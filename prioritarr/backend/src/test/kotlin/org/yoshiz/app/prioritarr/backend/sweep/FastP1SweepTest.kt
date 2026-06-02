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
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.database.Database
import org.yoshiz.app.prioritarr.backend.priority.PriorityResult
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class FastP1SweepTest {
    private val now = 1704844800L // 2024-01-10T00:00:00Z

    private fun freshDb(): Database {
        val tmp = Files.createTempFile("prio-fastp1", ".db")
        tmp.toFile().deleteOnExit()
        return Database(tmp.toAbsolutePath().toString())
    }

    private fun rec(seriesId: Long, id: Long, air: String): JsonObject = buildJsonObject {
        put("seriesId", JsonPrimitive(seriesId)); put("id", JsonPrimitive(id))
        put("airDateUtc", JsonPrimitive(air)); put("seasonNumber", JsonPrimitive(1))
    }

    private class FakeSonarr(
        private val missing: JsonArray,
        private val queue: JsonArray = JsonArray(emptyList()),
    ) : SonarrClient(
        baseUrl = "http://fake", apiKey = "x",
        http = HttpClient(MockEngine { respond(ByteReadChannel("{}"), headers = headersOf("Content-Type", "application/json")) }) {
            install(ContentNegotiation) { json() }
        },
    ) {
        val searched = mutableListOf<List<Long>>()
        override suspend fun getWantedMissing(pageSize: Int): JsonArray = missing
        override suspend fun getQueue(pageSize: Int): JsonArray = queue
        override suspend fun triggerEpisodeSearch(episodeIds: List<Long>): JsonObject {
            searched += episodeIds; return buildJsonObject {}
        }
    }

    @Test fun searches_only_p1_episodes_in_window_and_records_fast_band() = runTest {
        val missing = buildJsonArray {
            add(rec(1L, 11L, "2024-01-09T22:00:00Z")) // P1, 2h old -> search
            add(rec(2L, 21L, "2024-01-09T22:00:00Z")) // P3 -> skip (not P1)
            add(rec(1L, 12L, "2024-01-09T23:45:00Z")) // P1 but 15min old -> skip (too fresh)
        }
        val db = freshDb()
        val fake = FakeSonarr(missing)
        val fired = runFastP1Sweep(
            sonarr = fake, db = db,
            priorityForSeriesFn = { sid -> PriorityResult(if (sid == 1L) 1 else 3, "P", "") },
            releaseDelayMinutes = 60, windowHours = 48, cooldownMinutes = 20,
            maxPerSweep = 10, dryRun = false, nowEpochSeconds = now,
        )
        assertEquals(1, fired)
        assertEquals(listOf(listOf(11L)), fake.searched)
        assertEquals(listOf(11L), db.listPriorityAttemptedSince(Database.BAND_P1_FAST, 0L))
    }

    @Test fun cooldown_blocks_a_second_immediate_sweep() = runTest {
        val missing = buildJsonArray { add(rec(1L, 11L, "2024-01-09T22:00:00Z")) }
        val db = freshDb()
        val fake = FakeSonarr(missing)
        val run = suspend {
            runFastP1Sweep(
                sonarr = fake, db = db,
                priorityForSeriesFn = { PriorityResult(1, "P1", "") },
                releaseDelayMinutes = 60, windowHours = 48, cooldownMinutes = 20,
                maxPerSweep = 10, dryRun = false, nowEpochSeconds = now,
            )
        }
        assertEquals(1, run())
        assertEquals(0, run()) // within 20-min cooldown -> nothing fired
        assertEquals(1, fake.searched.size)
    }

    @Test fun queued_episode_is_skipped() = runTest {
        val missing = buildJsonArray { add(rec(1L, 11L, "2024-01-09T22:00:00Z")) }
        val queue = buildJsonArray { add(buildJsonObject { put("episodeId", JsonPrimitive(11)) }) }
        val db = freshDb()
        val fake = FakeSonarr(missing, queue)
        val fired = runFastP1Sweep(
            sonarr = fake, db = db,
            priorityForSeriesFn = { PriorityResult(1, "P1", "") },
            releaseDelayMinutes = 60, windowHours = 48, cooldownMinutes = 20,
            maxPerSweep = 10, dryRun = false, nowEpochSeconds = now,
        )
        assertEquals(0, fired)
    }
}
