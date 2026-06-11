package org.yoshiz.app.prioritarr.backend.priority

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.yoshiz.app.prioritarr.backend.clients.PlexClient
import org.yoshiz.app.prioritarr.backend.mapping.MappingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlexHistoryProviderTest {

    @Test
    fun `historyFor resolves the plex key by series id, not by title`() = runTest {
        // Plex reports S04E02 watched. The Sonarr title deliberately
        // does NOT match the Plex title, so a title-based lookup would
        // return nothing — the id mapping must carry the resolution.
        val allLeaves = """
            <MediaContainer>
              <Video ratingKey="1" parentIndex="4" index="2" viewCount="1" lastViewedAt="1700000000"/>
              <Video ratingKey="2" parentIndex="4" index="3" viewCount="0"/>
            </MediaContainer>
        """.trimIndent()
        val requestedPaths = mutableListOf<String>()
        val http = HttpClient(MockEngine { req ->
            requestedPaths += req.url.encodedPath
            respond(ByteReadChannel(allLeaves), HttpStatusCode.OK)
        })
        val plex = PlexClient("http://plex:32400", "tok", http)
        val mappings = MappingState().apply { inject("85175", 233L) }

        val provider = PlexHistoryProvider(plex, mappings)
        val ref = SeriesRef(seriesId = 233L, title = "Sonarr Title That Does Not Match Plex", tvdbId = 305089L)
        val events = provider.historyFor(ref).getOrThrow()

        assertEquals(1, events.size)
        assertEquals(4 to 2, events[0].season to events[0].episode)
        assertTrue(
            requestedPaths.any { it.contains("/library/metadata/85175/allLeaves") },
            "expected Plex to be queried under the id-resolved rating-key 85175, got $requestedPaths",
        )
    }
}
