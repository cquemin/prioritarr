package org.yoshiz.app.prioritarr.backend.clients

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PlexClientTest {

    private fun clientReturning(body: String): Pair<HttpClient, MutableList<String>> {
        val headers = mutableListOf<String>()
        val engine = MockEngine { req ->
            headers += req.headers["X-Plex-Token"] ?: ""
            respond(ByteReadChannel(body), HttpStatusCode.OK)
        }
        return HttpClient(engine) to headers
    }

    @Test
    fun `getShowEpisodesWatchStatus parses viewCount and lastViewedAt`() = runTest {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <MediaContainer size="2">
              <Video parentIndex="1" index="1" viewCount="2" lastViewedAt="1700000000"/>
              <Video parentIndex="1" index="2" viewCount="0"/>
            </MediaContainer>
        """.trimIndent()
        val (http, tokens) = clientReturning(xml)
        val client = PlexClient("http://plex:32400", "tok", http)
        val episodes = client.getShowEpisodesWatchStatus("ABC")
        assertEquals(2, episodes.size)
        assertEquals(true, episodes[0]["watched"])
        assertEquals(1700000000L, episodes[0]["last_viewed_at"])
        assertEquals(false, episodes[1]["watched"])
        assertEquals(null, episodes[1]["last_viewed_at"])
        assertEquals("tok", tokens[0])
    }

    @Test
    fun `getLibrarySections extracts key title type`() = runTest {
        val xml = """
            <MediaContainer size="1">
              <Directory key="1" title="TV" type="show"/>
            </MediaContainer>
        """.trimIndent()
        val (http, _) = clientReturning(xml)
        val client = PlexClient("http://plex:32400", "t", http)
        val sections = client.getLibrarySections()
        assertEquals(1, sections.size)
        assertEquals("show", sections[0]["type"])
    }
}
