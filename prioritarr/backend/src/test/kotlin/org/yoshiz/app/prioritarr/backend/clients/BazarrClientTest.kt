package org.yoshiz.app.prioritarr.backend.clients

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the thin Bazarr REST client. Bazarr's API surface for "find
 * me a subtitle, now" is per-language for episodes (PATCH
 * /episodes/subtitles) and per-action for movies (PATCH /movies).
 *
 * Header note: Bazarr uses `X-API-KEY` (all-caps) — different from
 * Sonarr/Radarr's `X-Api-Key`. Easy to get wrong; the assertions here
 * enforce it explicitly.
 */
class BazarrClientTest {

    private data class CapturedCall(
        val method: HttpMethod,
        val url: String,
        val headers: Map<String, List<String>>,
    )

    private fun clientCapturing(): Pair<HttpClient, MutableList<CapturedCall>> {
        val calls = mutableListOf<CapturedCall>()
        val engine = MockEngine { req ->
            calls += CapturedCall(
                method = req.method,
                url = req.url.toString(),
                headers = req.headers.entries().associate { it.key to it.value },
            )
            respond(
                content = ByteReadChannel(""),
                status = HttpStatusCode.NoContent,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json() }
        }
        return http to calls
    }

    @Test
    fun `triggerEpisodeSearch issues PATCH with seriesid episodeid language and X-API-KEY header`() = runTest {
        val (http, calls) = clientCapturing()
        val client = BazarrClient("http://bazarr:6767/bazarr", "secret-key", http)

        client.triggerEpisodeSearch(sonarrSeriesId = 238, sonarrEpisodeId = 7777, language = "en")

        assertEquals(1, calls.size, "expected exactly one HTTP call")
        val call = calls[0]
        assertEquals(HttpMethod.Patch, call.method)
        assertTrue(call.url.contains("/api/episodes/subtitles"), "unexpected path: ${call.url}")
        assertTrue(call.url.contains("seriesid=238"), "missing seriesid: ${call.url}")
        assertTrue(call.url.contains("episodeid=7777"), "missing episodeid: ${call.url}")
        assertTrue(call.url.contains("language=en"), "missing language: ${call.url}")
        assertTrue(call.url.contains("forced=false"), "missing forced=false: ${call.url}")
        assertTrue(call.url.contains("hi=false"), "missing hi=false: ${call.url}")
        assertEquals(listOf("secret-key"), call.headers["X-API-KEY"], "wrong/missing X-API-KEY header")
    }

    @Test
    fun `triggerMovieSearch issues PATCH movies with radarrid and search-missing action`() = runTest {
        val (http, calls) = clientCapturing()
        val client = BazarrClient("http://bazarr:6767/bazarr", "k", http)

        client.triggerMovieSearch(radarrId = 1671)

        assertEquals(1, calls.size)
        val call = calls[0]
        assertEquals(HttpMethod.Patch, call.method)
        // PATCH /movies (NOT /movies/subtitles) — Bazarr's "action" endpoint
        // takes radarrid + action=search-missing and covers all wanted langs
        // in a single call.
        assertTrue(call.url.contains("/api/movies?") || call.url.contains("/api/movies&"),
            "path should be /api/movies with query string: ${call.url}")
        assertTrue(call.url.contains("radarrid=1671"))
        assertTrue(call.url.contains("action=search-missing"))
        assertEquals(listOf("k"), call.headers["X-API-KEY"])
    }

    @Test
    fun `baseUrl trailing slash is normalised so we never get double slashes in path`() = runTest {
        val (http, calls) = clientCapturing()
        // Note trailing /; common when env var is set to .../bazarr/
        val client = BazarrClient("http://bazarr:6767/bazarr/", "k", http)

        client.triggerEpisodeSearch(1, 2, "fr")

        // Bug class: "http://bazarr:6767/bazarr//api/episodes/subtitles" with
        // double slash before /api — Bazarr's nginx happens to accept this
        // but we should normalise so it works behind any reverse proxy.
        assertTrue(
            !calls[0].url.contains("bazarr//"),
            "double slash in url: ${calls[0].url}",
        )
    }
}
