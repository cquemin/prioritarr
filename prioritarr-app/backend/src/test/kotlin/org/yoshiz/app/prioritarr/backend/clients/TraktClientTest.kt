package org.yoshiz.app.prioritarr.backend.clients

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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraktClientTest {

    @Test fun search_by_tvdb_parses_first_result() = runTest {
        val responseBody = """
            [
              {"type":"show","score":1000,"show":{"title":"Slime","year":2018,"ids":{"trakt":121361,"slug":"that-time","tvdb":329841,"imdb":"tt9303880","tmdb":76885}}}
            ]
        """.trimIndent()

        val http = HttpClient(MockEngine { request ->
            assertTrue(request.url.toString().contains("/search/tvdb/329841"))
            assertEquals("2", request.headers["trakt-api-version"])
            assertEquals("test-client-id", request.headers["trakt-api-key"])
            assertEquals("Bearer test-token", request.headers["Authorization"])
            respond(
                ByteReadChannel(responseBody),
                HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }) { install(ContentNegotiation) { json() } }

        val trakt = TraktClient("test-client-id", "test-token", http)
        assertEquals(121361L, trakt.searchShowByTvdb(329841))
    }

    @Test fun search_returns_null_on_empty_array() = runTest {
        val http = HttpClient(MockEngine {
            respond(
                ByteReadChannel("[]"),
                HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }) { install(ContentNegotiation) { json() } }

        assertNull(TraktClient("k", "t", http).searchShowByTvdb(999))
    }

    @Test fun show_history_returns_raw_array() = runTest {
        val responseBody = """
            [
              {"id":1,"watched_at":"2026-04-17T22:08:45.000Z","action":"scrobble","type":"episode",
               "episode":{"season":4,"number":3,"title":"...","ids":{"trakt":100,"tvdb":1,"imdb":"t","tmdb":2}},
               "show":{"title":"Slime","year":2018,"ids":{"trakt":121361,"slug":"that-time"}}}
            ]
        """.trimIndent()

        val http = HttpClient(MockEngine { request ->
            val url = request.url.toString()
            assertTrue(url.contains("/sync/history/shows/121361"))
            assertTrue(url.contains("type=episodes"))
            assertTrue(url.contains("limit="))
            respond(
                ByteReadChannel(responseBody),
                HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }) { install(ContentNegotiation) { json() } }

        val result = TraktClient("k", "t", http).getShowHistory(121361, limit = 500)
        assertEquals(1, result.size)
        val first = result[0].jsonObject
        val episode = first["episode"]?.jsonObject
        assertNotNull(episode)
        assertEquals("4", episode!!["season"]?.jsonPrimitive?.content)
        assertEquals("3", episode["number"]?.jsonPrimitive?.content)
    }
}
