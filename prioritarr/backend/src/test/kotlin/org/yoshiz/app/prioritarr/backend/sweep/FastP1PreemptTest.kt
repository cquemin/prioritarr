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
import org.yoshiz.app.prioritarr.backend.orchestration.SearchQueueControl
import org.yoshiz.app.prioritarr.backend.orchestration.SonarrCommand
import org.yoshiz.app.prioritarr.backend.priority.PriorityResult
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class FastP1PreemptTest {
    private val now = 1704844800L
    private fun freshDb(): Database {
        val tmp = Files.createTempFile("prio-preempt", ".db"); tmp.toFile().deleteOnExit()
        return Database(tmp.toAbsolutePath().toString())
    }
    private fun rec(seriesId: Long, id: Long, air: String) = buildJsonObject {
        put("seriesId", JsonPrimitive(seriesId)); put("id", JsonPrimitive(id))
        put("airDateUtc", JsonPrimitive(air)); put("seasonNumber", JsonPrimitive(1))
    }
    private class FakeSonarr(private val missing: JsonArray) : SonarrClient(
        "http://fake", "x",
        HttpClient(MockEngine { respond(ByteReadChannel("{}"), headers = headersOf("Content-Type", "application/json")) }) { install(ContentNegotiation) { json() } },
    ) {
        val searched = mutableListOf<List<Long>>()
        override suspend fun getWantedMissing(pageSize: Int): JsonArray = missing
        override suspend fun getQueue(pageSize: Int): JsonArray = JsonArray(emptyList())
        override suspend fun triggerEpisodeSearch(episodeIds: List<Long>): JsonObject { searched += episodeIds; return buildJsonObject {} }
    }

    private fun controlOver(cmds: List<SonarrCommand>, cancelled: MutableList<Long>) =
        SearchQueueControl(getCommands = { cmds }, cancelCommand = { cancelled += it }, threshold = { 3 }, dryRun = { false })

    @Test fun cancels_backfill_before_firing_when_candidates_exist() = runTest {
        val fake = FakeSonarr(buildJsonArray { add(rec(1L, 11L, "2024-01-09T22:00:00Z")) }) // P1 in window
        val cancelled = mutableListOf<Long>()
        val control = controlOver(listOf(SonarrCommand(7, "SeriesSearch", "started", null)), cancelled)
        val fired = runFastP1Sweep(
            sonarr = fake, db = freshDb(),
            priorityForSeriesFn = { PriorityResult(1, "P1", "") },
            releaseDelayMinutes = 60, windowHours = 48, cooldownMinutes = 20,
            maxPerSweep = 10, dryRun = false, nowEpochSeconds = now,
            searchQueueControl = control, cancelBackfillForPriority = true,
        )
        assertEquals(1, fired)
        assertEquals(listOf(7L), cancelled) // backfill cancelled
        assertEquals(listOf(listOf(11L)), fake.searched) // P1 then fired
    }

    @Test fun no_candidates_means_no_cancellation() = runTest {
        val fake = FakeSonarr(buildJsonArray { add(rec(2L, 21L, "2024-01-09T22:00:00Z")) }) // P3 -> no P1 candidate
        val cancelled = mutableListOf<Long>()
        val control = controlOver(listOf(SonarrCommand(7, "SeriesSearch", "started", null)), cancelled)
        val fired = runFastP1Sweep(
            sonarr = fake, db = freshDb(),
            priorityForSeriesFn = { PriorityResult(3, "P3", "") },
            releaseDelayMinutes = 60, windowHours = 48, cooldownMinutes = 20,
            maxPerSweep = 10, dryRun = false, nowEpochSeconds = now,
            searchQueueControl = control, cancelBackfillForPriority = true,
        )
        assertEquals(0, fired)
        assertEquals(emptyList(), cancelled)
    }
}
