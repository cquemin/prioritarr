package org.yoshiz.app.prioritarr.backend.priority

import org.yoshiz.app.prioritarr.backend.config.PriorityThresholds
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Translation of prioritarr/tests/test_priority.py. Every original
 * test has a Kotlin counterpart; if either suite fails, the priority
 * logic has drifted between backends.
 */
class ComputeTest {

    private val t = PriorityThresholds()
    private val now: Instant = Instant.parse("2026-04-18T12:00:00Z")

    private fun snap(
        aired: Int = 20,
        watched: Int = 20,
        lastWatchDaysAgo: Int? = 2,
        releaseDaysAgo: Int? = 1,
        prevReleaseDaysAgo: Int? = 8,
    ) = SeriesSnapshot(
        seriesId = 1,
        title = "Test",
        tvdbId = 12345,
        monitoredEpisodesAired = aired,
        monitoredEpisodesWatched = watched,
        lastWatchedAt = lastWatchDaysAgo?.let { now.minus(Duration.ofDays(it.toLong())) },
        episodeReleaseDate = releaseDaysAgo?.let { now.minus(Duration.ofDays(it.toLong())) },
        previousEpisodeReleaseDate = prevReleaseDaysAgo?.let { now.minus(Duration.ofDays(it.toLong())) },
    )

    // ---------- P1 ----------

    @Test fun p1_caught_up_fresh_episode() {
        assertEquals(1, computePriority(snap(aired = 20, watched = 20, lastWatchDaysAgo = 2, releaseDaysAgo = 1), t, now).priority)
    }

    @Test fun p1_90pct_watched_qualifies() {
        assertEquals(1, computePriority(snap(aired = 10, watched = 9, lastWatchDaysAgo = 2, releaseDaysAgo = 1), t, now).priority)
    }

    @Test fun p1_89pct_does_not_qualify() {
        assertNotEquals(1, computePriority(snap(aired = 9, watched = 8, lastWatchDaysAgo = 2, releaseDaysAgo = 1), t, now).priority)
    }

    @Test fun p1_post_hiatus_within_28_days() {
        assertEquals(1, computePriority(
            snap(aired = 20, watched = 20, lastWatchDaysAgo = 2, releaseDaysAgo = 20, prevReleaseDaysAgo = 35),
            t, now
        ).priority)
    }

    @Test fun p1_post_hiatus_beyond_28_days() {
        assertNotEquals(1, computePriority(
            snap(aired = 20, watched = 20, lastWatchDaysAgo = 2, releaseDaysAgo = 29, prevReleaseDaysAgo = 44),
            t, now
        ).priority)
    }

    @Test fun p1_last_watch_15_days_ago() {
        assertNotEquals(1, computePriority(
            snap(aired = 20, watched = 20, lastWatchDaysAgo = 15, releaseDaysAgo = 1),
            t, now
        ).priority)
    }

    // ---------- P2 ----------

    @Test fun p2_caught_up_lapsed_30_days() {
        assertEquals(2, computePriority(snap(aired = 20, watched = 20, lastWatchDaysAgo = 30, releaseDaysAgo = 30), t, now).priority)
    }

    @Test fun p2_exactly_60_days_boundary() {
        assertEquals(2, computePriority(snap(aired = 20, watched = 20, lastWatchDaysAgo = 60, releaseDaysAgo = 60), t, now).priority)
    }

    @Test fun p2_61_days_falls_through() {
        assertNotEquals(2, computePriority(snap(aired = 20, watched = 20, lastWatchDaysAgo = 61, releaseDaysAgo = 61), t, now).priority)
    }

    // ---------- P3 ----------

    @Test fun p3_three_unwatched_recent() {
        assertEquals(3, computePriority(
            snap(aired = 40, watched = 37, lastWatchDaysAgo = 5, releaseDaysAgo = 30, prevReleaseDaysAgo = 37),
            t, now
        ).priority)
    }

    @Test fun p3_four_unwatched_falls_to_p4() {
        assertEquals(4, computePriority(
            snap(aired = 44, watched = 40, lastWatchDaysAgo = 5, releaseDaysAgo = 30, prevReleaseDaysAgo = 37),
            t, now
        ).priority)
    }

    // ---------- P4 ----------

    @Test fun p4_many_unwatched_some_watched() {
        assertEquals(4, computePriority(snap(aired = 20, watched = 10, lastWatchDaysAgo = 5, releaseDaysAgo = 5), t, now).priority)
    }

    @Test fun p4_one_watched_rest_unwatched() {
        assertEquals(4, computePriority(snap(aired = 20, watched = 1, lastWatchDaysAgo = 5, releaseDaysAgo = 5), t, now).priority)
    }

    // ---------- P5 ----------

    @Test fun p5_zero_watched() {
        assertEquals(5, computePriority(snap(aired = 20, watched = 0, lastWatchDaysAgo = null, releaseDaysAgo = 5), t, now).priority)
    }

    @Test fun p5_long_dormant_caught_up() {
        assertEquals(5, computePriority(snap(aired = 20, watched = 18, lastWatchDaysAgo = 90, releaseDaysAgo = 90), t, now).priority)
    }

    @Test fun p5_zero_aired() {
        assertEquals(5, computePriority(snap(aired = 0, watched = 0, lastWatchDaysAgo = null, releaseDaysAgo = null), t, now).priority)
    }

    // ---------- Edge cases ----------

    @Test fun edge_no_release_date_not_p1() {
        assertNotEquals(1, computePriority(snap(aired = 20, watched = 20, lastWatchDaysAgo = 2, releaseDaysAgo = null), t, now).priority)
    }

    @Test fun edge_no_previous_release_hiatus_false() {
        assertNotEquals(1, computePriority(
            snap(aired = 20, watched = 20, lastWatchDaysAgo = 2, releaseDaysAgo = 20, prevReleaseDaysAgo = null),
            t, now
        ).priority)
    }
}
