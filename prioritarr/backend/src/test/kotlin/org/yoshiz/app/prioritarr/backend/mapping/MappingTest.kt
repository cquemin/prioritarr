package org.yoshiz.app.prioritarr.backend.mapping

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.clients.TautulliClient
import kotlin.test.Test
import kotlin.test.assertEquals

class MappingTest {

    @Test
    fun `extractTvdbFromGuids supports new and old plex agent URIs`() {
        assertEquals(267440L, extractTvdbFromGuids(listOf("tvdb://267440")))
        assertEquals(267440L, extractTvdbFromGuids(listOf("com.plexapp.agents.thetvdb://267440")))
        assertEquals(null, extractTvdbFromGuids(listOf("imdb://tt123", "tmdb://9")))
    }

    @Test
    fun `extractFolderName strips trailing slashes and lowercases`() {
        assertEquals("attack on titan", extractFolderName("/storage/media/video/anime/Attack on Titan"))
        assertEquals("attack on titan", extractFolderName("/storage/media/video/anime/Attack on Titan/"))
        assertEquals("attack on titan", extractFolderName("D:\\anime\\Attack on Titan"))
    }

    @Test
    fun `normaliseTitle trims and lowercases`() {
        assertEquals("attack on titan", normaliseTitle("  Attack on Titan  "))
    }

    @Test
    fun `refreshMappings picks up cached mapping without re-matching`() = runTest {
        // Sonarr: one series with id=1, tvdbId=267440.
        val sonarrBody = """[{"id":1,"title":"Attack on Titan","tvdbId":267440,"path":"/anime/Attack on Titan"}]"""
        // Tautulli get_libraries: one show section.
        val libsBody = """{"response":{"result":"success","data":[{"section_id":"1","section_name":"TV","section_type":"show"}]}}"""
        // Tautulli get_library_media_info for section 1: one show with rating_key=5000.
        val mediaBody = """{"response":{"result":"success","data":{"data":[{"rating_key":"5000","title":"Attack on Titan"}]}}}"""

        val httpSonarr = HttpClient(MockEngine { req ->
            respond(
                ByteReadChannel(sonarrBody),
                HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }) { install(ContentNegotiation) { json() } }

        val httpTautulli = HttpClient(MockEngine { req ->
            val url = req.url.toString()
            val body = when {
                "cmd=get_libraries" in url -> libsBody
                "cmd=get_library_media_info" in url -> mediaBody
                else -> """{"response":{"result":"success","data":{}}}"""
            }
            respond(
                ByteReadChannel(body),
                HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }) { install(ContentNegotiation) { json() } }

        val sonarr = SonarrClient("http://sonarr", "k", httpSonarr)
        val tautulli = TautulliClient("http://tautulli", "k", httpTautulli)
        val cache = InMemoryMappingCache().apply { save(mapOf("5000" to 1L)) }
        val state = MappingState()

        val stats = refreshMappings(sonarr, tautulli, cache, state)

        assertEquals(1, stats.cached, "should have used the persistent mapping cache entry")
        assertEquals(0, stats.tvdb)
        assertEquals(mapOf("5000" to 1L), state.plexKeyToSeriesId)
        assertEquals(1L, state.seriesIdForPlexKey("5000"))
    }
}
