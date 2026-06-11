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
import org.yoshiz.app.prioritarr.backend.clients.PlexClient
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

    private fun jsonClient(body: String): HttpClient = HttpClient(MockEngine {
        respond(
            ByteReadChannel(body),
            HttpStatusCode.OK,
            headersOf("Content-Type", ContentType.Application.Json.toString()),
        )
    }) { install(ContentNegotiation) { json() } }

    @Test
    fun `refreshMappings resolves plex key by tvdb id even when sonarr and plex titles differ`() = runTest {
        // The Re:Zero case: Sonarr title and Plex title differ, so a
        // title join would miss. The tvdb id (305089) is identical on
        // both sides and must carry the match.
        val sonarrBody =
            """[{"id":233,"title":"Re: ZERO, Starting Life in Another World","tvdbId":305089,"path":"/anime/Re Zero"}]"""
        val sonarr = SonarrClient("http://sonarr", "k", jsonClient(sonarrBody))

        val sectionsXml = """<MediaContainer><Directory key="5" title="Anime" type="show"/></MediaContainer>"""
        val showsXml = """
            <MediaContainer>
              <Directory ratingKey="85175" title="Re:ZERO -Starting Life in Another World-">
                <Guid id="tvdb://305089"/>
              </Directory>
            </MediaContainer>
        """.trimIndent()
        val plexHttp = HttpClient(MockEngine { req ->
            val url = req.url.toString()
            val body = when {
                "/library/sections/5/all" in url -> showsXml
                "/library/sections" in url -> sectionsXml
                else -> "<MediaContainer/>"
            }
            respond(ByteReadChannel(body), HttpStatusCode.OK)
        })
        val plex = PlexClient("http://plex:32400", "tok", plexHttp)

        // Tautulli must not be consulted for key sourcing when Plex is present.
        val tautulli = TautulliClient(
            "http://tautulli", "k",
            jsonClient("""{"response":{"result":"success","data":{}}}"""),
        )

        val state = MappingState()
        refreshMappings(sonarr, tautulli, InMemoryMappingCache(), state, plex = plex)

        assertEquals(233L, state.seriesIdForPlexKey("85175"))
        assertEquals("85175", state.plexKeyForSeriesId(233L))
    }

    @Test
    fun `plexKeyForSeriesId reverse-resolves the plex key for a series id`() {
        val state = MappingState()
        // Title intentionally absent — resolution must work off the id alone.
        state.apply(
            tvdb = emptyMap(),
            title = emptyMap(),
            keyToSid = mapOf("85175" to 233L),
            tautulliUp = true,
        )
        assertEquals("85175", state.plexKeyForSeriesId(233L))
        assertEquals(null, state.plexKeyForSeriesId(999L))
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
