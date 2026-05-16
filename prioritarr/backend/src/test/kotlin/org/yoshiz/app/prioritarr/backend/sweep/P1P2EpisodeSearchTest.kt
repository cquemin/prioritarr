package org.yoshiz.app.prioritarr.backend.sweep

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
