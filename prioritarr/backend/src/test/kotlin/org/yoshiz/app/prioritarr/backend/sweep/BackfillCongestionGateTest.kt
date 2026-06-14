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
import org.yoshiz.app.prioritarr.backend.config.BandwidthSettings
import org.yoshiz.app.prioritarr.backend.config.P5RatchetConfig
import org.yoshiz.app.prioritarr.backend.database.Database
import org.yoshiz.app.prioritarr.backend.priority.PriorityResult
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class BackfillCongestionGateTest {
    private fun freshDb(): Database {
        val tmp = Files.createTempFile("prio-gate", ".db"); tmp.toFile().deleteOnExit()
        return Database(tmp.toAbsolutePath().toString())
    }
    private fun rec(seriesId: Long, id: Long) = buildJsonObject {
        put("seriesId", JsonPrimitive(seriesId)); put("id", JsonPrimitive(id))
        put("airDateUtc", JsonPrimitive("2024-01-01T00:00:00Z")); put("seasonNumber", JsonPrimitive(1))
    }
    private class FakeSonarr(private val missing: JsonArray) : SonarrClient(
        "http://fake", "x",
        HttpClient(MockEngine { respond(ByteReadChannel("{}"), headers = headersOf("Content-Type", "application/json")) }) { install(ContentNegotiation) { json() } },
    ) {
        val seriesSearched = mutableListOf<Long>()
        override suspend fun getWantedMissing(pageSize: Int): JsonArray = missing
        override suspend fun getQueue(pageSize: Int): JsonArray = JsonArray(emptyList())
        override suspend fun triggerSeriesSearch(seriesId: Long): JsonObject { seriesSearched += seriesId; return buildJsonObject {} }
    }

    @Test fun congested_skips_p3p4_series_searches() = runTest {
        val fake = FakeSonarr(buildJsonArray { add(rec(2L, 21L)) })
        runBackfillSweep(
            sonarr = fake, db = freshDb(),
            p5Ratchet = P5RatchetConfig(), bandwidth = BandwidthSettings(), telemetry = null,
            maxSearches = 10, delaySeconds = 0, dryRun = false,
            p1p2MaxPerSweep = 20, p1p2CooldownMinutes = 30,
            lowPriorityCongested = true,
            priorityForSeriesFn = { PriorityResult(3, "P3", "") },
        )
        assertEquals(emptyList(), fake.seriesSearched)
    }

    @Test fun uncongested_runs_p3p4_series_searches() = runTest {
        val fake = FakeSonarr(buildJsonArray { add(rec(2L, 21L)) })
        runBackfillSweep(
            sonarr = fake, db = freshDb(),
            p5Ratchet = P5RatchetConfig(), bandwidth = BandwidthSettings(), telemetry = null,
            maxSearches = 10, delaySeconds = 0, dryRun = false,
            p1p2MaxPerSweep = 20, p1p2CooldownMinutes = 30,
            lowPriorityCongested = false,
            priorityForSeriesFn = { PriorityResult(3, "P3", "") },
        )
        assertEquals(listOf(2L), fake.seriesSearched)
    }
}
