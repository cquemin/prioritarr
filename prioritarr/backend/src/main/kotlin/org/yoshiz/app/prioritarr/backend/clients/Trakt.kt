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
    initialAccessToken: String,
    private val http: HttpClient,
    private val baseUrl: String = org.yoshiz.app.prioritarr.backend.TraktApi.BASE_URL,
    /**
     * Optional refresh callback. When a Trakt call returns 401, the
     * client invokes this once and retries. The callback should mint a
     * fresh access_token via [TraktOAuth.refresh] (using a stored
     * refresh_token), persist it, and return the new token. Returning
     * null = unable to refresh (no refresh_token, or refresh failed),
     * which lets the original 401 propagate.
     */
    private val onRefreshNeeded: (suspend () -> String?)? = null,
) {
    private val logger = LoggerFactory.getLogger(TraktClient::class.java)

    // Volatile because the proactive refresh scheduler may write while
    // a request coroutine reads. Atomic writes are sufficient — every
    // call re-reads `accessToken` at header-build time.
    @Volatile
    private var accessToken: String = initialAccessToken

    /**
     * Hot-swap the access token in the live client (called by the
     * proactive refresh job after persisting). No reconnection needed
     * — the next request just uses the new bearer.
     */
    fun updateAccessToken(newToken: String) {
        accessToken = newToken
    }

    /**
     * Wrap a Trakt API call with one-shot 401 retry. The retry only
     * fires if [onRefreshNeeded] is wired AND the refresh callback
     * actually returns a new token. Anything else propagates.
     */
    private suspend fun <T> withAuth(block: suspend () -> T): T = try {
        block()
    } catch (e: io.ktor.client.plugins.ResponseException) {
        if (e.response.status.value == 401 && onRefreshNeeded != null) {
            val newToken = onRefreshNeeded.invoke()
            if (newToken != null) {
                accessToken = newToken
                block()
            } else throw e
        } else throw e
    }

    /**
     * Look up a TVDB id → Trakt show id. Returns null if no show is
     * found at that TVDB id (unusual; TVDB coverage on Trakt is very
     * high but shows flagged as "removed from TVDB" occasionally miss).
     */
    suspend fun searchShowByTvdb(tvdbId: Long): Long? = withAuth {
        val url = "$baseUrl/search/tvdb/$tvdbId?type=show"
        val results: JsonArray = http.get(url) { applyHeaders() }.body()
        results.firstOrNull()
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
    suspend fun getShowHistory(traktShowId: Long, limit: Int = 1000): JsonArray = withAuth {
        val perShowUrl = "$baseUrl/sync/history/shows/$traktShowId?type=episodes&limit=$limit&extended=full"
        // Manual status check: Ktor's ContentNegotiation eagerly deserializes
        // any response body into JsonArray, so a 5xx error body ("{error:…}")
        // throws a SerializationException before any try/catch on HTTP status
        // can fire. Taking HttpResponse first lets us branch on .status.
        val perShowResp: HttpResponse = http.get(perShowUrl) { applyHeaders() }
        if (perShowResp.status.value < 500) {
            return@withAuth perShowResp.body<JsonArray>()
        }

        logger.warn(
            "trakt per-show history {} for trakt_show_id={}, falling back to account-wide /sync/history",
            perShowResp.status, traktShowId,
        )
        val allUrl = "$baseUrl/sync/history?type=episodes&limit=$limit&extended=full"
        val all: JsonArray = http.get(allUrl) { applyHeaders() }.body()
        buildJsonArray {
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
    ): JsonObject = withAuth {
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
        http.post("$baseUrl/sync/history") {
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

/**
 * Trakt OAuth device-code flow helper. Stateless — no access token
 * required (the whole point of this is to obtain one). Lives apart from
 * TraktClient because the latter requires an access_token at
 * construction; this helper exists precisely to mint one.
 *
 * Reference: https://trakt.docs.apiary.io/#reference/authentication-devices
 *
 * Three-step dance:
 *   1. [begin] returns a user_code + verification_url. Show those to the
 *      operator, who navigates to trakt.tv/activate and types the code.
 *   2. [poll] is hit every `interval` seconds with the device_code from
 *      step 1. Returns null while still pending; returns tokens on success.
 *   3. Caller persists access_token + refresh_token + expires_at so
 *      subsequent TraktClient instances can use them.
 *
 * [refresh] handles token rotation when the access_token nears expiry
 * (Trakt issues 90-day tokens with 90-day refresh tokens).
 */
class TraktOAuth(
    private val clientId: String,
    private val clientSecret: String,
    private val http: HttpClient,
    private val baseUrl: String = org.yoshiz.app.prioritarr.backend.TraktApi.BASE_URL,
) {
    /** Begin a device-code flow. Returns the activation details to show the user. */
    suspend fun begin(): JsonObject = http.post("$baseUrl/oauth/device/code") {
        contentType(ContentType.Application.Json)
        setBody(buildJsonObject { put("client_id", clientId) })
    }.body()

    /**
     * Poll for the user completing activation. Returns the token
     * payload on success, or null if Trakt returned 400
     * "authorization_pending" (the operator hasn't finished yet).
     * Throws on any other error so the caller can stop polling.
     */
    suspend fun poll(deviceCode: String): JsonObject? {
        // Catch the per-status exception to distinguish "still pending"
        // (400) from real failures. The default JSON client has
        // expectSuccess=true, which would auto-throw on 4xx; we have
        // to inspect the wrapped status to recover the right response.
        val resp: HttpResponse = try {
            http.post("$baseUrl/oauth/device/token") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("code", deviceCode)
                    put("client_id", clientId)
                    put("client_secret", clientSecret)
                })
            }
        } catch (e: io.ktor.client.plugins.ResponseException) {
            e.response
        }
        return when (resp.status.value) {
            200 -> resp.body()
            400 -> null  // authorization_pending — keep polling
            404 -> throw IllegalStateException("device code expired or unknown — restart the flow")
            409 -> throw IllegalStateException("already used — re-run begin()")
            410 -> throw IllegalStateException("flow expired (interval not respected) — restart")
            418 -> throw IllegalStateException("user denied access in trakt.tv")
            429 -> null  // slow_down — caller already sleeps the interval
            else -> throw IllegalStateException("trakt /oauth/device/token returned ${resp.status.value}")
        }
    }

    /** Trade a refresh_token for a fresh access_token + new refresh_token. */
    suspend fun refresh(refreshToken: String, redirectUri: String = "urn:ietf:wg:oauth:2.0:oob"): JsonObject =
        http.post("$baseUrl/oauth/token") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("refresh_token", refreshToken)
                put("client_id", clientId)
                put("client_secret", clientSecret)
                put("redirect_uri", redirectUri)
                put("grant_type", "refresh_token")
            })
        }.body()
}
