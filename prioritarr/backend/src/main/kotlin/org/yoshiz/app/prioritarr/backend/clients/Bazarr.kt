package org.yoshiz.app.prioritarr.backend.clients

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess

/**
 * Thin Bazarr REST client. Surface is intentionally tiny — prioritarr
 * only ever kicks Bazarr to start a per-item subtitle search; Bazarr
 * itself handles the actual provider rotation, scoring, and Whisper
 * fallback.
 *
 * Header is `X-API-KEY` (all-caps), unlike Sonarr/Radarr's `X-Api-Key`.
 *
 * Per-language for episodes (Bazarr exposes
 * `PATCH /api/episodes/subtitles?seriesid=&episodeid=&language=`) and
 * per-action for movies (`PATCH /api/movies?radarrid=&action=search-missing`,
 * which covers all wanted languages on the movie's language profile in
 * a single call).
 */
class BazarrClient(
    baseUrl: String,
    private val apiKey: String,
    private val http: HttpClient,
) {
    private val root: String = baseUrl.trimEnd('/')

    /**
     * Ask Bazarr to immediately search providers for a single
     * episode-language pair. Bazarr returns 204 with NO body on accept
     * (see class KDoc) — the actual search runs asynchronously inside
     * Bazarr, so there is nothing to deserialize. Judge success purely
     * on HTTP status; calling `.body<JsonElement?>()` on an empty 204
     * either throws or yields a false negative, which previously made
     * every successful trigger look like a failure.
     *
     * Idempotent from Bazarr's side — calling twice in quick succession
     * just re-queues the search.
     */
    suspend fun triggerEpisodeSearch(
        sonarrSeriesId: Long,
        sonarrEpisodeId: Long,
        language: String,
    ): Boolean = patch(
        "/api/episodes/subtitles",
        mapOf(
            "seriesid" to sonarrSeriesId.toString(),
            "episodeid" to sonarrEpisodeId.toString(),
            "language" to language,
            "forced" to "false",
            "hi" to "false",
        ),
    )

    /**
     * Ask Bazarr to search for ALL missing subtitle languages on a
     * single movie in one shot. Bazarr reads the movie's language
     * profile to know what to look for.
     *
     * Preferred over per-language [triggerMovieSubtitleSearch] because
     * one HTTP round-trip covers en+fr+anything else without prioritarr
     * needing to know about Bazarr's profile config.
     */
    suspend fun triggerMovieSearch(radarrId: Long): Boolean = patch(
        "/api/movies",
        mapOf(
            "radarrid" to radarrId.toString(),
            "action" to "search-missing",
        ),
    )

    /** Fires the PATCH and reports success by HTTP status alone — see [triggerEpisodeSearch]. */
    private suspend fun patch(path: String, params: Map<String, String>): Boolean =
        http.patch("$root$path") {
            method = HttpMethod.Patch
            header("X-API-KEY", apiKey)
            for ((k, v) in params) parameter(k, v)
        }.status.isSuccess()
}
