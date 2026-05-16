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
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class P1P2EpisodeSearchPlannerTest {

    private fun rec(seriesId: Long, episodeId: Long, airDate: String, season: Int = 1): JsonObject =
        buildJsonObject {
            put("seriesId", JsonPrimitive(seriesId))
            put("id", JsonPrimitive(episodeId))
            put("airDateUtc", JsonPrimitive(airDate))
            put("seasonNumber", JsonPrimitive(season))
        }

    private fun records(vararg objs: JsonObject): JsonArray = buildJsonArray { objs.forEach { add(it) } }

    @Test fun groups_by_series_takes_5_oldest() {
        val recs = records(
            rec(1L, 11L, "2024-01-05"),
            rec(1L, 12L, "2024-01-03"),
            rec(1L, 13L, "2024-01-01"),
            rec(1L, 14L, "2024-01-04"),
            rec(1L, 15L, "2024-01-02"),
            rec(1L, 16L, "2024-01-06"),     // 6th — should be dropped
        )
        val out = buildP1P2Candidates(
            records = recs,
            priorityBySeriesId = mapOf(1L to 1),
            queuedEpisodeIds = emptySet(),
            cooldownEpisodeIds = emptySet(),
            perSeriesCap = 5,
        )
        assertEquals(1, out.size)
        val c = out.single()
        assertEquals(1L, c.seriesId)
        assertEquals(1, c.priority)
        assertEquals(listOf(13L, 15L, 12L, 14L, 11L), c.episodes.map { it.episodeId })
    }

    @Test fun outer_sort_is_priority_then_oldest() {
        val recs = records(
            rec(1L, 11L, "2024-01-01"),     // P2 oldest later
            rec(1L, 12L, "2024-02-01"),
            rec(2L, 21L, "2023-12-01"),     // P1 oldest earlier — wins
            rec(2L, 22L, "2024-03-01"),
            rec(3L, 31L, "2020-01-01"),     // P2 oldest very early — beats P2 series 1
        )
        val out = buildP1P2Candidates(
            records = recs,
            priorityBySeriesId = mapOf(1L to 2, 2L to 1, 3L to 2),
            queuedEpisodeIds = emptySet(),
            cooldownEpisodeIds = emptySet(),
            perSeriesCap = 5,
        )
        assertEquals(listOf(2L, 3L, 1L), out.map { it.seriesId })
    }

    @Test fun queue_skip_drops_matching_episode_ids() {
        val recs = records(
            rec(1L, 11L, "2024-01-01"),
            rec(1L, 12L, "2024-01-02"),
            rec(1L, 13L, "2024-01-03"),
        )
        val out = buildP1P2Candidates(
            records = recs,
            priorityBySeriesId = mapOf(1L to 1),
            queuedEpisodeIds = setOf(12L),
            cooldownEpisodeIds = emptySet(),
            perSeriesCap = 5,
        )
        assertEquals(listOf(11L, 13L), out.single().episodes.map { it.episodeId })
    }

    @Test fun cooldown_skip_drops_matching_episode_ids() {
        val recs = records(
            rec(1L, 11L, "2024-01-01"),
            rec(1L, 12L, "2024-01-02"),
        )
        val out = buildP1P2Candidates(
            records = recs,
            priorityBySeriesId = mapOf(1L to 2),
            queuedEpisodeIds = emptySet(),
            cooldownEpisodeIds = setOf(11L),
            perSeriesCap = 5,
        )
        assertEquals(listOf(12L), out.single().episodes.map { it.episodeId })
    }

    @Test fun series_with_all_episodes_filtered_drops_from_output() {
        val recs = records(
            rec(1L, 11L, "2024-01-01"),
            rec(2L, 21L, "2024-01-01"),
        )
        val out = buildP1P2Candidates(
            records = recs,
            priorityBySeriesId = mapOf(1L to 1, 2L to 2),
            queuedEpisodeIds = setOf(11L),
            cooldownEpisodeIds = setOf(21L),
            perSeriesCap = 5,
        )
        assertTrue(out.isEmpty())
    }

    @Test fun non_p1p2_series_are_ignored() {
        val recs = records(
            rec(1L, 11L, "2024-01-01"),       // P3 — ignore
            rec(2L, 21L, "2024-01-01"),       // P2 — keep
            rec(3L, 31L, "2024-01-01"),       // P5 — ignore
        )
        val out = buildP1P2Candidates(
            records = recs,
            priorityBySeriesId = mapOf(1L to 3, 2L to 2, 3L to 5),
            queuedEpisodeIds = emptySet(),
            cooldownEpisodeIds = emptySet(),
            perSeriesCap = 5,
        )
        assertEquals(listOf(2L), out.map { it.seriesId })
    }

    @Test fun missing_priority_treats_series_as_non_p1p2() {
        val recs = records(rec(1L, 11L, "2024-01-01"))
        val out = buildP1P2Candidates(
            records = recs,
            priorityBySeriesId = emptyMap(),  // priority unknown
            queuedEpisodeIds = emptySet(),
            cooldownEpisodeIds = emptySet(),
            perSeriesCap = 5,
        )
        assertTrue(out.isEmpty())
    }
}

