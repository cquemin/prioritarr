package org.yoshiz.app.prioritarr.backend.webhooks

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
import org.yoshiz.app.prioritarr.backend.database.Database

class OnGrabFollowupTest {

    private fun freshDb(): Database {
        val tmp = Files.createTempFile("prio-followup", ".db")
        tmp.toFile().deleteOnExit()
        return Database(tmp.toAbsolutePath().toString())
    }

    /**
     * Minimal SonarrClient fake — mirrors the FakeSonarr in
     * P1P2EpisodeSearchTest.kt and SweepIntegrationTest.kt. Construct
     * a no-op MockEngine HttpClient since SonarrClient's real ctor
     * takes one, but only the overridden methods are exercised.
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
        override suspend fun getWantedMissing(pageSize: Int): JsonArray = missing
        override suspend fun getQueue(pageSize: Int): JsonArray = queue
        override suspend fun triggerEpisodeSearch(episodeIds: List<Long>): JsonObject {
            episodeSearches += episodeIds; return buildJsonObject {}
        }
    }

    private fun rec(seriesId: Long, episodeId: Long, airDate: String): JsonObject =
        buildJsonObject {
            put("seriesId", JsonPrimitive(seriesId))
            put("id", JsonPrimitive(episodeId))
            put("airDateUtc", JsonPrimitive(airDate))
            put("seasonNumber", JsonPrimitive(1))
        }

    @Test fun follow_up_fires_for_p1_grab_with_next_2_episodes() = runTest {
        val missing = buildJsonArray {
            // S04E07 and S04E08 are next; S04E06 was just grabbed
            add(rec(7L, 707L, "2024-04-07"))
            add(rec(7L, 708L, "2024-04-08"))
            add(rec(7L, 709L, "2024-04-09"))   // 3rd, dropped by cap=2
        }
        val sonarr = FakeSonarr(missing)
        val db = freshDb()
        val event = OnGrabEvent(
            seriesId = 7L, seriesTitle = "Slime", tvdbId = 0L,
            episodeIds = listOf(706L), downloadClient = "sab", downloadId = "x", airDate = null,
        )

        runOnGrabFollowup(
            event = event, priority = 1, sonarr = sonarr, db = db,
            followupCap = 2, cooldownSeconds = 1800L, nowEpochSeconds = 1_700_000_000L,
        )

        assertEquals(listOf(listOf(707L, 708L)), sonarr.episodeSearches)
        assertEquals(setOf(707L, 708L), db.listP1P2AttemptedSince(0L).toSet())
    }

    @Test fun follow_up_does_not_fire_for_p3() = runTest {
        val sonarr = FakeSonarr(buildJsonArray { add(rec(7L, 707L, "2024-04-07")) })
        val db = freshDb()
        runOnGrabFollowup(
            event = OnGrabEvent(7L, "x", 0L, listOf(706L), "sab", "x", null),
            priority = 3, sonarr = sonarr, db = db,
            followupCap = 2, cooldownSeconds = 1800L, nowEpochSeconds = 1L,
        )
        assertTrue(sonarr.episodeSearches.isEmpty())
    }

    @Test fun follow_up_excludes_queued_and_cooldown_and_just_grabbed() = runTest {
        val missing = buildJsonArray {
            add(rec(7L, 707L, "2024-04-07"))     // in queue → skip
            add(rec(7L, 708L, "2024-04-08"))     // in cooldown → skip
            add(rec(7L, 709L, "2024-04-09"))     // OK
            add(rec(7L, 710L, "2024-04-10"))     // OK
        }
        val queue = buildJsonArray {
            add(buildJsonObject {
                put("episodeId", JsonPrimitive(707L))
                put("seriesId", JsonPrimitive(7L))
            })
        }
        val sonarr = FakeSonarr(missing, queue)
        val db = freshDb()
        val now = 1_700_000_000L
        db.upsertP1P2Attempt(708L, now - 60)     // recent, inside cooldown

        runOnGrabFollowup(
            event = OnGrabEvent(7L, "x", 0L, listOf(706L), "sab", "x", null),
            priority = 2, sonarr = sonarr, db = db,
            followupCap = 2, cooldownSeconds = 1800L, nowEpochSeconds = now,
        )
        assertEquals(listOf(listOf(709L, 710L)), sonarr.episodeSearches)
    }

    @Test fun follow_up_no_op_when_all_candidates_excluded() = runTest {
        val missing = buildJsonArray {
            add(rec(7L, 707L, "2024-04-07"))     // queued
            add(rec(7L, 708L, "2024-04-08"))     // cooldown
        }
        val queue = buildJsonArray {
            add(buildJsonObject {
                put("episodeId", JsonPrimitive(707L))
                put("seriesId", JsonPrimitive(7L))
            })
        }
        val sonarr = FakeSonarr(missing, queue)
        val db = freshDb()
        val now = 1_700_000_000L
        db.upsertP1P2Attempt(708L, now - 60)

        runOnGrabFollowup(
            event = OnGrabEvent(7L, "x", 0L, listOf(706L), "sab", "x", null),
            priority = 1, sonarr = sonarr, db = db,
            followupCap = 2, cooldownSeconds = 1800L, nowEpochSeconds = now,
        )
        assertTrue(sonarr.episodeSearches.isEmpty(), "no Sonarr call when all candidates excluded")
        // Cooldown is unchanged (708L still there from setup; no NEW rows for 706L/707L)
        assertEquals(setOf(708L), db.listP1P2AttemptedSince(0L).toSet())
    }

    @Test fun follow_up_no_op_when_cap_is_zero() = runTest {
        val sonarr = FakeSonarr(buildJsonArray { add(rec(7L, 707L, "2024-04-07")) })
        val db = freshDb()
        runOnGrabFollowup(
            event = OnGrabEvent(7L, "x", 0L, listOf(706L), "sab", "x", null),
            priority = 1, sonarr = sonarr, db = db,
            followupCap = 0, cooldownSeconds = 1800L, nowEpochSeconds = 1L,
        )
        assertTrue(sonarr.episodeSearches.isEmpty())
    }

    @Test fun follow_up_filters_other_series() = runTest {
        val missing = buildJsonArray {
            add(rec(7L, 707L, "2024-04-07"))     // our series
            add(rec(9L, 909L, "2024-04-07"))     // different series
        }
        val sonarr = FakeSonarr(missing)
        val db = freshDb()
        runOnGrabFollowup(
            event = OnGrabEvent(7L, "x", 0L, listOf(706L), "sab", "x", null),
            priority = 1, sonarr = sonarr, db = db,
            followupCap = 2, cooldownSeconds = 1800L, nowEpochSeconds = 1L,
        )
        assertEquals(listOf(listOf(707L)), sonarr.episodeSearches)
    }
}
