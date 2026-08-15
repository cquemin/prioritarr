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
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.config.PriorityThresholds
import org.yoshiz.app.prioritarr.backend.database.Database
import java.io.File
import java.nio.file.Files
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end guard on the *persisted* TTL, against a real SQLite file.
 *
 * The unit tests pin `priorityCacheTtlMinutes` in isolation; this pins
 * the thing that actually matters — the `expires_at` column written for
 * a series whose watch history was incomplete. A regression anywhere in
 * the chain (outcome → snapshot flag → cache write) shows up here.
 */
class PriorityCacheTtlIntegrationTest {

    private val tempDb: File =
        Files.createTempFile("priority-ttl-test", ".db").toFile().also { it.deleteOnExit() }

    @AfterTest fun cleanup() { tempDb.delete() }

    private class FixedThresholds : ThresholdsSource {
        override fun current(): PriorityThresholds = PriorityThresholds()
        override fun save(next: PriorityThresholds) {}
        override fun reset() {}
    }

    /** Provider that always fails — stands in for a rate-limited Trakt. */
    private class FailingProvider(override val name: String) : WatchHistoryProvider {
        override suspend fun historyFor(ref: SeriesRef): Result<List<WatchEvent>> =
            Result.failure(RuntimeException("rate limited"))
    }

    /** Provider that always succeeds with no watches — stands in for a healthy Tautulli. */
    private class EmptyProvider(override val name: String) : WatchHistoryProvider {
        override suspend fun historyFor(ref: SeriesRef): Result<List<WatchEvent>> =
            Result.success(emptyList())
    }

    private fun sonarrOverMock(): SonarrClient {
        val series = """{"id":1,"title":"Test","tvdbId":999,"seasons":[{"seasonNumber":1,"monitored":true}]}"""
        val episodes = """
            [{"id":10,"seasonNumber":1,"episodeNumber":1,"monitored":true,"hasFile":false,
              "airDateUtc":"2020-01-01T00:00:00Z"}]
        """.trimIndent()
        val http = HttpClient(MockEngine { request ->
            val body = if (request.url.encodedPath.contains("/episode")) episodes else series
            respond(
                ByteReadChannel(body),
                HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }) { install(ContentNegotiation) { json() } }
        return SonarrClient("http://sonarr", "key", http)
    }

    private fun ttlMinutesOf(db: Database, seriesId: Long): Long {
        val row = db.getPriorityCache(seriesId)
        assertNotNull(row, "expected a cached priority row")
        val computed = OffsetDateTime.parse(row.computed_at)
        val expires = OffsetDateTime.parse(row.expires_at)
        return java.time.Duration.between(computed, expires).toMinutes()
    }

    @Test fun a_partial_provider_failure_persists_a_short_ttl() = runTest {
        val db = Database(tempDb.absolutePath)
        val service = PriorityService(
            sonarr = sonarrOverMock(),
            // One healthy, one failing => degraded, not unreachable.
            watchProviders = listOf(EmptyProvider("tautulli"), FailingProvider("trakt")),
            db = db,
            thresholdsSource = FixedThresholds(),
            cacheTtlMinutes = 60,
        )

        service.priorityForSeries(1)

        assertEquals(DEGRADED_PRIORITY_CACHE_TTL_MINUTES, ttlMinutesOf(db, 1))
    }

    @Test fun all_providers_healthy_persists_the_full_ttl() = runTest {
        val db = Database(tempDb.absolutePath)
        db.invalidatePriorityCache(2)
        val service = PriorityService(
            sonarr = sonarrOverMock(),
            watchProviders = listOf(EmptyProvider("tautulli"), EmptyProvider("trakt")),
            db = db,
            thresholdsSource = FixedThresholds(),
            cacheTtlMinutes = 60,
        )

        service.priorityForSeries(2)

        assertTrue(
            ttlMinutesOf(db, 2) > DEGRADED_PRIORITY_CACHE_TTL_MINUTES,
            "healthy providers must keep the configured TTL",
        )
    }
}
