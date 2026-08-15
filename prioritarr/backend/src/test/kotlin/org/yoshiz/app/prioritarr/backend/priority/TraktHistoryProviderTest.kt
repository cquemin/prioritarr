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
import org.yoshiz.app.prioritarr.backend.clients.TraktClient
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bug these cover: a failed tvdb→trakt id lookup used to rethrow
 * *before* writing anything to the id cache, so nothing was ever
 * memoised on failure. Every refresh cycle then re-issued a search for
 * every unresolved series — which is exactly what turns one 429 into a
 * self-sustaining storm across a 300-series library.
 */
class TraktHistoryProviderTest {

    private val ref = SeriesRef(seriesId = 238, title = "Slime", tvdbId = 329841)

    private fun searchCountingClient(
        onSearch: () -> Nothing,
        counter: () -> Unit,
    ): TraktClient {
        val http = HttpClient(MockEngine { request ->
            if (request.url.toString().contains("/search/tvdb/")) {
                counter()
                onSearch()
            }
            respond(
                ByteReadChannel("[]"),
                HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }) { install(ContentNegotiation) { json() } }
        return TraktClient("k", "t", http)
    }

    @Test fun does_not_repeat_a_failed_id_lookup_within_the_backoff_window() = runTest {
        var searchCalls = 0
        val trakt = searchCountingClient(
            onSearch = { throw IOException("connection reset") },
            counter = { searchCalls++ },
        )
        var now = Instant.parse("2026-07-31T12:00:00Z")
        val provider = TraktHistoryProvider(trakt, now = { now })

        assertTrue(provider.historyFor(ref).isFailure)
        assertEquals(1, searchCalls)

        // Within the backoff window the provider must not hit Trakt again.
        assertTrue(provider.historyFor(ref).isFailure)
        assertEquals(1, searchCalls)

        // Once the window elapses it retries exactly once more.
        now = now.plusSeconds(TRAKT_ID_LOOKUP_BACKOFF_SECONDS + 1)
        assertTrue(provider.historyFor(ref).isFailure)
        assertEquals(2, searchCalls)
    }

    @Test fun a_successful_lookup_after_a_failure_clears_the_backoff() = runTest {
        var searchCalls = 0
        var fail = true
        val http = HttpClient(MockEngine { request ->
            if (request.url.toString().contains("/search/tvdb/")) {
                searchCalls++
                if (fail) throw IOException("connection reset")
                respond(
                    ByteReadChannel("""[{"show":{"ids":{"trakt":121361}}}]"""),
                    HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            } else {
                respond(
                    ByteReadChannel("[]"),
                    HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            }
        }) { install(ContentNegotiation) { json() } }

        var now = Instant.parse("2026-07-31T12:00:00Z")
        val provider = TraktHistoryProvider(TraktClient("k", "t", http), now = { now })

        assertTrue(provider.historyFor(ref).isFailure)
        assertEquals(1, searchCalls)

        fail = false
        now = now.plusSeconds(TRAKT_ID_LOOKUP_BACKOFF_SECONDS + 1)
        assertTrue(provider.historyFor(ref).isSuccess)
        assertEquals(2, searchCalls)

        // Resolved id is memoised — no further search calls at all.
        assertTrue(provider.historyFor(ref).isSuccess)
        assertEquals(2, searchCalls)
    }

    /** Stand-in for the DB-backed store; same contract, no SQLite in the test. */
    private class FakeTraktIdStore : TraktIdStore {
        val entries = HashMap<Long, TraktIdEntry>()
        override fun lookup(tvdbId: Long): TraktIdEntry? = entries[tvdbId]
        override fun save(tvdbId: Long, traktId: Long?) {
            entries[tvdbId] = TraktIdEntry(traktId)
        }
    }

    private fun resolvingClient(onSearch: () -> Unit): TraktClient {
        val http = HttpClient(MockEngine { request ->
            if (request.url.toString().contains("/search/tvdb/")) {
                onSearch()
                respond(
                    ByteReadChannel("""[{"show":{"ids":{"trakt":121361}}}]"""),
                    HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            } else {
                respond(
                    ByteReadChannel("[]"),
                    HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            }
        }) { install(ContentNegotiation) { json() } }
        return TraktClient("k", "t", http)
    }

    @Test fun a_persisted_id_survives_a_restart_and_skips_the_search() = runTest {
        var searchCalls = 0
        val store = FakeTraktIdStore()
        store.save(329841, 121361)

        // Fresh provider == fresh process: in-memory cache is empty.
        val provider = TraktHistoryProvider(resolvingClient { searchCalls++ }, store = store)

        assertTrue(provider.historyFor(ref).isSuccess)
        assertEquals(0, searchCalls)
    }

    @Test fun a_resolved_id_is_written_through_to_the_store() = runTest {
        val store = FakeTraktIdStore()
        val provider = TraktHistoryProvider(resolvingClient { }, store = store)

        assertTrue(provider.historyFor(ref).isSuccess)
        assertEquals(121361L, store.lookup(329841)?.traktId)
    }

    @Test fun a_persisted_not_found_is_honoured_without_searching() = runTest {
        var searchCalls = 0
        val store = FakeTraktIdStore()
        store.save(329841, null)

        val provider = TraktHistoryProvider(resolvingClient { searchCalls++ }, store = store)
        val result = provider.historyFor(ref)

        assertTrue(result.isSuccess)
        assertEquals(emptyList(), result.getOrNull())
        assertEquals(0, searchCalls)
    }

    @Test fun series_without_tvdb_id_never_calls_trakt() = runTest {
        var calls = 0
        val http = HttpClient(MockEngine {
            calls++
            respond(
                ByteReadChannel("[]"),
                HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }) { install(ContentNegotiation) { json() } }

        val provider = TraktHistoryProvider(TraktClient("k", "t", http))
        val result = provider.historyFor(ref.copy(tvdbId = null))

        assertTrue(result.isSuccess)
        assertEquals(emptyList(), result.getOrNull())
        assertEquals(0, calls)
    }
}
