package org.yoshiz.app.prioritarr.backend.clients

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Thin wrapper around the Trakt.tv v2 API. Endpoints prioritarr uses:
 *
 *  - `GET  /search/tvdb/{tvdbId}?type=show` — resolve a TVDB id to a
 *    Trakt show id (one-time per series; caller should cache).
 *  - `GET  /sync/history/shows/{traktId}?type=episodes&limit=N` — per-user
 *    watch history for a specific show. Requires OAuth access token
 *    on top of the client id.
 *  - `POST /sync/history` — write back episodes as watched. Used by
 *    the cross-source sync to mirror Plex's watch state into Trakt.
 *
 * Auth plumbing stays out of this class — callers get the access
 * token from config (user pastes it after running Trakt's OAuth
 * device-code flow once; the refresh flow is a future follow-up).
 */
class TraktClient(
    private val clientId: String,
    private val accessToken: String,
    private val http: HttpClient,
    private val baseUrl: String = "https://api.trakt.tv",
) {
    private val logger = LoggerFactory.getLogger(TraktClient::class.java)

    /**
     * Look up a TVDB id → Trakt show id. Returns null if no show is
     * found at that TVDB id (unusual; TVDB coverage on Trakt is very
     * high but shows flagged as "removed from TVDB" occasionally miss).
     */
    suspend fun searchShowByTvdb(tvdbId: Long): Long? {
        val url = "$baseUrl/search/tvdb/$tvdbId?type=show"
        val results: JsonArray = http.get(url) { applyHeaders() }.body()
        return results.firstOrNull()
            ?.jsonObject?.get("show")
            ?.jsonObject?.get("ids")
            ?.jsonObject?.get("trakt")
            ?.jsonPrimitive?.longOrNull
    }

    /**
     * Per-show watch history, episode-type only. `extended=full` is
     * requested so each episode payload includes `number_abs` (the
     * absolute episode number) — needed for canonicalising long-running
     * anime where Trakt's per-arc seasons drift from Sonarr/TVDB's
     * cours-based numbering.
     *
     * Returns the raw JSON array from Trakt. Each element has:
     *   {
     *     "watched_at": "2026-04-17T22:08:45.000Z",
     *     "episode": {"season": N, "number": M, "number_abs": K, ...},
     *     ...
     *   }
     *
     * Fallback: Trakt's per-show `/sync/history/shows/{id}` endpoint
     * intermittently returns `500 handler.sync.getHistory` for some
     * user/token combinations (reproduced on the April 2026 scopes).
     * When that happens we fall back to fetching the account-wide
     * `/sync/history?type=episodes` and filtering client-side by
     * `show.ids.trakt == traktShowId`. That path is less precise
     * (capped at `limit` total rows across every show) but robust.
     */
    suspend fun getShowHistory(traktShowId: Long, limit: Int = 1000): JsonArray {
        val perShowUrl = "$baseUrl/sync/history/shows/$traktShowId?type=episodes&limit=$limit&extended=full"
        // Manual status check: Ktor's ContentNegotiation eagerly deserializes
        // any response body into JsonArray, so a 5xx error body ("{error:…}")
        // throws a SerializationException before any try/catch on HTTP status
        // can fire. Taking HttpResponse first lets us branch on .status.
        val perShowResp: HttpResponse = http.get(perShowUrl) { applyHeaders() }
        if (perShowResp.status.value < 500) return perShowResp.body()

        logger.warn(
            "trakt per-show history {} for trakt_show_id={}, falling back to account-wide /sync/history",
            perShowResp.status, traktShowId,
        )
        val allUrl = "$baseUrl/sync/history?type=episodes&limit=$limit&extended=full"
        val all: JsonArray = http.get(allUrl) { applyHeaders() }.body()
        return buildJsonArray {
            for (el in all) {
                val obj = (el as? JsonObject) ?: continue
                val showId = obj["show"]?.jsonObject
                    ?.get("ids")?.jsonObject
                    ?.get("trakt")?.jsonPrimitive?.longOrNull
                if (showId == traktShowId) add(el)
            }
        }
    }

    /**
     * Add episodes to the user's watch history. `episodes` is a list of
     * (season, episode) pairs. Trakt's response includes per-episode
     * outcome counts (`added.episodes`, `not_found.episodes` etc.) — we
     * return the raw payload so the caller can surface those counts.
     *
     * The Trakt /sync/history payload is shaped per show, so all
     * episodes in one call must belong to [traktShowId]. Callers that
     * sync multiple shows make one POST per show. `watchedAt` is the
     * single timestamp stamped on every added episode (Plex doesn't
     * give us per-episode resolution we'd want to pass through, so the
     * common-case "all marked watched at the time of sync" is fine).
     */
    suspend fun addEpisodesToHistory(
        traktShowId: Long,
        episodes: List<Pair<Int, Int>>,
        watchedAt: Instant,
    ): JsonObject {
        val ts = DateTimeFormatter.ISO_INSTANT.format(watchedAt)
        // Trakt expects {shows: [{ids: {trakt}, seasons: [{number, episodes: [{number, watched_at}]}]}]}
        // — flatten our flat (season, episode) pair list into that
        // grouped shape.
        val grouped = episodes.groupBy({ it.first }, { it.second })
        val payload = buildJsonObject {
            putJsonArray("shows") {
                addJsonObject {
                    putJsonObject("ids") { put("trakt", traktShowId) }
                    putJsonArray("seasons") {
                        for ((season, eps) in grouped) {
                            addJsonObject {
                                put("number", season)
                                putJsonArray("episodes") {
                                    for (ep in eps) {
                                        addJsonObject {
                                            put("number", ep)
                                            put("watched_at", ts)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return http.post("$baseUrl/sync/history") {
            applyHeaders()
            contentType(ContentType.Application.Json)
            setBody(payload)
        }.body()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyHeaders() {
        header("Content-Type", "application/json")
        header("trakt-api-version", "2")
        header("trakt-api-key", clientId)
        header("Authorization", "Bearer $accessToken")
    }
}