class P1P2EpisodeSearchRunnerTest {

    private fun freshDb(): Database {
        val tmp = Files.createTempFile("prio-p1p2-runner", ".db")
        tmp.toFile().deleteOnExit()
        return Database(tmp.toAbsolutePath().toString())
    }

    /**
     * Minimal SonarrClient fake. Overrides only triggerEpisodeSearch;
     * passes a no-op MockEngine so the parent's http field is valid but
     * never actually called (we override the only method under test).
     */
    private class FakeSonarr(
        private val throwOnCall: Long? = null,
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
        val calls = mutableListOf<List<Long>>()
        override suspend fun triggerEpisodeSearch(episodeIds: List<Long>): JsonObject {
            calls += episodeIds
            if (throwOnCall != null && episodeIds.contains(throwOnCall)) error("fake failure")
            return buildJsonObject {}
        }
    }

    private fun candidate(seriesId: Long, vararg episodeIds: Long, priority: Int = 1) = P1P2Candidate(
        seriesId = seriesId,
        priority = priority,
        oldestAirDate = "2024-01-01",
        episodes = episodeIds.map { P1P2Episode(it, "2024-01-01", 1) },
    )

    @Test fun fires_episode_search_in_order_records_cooldown() = runTest {
        val db = freshDb()
        val fake = FakeSonarr()
        val now = 1_700_000_000L

        val fired = runP1P2EpisodePass(
            candidates = listOf(
                candidate(seriesId = 1L, 11L, 12L, 13L),
                candidate(seriesId = 2L, 21L),
            ),
            sonarr = fake,
            db = db,
            budget = 10,
            delaySeconds = 0,
            dryRun = false,
            nowEpochSeconds = now,
        )
        assertEquals(2, fired)
        assertEquals(listOf(listOf(11L, 12L, 13L), listOf(21L)), fake.calls)
        // Cooldown rows written for all four episodes:
        val cooldownIds = db.listP1P2AttemptedSince(0L).toSet()
        assertEquals(setOf(11L, 12L, 13L, 21L), cooldownIds)
    }

    @Test fun respects_budget() = runTest {
        val db = freshDb()
        val fake = FakeSonarr()
        val fired = runP1P2EpisodePass(
            candidates = listOf(
                candidate(1L, 11L),
                candidate(2L, 21L),
                candidate(3L, 31L),
            ),
            sonarr = fake, db = db,
            budget = 2, delaySeconds = 0, dryRun = false, nowEpochSeconds = 1L,
        )
        assertEquals(2, fired)
        assertEquals(2, fake.calls.size)
    }

    @Test fun zero_budget_processes_nothing() = runTest {
        val db = freshDb()
        val fake = FakeSonarr()
        val fired = runP1P2EpisodePass(
            candidates = listOf(candidate(1L, 11L), candidate(2L, 21L)),
            sonarr = fake, db = db,
            budget = 0, delaySeconds = 0, dryRun = false, nowEpochSeconds = 1L,
        )
        assertEquals(0, fired)
        assertTrue(fake.calls.isEmpty())
        assertTrue(db.listP1P2AttemptedSince(0L).isEmpty())
    }

    @Test fun dry_run_does_not_call_sonarr_or_record_cooldown() = runTest {
        val db = freshDb()
        val fake = FakeSonarr()
        val fired = runP1P2EpisodePass(
            candidates = listOf(candidate(1L, 11L, 12L)),
            sonarr = fake, db = db,
            budget = 10, delaySeconds = 0, dryRun = true, nowEpochSeconds = 1L,
        )
        assertEquals(1, fired)            // counts as "fired" for telemetry
        assertTrue(fake.calls.isEmpty())
        assertTrue(db.listP1P2AttemptedSince(0L).isEmpty())
    }

    @Test fun failure_breaks_and_does_not_record_cooldown_for_failed_call() = runTest {
        val db = freshDb()
        val fake = FakeSonarr(throwOnCall = 21L)
        val fired = runP1P2EpisodePass(
            candidates = listOf(
                candidate(1L, 11L),       // succeeds
                candidate(2L, 21L),       // fails
                candidate(3L, 31L),       // never attempted
            ),
            sonarr = fake, db = db,
            budget = 10, delaySeconds = 0, dryRun = false, nowEpochSeconds = 1L,
        )
        assertEquals(1, fired)
        // 11L recorded; 21L NOT recorded (so we retry next sweep); 31L never attempted
        assertEquals(listOf(11L), db.listP1P2AttemptedSince(0L))
    }
}
