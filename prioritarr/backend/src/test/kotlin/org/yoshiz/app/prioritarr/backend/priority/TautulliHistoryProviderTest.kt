package org.yoshiz.app.prioritarr.backend.priority

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
import org.yoshiz.app.prioritarr.backend.clients.TautulliClient
import org.yoshiz.app.prioritarr.backend.mapping.MappingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TautulliHistoryProviderTest {

    @Test
    fun `historyFor resolves grandparent rating key by series id, not title`() = runTest {
        val historyBody = """
            {"response":{"result":"success","data":{"data":[
              {"parent_media_index":"4","media_index":"2","date":1700000000}
            ]}}}
        """.trimIndent()
        val requestedUrls = mutableListOf<String>()
        val http = HttpClient(MockEngine { req ->
            requestedUrls += req.url.toString()
            respond(
                ByteReadChannel(historyBody),
                HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }) { install(ContentNegotiation) { json() } }
        val tautulli = TautulliClient("http://tautulli", "k", http)
        val mappings = MappingState().apply { inject("85175", 233L) }

        val provider = TautulliHistoryProvider(tautulli, mappings)
        // Sonarr title differs from Plex's grandparent_title — the
        // id-resolved fast path must be used, not the title-filter fallback.
        val ref = SeriesRef(seriesId = 233L, title = "Mismatched Sonarr Title", tvdbId = 305089L)
        val events = provider.historyFor(ref).getOrThrow()

        assertEquals(1, events.size)
        assertEquals(4 to 2, events[0].season to events[0].episode)
        assertTrue(
            requestedUrls.any { it.contains("grandparent_rating_key=85175") },
            "expected fast-path query by id-resolved key 85175, got $requestedUrls",
        )
    }
}
