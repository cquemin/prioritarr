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
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test fun search_throws_typed_rate_limit_error_on_429() = runTest {
        // Trakt answers 429 with a text/plain body. Ktor's ContentNegotiation
        // would try to deserialize that into JsonArray and throw
        // NoTransformationFoundException, hiding the real status.
        val http = HttpClient(MockEngine {
            respond(
                ByteReadChannel("Rate Limit Exceeded"),
                HttpStatusCode.TooManyRequests,
                headersOf(
                    "Content-Type" to listOf(ContentType.Text.Plain.toString()),
                    "Retry-After" to listOf("42"),
                ),
            )
        }) { install(ContentNegotiation) { json() } }

        val err = assertFailsWith<TraktRateLimitedException> {
            TraktClient("k", "t", http).searchShowByTvdb(329841)
        }
        assertEquals(42L, err.retryAfterSeconds)
    }

    @Test fun show_history_throws_typed_rate_limit_error_on_429() = runTest {
        val http = HttpClient(MockEngine {
            respond(
                ByteReadChannel("Rate Limit Exceeded"),
                HttpStatusCode.TooManyRequests,
                headersOf("Content-Type", ContentType.Text.Plain.toString()),
            )
        }) { install(ContentNegotiation) { json() } }

        val err = assertFailsWith<TraktRateLimitedException> {
            TraktClient("k", "t", http).getShowHistory(121361)
        }
        assertNull(err.retryAfterSeconds)
    }

    @Test fun parks_further_calls_while_the_rate_limit_window_is_open() = runTest {
        var engineCalls = 0
        val http = HttpClient(MockEngine {
            engineCalls++
            respond(
                ByteReadChannel("Rate Limit Exceeded"),
                HttpStatusCode.TooManyRequests,
                headersOf(
                    "Content-Type" to listOf(ContentType.Text.Plain.toString()),
                    "Retry-After" to listOf("60"),
                ),
            )
        }) { install(ContentNegotiation) { json() } }

        var now = Instant.parse("2026-07-31T12:00:00Z")
        val trakt = TraktClient("k", "t", http, now = { now })

        assertFailsWith<TraktRateLimitedException> { trakt.searchShowByTvdb(1) }
        assertEquals(1, engineCalls)

        // Still inside the 60s window: must fail fast, no HTTP call.
        assertFailsWith<TraktRateLimitedException> { trakt.searchShowByTvdb(2) }
        assertEquals(1, engineCalls)

        // A different endpoint is parked by the same breaker.
        assertFailsWith<TraktRateLimitedException> { trakt.getShowHistory(99) }
        assertEquals(1, engineCalls)

        // Window elapsed: traffic resumes.
        now = now.plusSeconds(61)
        assertFailsWith<TraktRateLimitedException> { trakt.searchShowByTvdb(3) }
        assertEquals(2, engineCalls)
    }

    @Test fun rate_limit_without_retry_after_uses_default_cooldown() = runTest {
        var engineCalls = 0
        val http = HttpClient(MockEngine {
            engineCalls++
            respond(
                ByteReadChannel("Rate Limit Exceeded"),
                HttpStatusCode.TooManyRequests,
                headersOf("Content-Type", ContentType.Text.Plain.toString()),
            )
        }) { install(ContentNegotiation) { json() } }

        var now = Instant.parse("2026-07-31T12:00:00Z")
        val trakt = TraktClient("k", "t", http, now = { now })

        assertFailsWith<TraktRateLimitedException> { trakt.searchShowByTvdb(1) }
        assertFailsWith<TraktRateLimitedException> { trakt.searchShowByTvdb(2) }
        assertEquals(1, engineCalls)

        now = now.plusSeconds(TRAKT_DEFAULT_RATE_LIMIT_COOLDOWN_SECONDS + 1)
        assertFailsWith<TraktRateLimitedException> { trakt.searchShowByTvdb(3) }
        assertEquals(2, engineCalls)
    }

    @Test fun successful_call_after_window_clears_the_breaker() = runTest {
        var engineCalls = 0
        val http = HttpClient(MockEngine {
            engineCalls++
            respond(
                ByteReadChannel("[]"),
                HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }) { install(ContentNegotiation) { json() } }

        val trakt = TraktClient("k", "t", http)
        assertNull(trakt.searchShowByTvdb(1))
        assertNull(trakt.searchShowByTvdb(2))
        assertEquals(2, engineCalls)
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
