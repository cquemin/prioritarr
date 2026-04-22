package org.yoshiz.app.prioritarr.backend.clients

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Thin wrapper around the Trakt.tv v2 API. Only the two endpoints
 * prioritarr actually needs:
 *
 *  - `GET /search/tvdb/{tvdbId}?type=show` — resolve a TVDB id to a
 *    Trakt show id (one-time per series; caller should cache).
 *  - `GET /sync/history/shows/{traktId}?type=episodes&limit=N` — per-user
 *    watch history for a specific show. Requires OAuth access token
 *    on top of the client id.
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
     */
    suspend fun getShowHistory(traktShowId: Long, limit: Int = 1000): JsonArray {
        val url = "$baseUrl/sync/history/shows/$traktShowId?type=episodes&limit=$limit&extended=full"
        return http.get(url) { applyHeaders() }.body()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyHeaders() {
        header("Content-Type", "application/json")
        header("trakt-api-version", "2")
        header("trakt-api-key", clientId)
        header("Authorization", "Bearer $accessToken")
    }
}
