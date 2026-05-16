package org.yoshiz.app.prioritarr.backend.sweep

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.config.BandwidthSettings
import org.yoshiz.app.prioritarr.backend.config.P5RatchetConfig
import org.yoshiz.app.prioritarr.backend.database.Database
import org.yoshiz.app.prioritarr.backend.priority.PriorityResult

class SweepIntegrationTest {

    private fun freshDb(): Database {
        val tmp = Files.createTempFile("prio-sweep-integ", ".db")
        tmp.toFile().deleteOnExit()
        return Database(tmp.toAbsolutePath().toString())
    }

    /**
     * Minimal SonarrClient fake. Mirrors the FakeSonarr from
     * P1P2EpisodeSearchTest: passes a no-op MockEngine so the parent's
     * http field is valid but never actually called.
     */
    private class FakeSonarr(
        private val missing: JsonArray,
        private val queue: JsonArray = JsonArray(emptyList()),
    ) : SonarrClient(
        baseUrl = "http://fake",
        apiKey = "x",
        http = HttpClient(MockEngine { _ ->
            respond(
                content = ByteReadChannel("{}"),
                headers = headersOf("Content-Type", "application/json"),
            )
        }) {
            install(ContentNegotiation) { json() }
        },
    ) {
        val episodeSearches = mutableListOf<List<Long>>()
        val seriesSearches = mutableListOf<Long>()

        override suspend fun getWantedMissing(pageSize: Int): JsonArray = missing
        override suspend fun getQueue(pageSize: Int): JsonArray = queue

        override suspend fun triggerEpisodeSearch(episodeIds: List<Long>): JsonObject {
            episodeSearches += episodeIds
            return buildJsonObject {}
        }

        override suspend fun triggerSeriesSearch(seriesId: Long): JsonObject {
            seriesSearches += seriesId
            return buildJsonObject {}
        }
    }

    private fun rec(seriesId: Long, episodeId: Long, airDate: String, season: Int = 1): JsonObject =
        buildJsonObject {
            put("seriesId", JsonPrimitive(seriesId))
            put("id", JsonPrimitive(episodeId))
            put("airDateUtc", JsonPrimitive(airDate))
            put("seasonNumber", JsonPrimitive(season))
        }

    /** Priority lookup lambda — avoids constructing the invasive PriorityService in tests. */
    private fun priorityFn(byId: Map<Long, Int>): suspend (Long) -> PriorityResult = { seriesId ->
        val p = byId[seriesId] ?: 5
        PriorityResult(priority = p, label = "P$p", reason = "")
    }

    @Test fun three_pass_orchestration_fires_correct_commands() = runTest {
        val records = buildJsonArray {
            // P1 series 1 — should be Pass A1 (EpisodeSearch)
            add(rec(1L, 11L, "2024-01-01"))
            add(rec(1L, 12L, "2024-01-02"))
            // P2 series 2 — Pass A1
            add(rec(2L, 21L, "2024-02-01"))
            // P3 series 3 — Pass A2 (SeriesSearch)
            add(rec(3L, 31L, "2024-01-01"))
            // P4 series 4 — Pass A2
            add(rec(4L, 41L, "2024-01-01"))
            // P5 series 5 — Pass B (ratchet disabled → SeriesSearch fallback)
            add(rec(5L, 51L, "2024-01-01", season = 1))
        }
        val sonarr = FakeSonarr(records)
        val db = freshDb()

        runBackfillSweep(
            sonarr = sonarr,
            db = db,
            p5Ratchet = P5RatchetConfig(enabled = false),
            bandwidth = BandwidthSettings(),
            telemetry = null,
            maxSearches = 10,
            delaySeconds = 0,
            dryRun = false,
            p1p2MaxPerSweep = 20,
            p1p2CooldownMinutes = 30,
            priorityForSeriesFn = priorityFn(mapOf(1L to 1, 2L to 2, 3L to 3, 4L to 4, 5L to 5)),
        )

        // Pass A1: one EpisodeSearch per P1/P2 series in priority order
        assertEquals(2, sonarr.episodeSearches.size)
        assertEquals(listOf(11L, 12L), sonarr.episodeSearches[0])    // P1 series 1
        assertEquals(listOf(21L), sonarr.episodeSearches[1])         // P2 series 2

        // Pass A2: SeriesSearch for P3 + P4. Pass B: ratchet inactive → falls
        // back to SeriesSearch for the P5 series 5 (in oldest-air-date order
        // after the A2 entries).
        assertEquals(listOf(3L, 4L, 5L), sonarr.seriesSearches)
        assertTrue(3L !in sonarr.episodeSearches.flatten())
        assertTrue(4L !in sonarr.episodeSearches.flatten())

        // Cooldown rows written for P1/P2 episodes
        assertEquals(setOf(11L, 12L, 21L), db.listP1P2AttemptedSince(0L).toSet())
    }

    @Test fun queue_skip_blocks_p1p2_episode_search() = runTest {
        val records = buildJsonArray { add(rec(1L, 11L, "2024-01-01")) }
        // Queue entry uses the episodeId shape (flat field)
        val queue = buildJsonArray {
            add(buildJsonObject {
                put("episodeId", JsonPrimitive(11L))
                put("seriesId", JsonPrimitive(1L))
            })
        }
        val sonarr = FakeSonarr(records, queue)
        val db = freshDb()

        runBackfillSweep(
            sonarr = sonarr,
            db = db,
            p5Ratchet = P5RatchetConfig(enabled = false),
            bandwidth = BandwidthSettings(),
            telemetry = null,
            maxSearches = 10,
            delaySeconds = 0,
            dryRun = false,
            p1p2MaxPerSweep = 20,
            p1p2CooldownMinutes = 30,
            priorityForSeriesFn = priorityFn(mapOf(1L to 1)),
        )
        assertTrue(sonarr.episodeSearches.isEmpty(), "queued episode should be skipped")
        assertTrue(db.listP1P2AttemptedSince(0L).isEmpty())
    }

    @Test fun cooldown_blocks_p1p2_episode_search() = runTest {
        val records = buildJsonArray { add(rec(1L, 11L, "2024-01-01")) }
        val sonarr = FakeSonarr(records)
        val db = freshDb()
        val now = System.currentTimeMillis() / 1000L
        db.upsertP1P2Attempt(11L, now - 60)   // 1 minute ago, well inside 30-min cooldown

        runBackfillSweep(
            sonarr = sonarr,
            db = db,
            p5Ratchet = P5RatchetConfig(enabled = false),
            bandwidth = BandwidthSettings(),
            telemetry = null,
            maxSearches = 10,
            delaySeconds = 0,
            dryRun = false,
            p1p2MaxPerSweep = 20,
            p1p2CooldownMinutes = 30,
            priorityForSeriesFn = priorityFn(mapOf(1L to 1)),
        )
        assertTrue(sonarr.episodeSearches.isEmpty())
    }
}
