package org.yoshiz.app.prioritarr.backend.priority

import org.yoshiz.app.prioritarr.backend.config.PriorityThresholds
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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
        monitoredSeasons: Int = 1,
        // Default to 1 so tests inherit "there's something to grab"
        // behaviour and only the specific short-circuit tests pass 0.
        // Reflects the common scenario the other tests encode.
        missing: Int = 1,
    ) = SeriesSnapshot(
        seriesId = 1,
        title = "Test",
        tvdbId = 12345,
        monitoredSeasons = monitoredSeasons,
        monitoredEpisodesAired = aired,
        monitoredEpisodesWatched = watched,
        lastWatchedAt = lastWatchDaysAgo?.let { now.minus(Duration.ofDays(it.toLong())) },
        episodeReleaseDate = releaseDaysAgo?.let { now.minus(Duration.ofDays(it.toLong())) },
        previousEpisodeReleaseDate = prevReleaseDaysAgo?.let { now.minus(Duration.ofDays(it.toLong())) },
        monitoredMissingEpisodes = missing,
    )

    // ---------- P1 ----------

    @Test fun p1_caught_up_fresh_episode() {
        assertEquals(1, computePriority(snap(aired = 20, watched = 20, lastWatchDaysAgo = 2, releaseDaysAgo = 1), t, now).priority)
    }

    @Test fun p1_90pct_watched_qualifies() {
        assertEquals(1, computePriority(snap(aired = 10, watched = 9, lastWatchDaysAgo = 2, releaseDaysAgo = 1), t, now).priority)
    }

    @Test fun p1_below_both_gates_does_not_qualify() {
        // Under the OR engagement gate, "failing" P1 now means failing BOTH
        // the pct-min and the absolute-unwatched-count gates. 80% + 10
        // unwatched → neither engagement path opens.
        assertNotEquals(1, computePriority(snap(aired = 50, watched = 40, lastWatchDaysAgo = 2, releaseDaysAgo = 1), t, now).priority)
    }

    @Test fun p1_short_show_count_gate_qualifies() {
        // New behaviour: 1 unwatched on a 12-ep show (92%) obviously P1, but
        // also 3 unwatched on a 12-ep show (75%) should qualify because the
        // absolute-count gate is open — "actively watching" regardless of pct.
        assertEquals(1, computePriority(snap(aired = 12, watched = 9, lastWatchDaysAgo = 2, releaseDaysAgo = 1), t, now).priority)
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

    @Test fun p2_nearly_caught_up_lapsed() {
        // aired=20, watched=17 → 85% (≥ p2_watch_pct_min=0.80). Last watch 30d ago.
        // Old rule (P2 required ≥90%) → dropped to P3. New rule → P2.
        assertEquals(2, computePriority(
            snap(aired = 20, watched = 17, lastWatchDaysAgo = 30, releaseDaysAgo = 30),
            t, now
        ).priority)
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

    @Test fun p3_four_unwatched_high_pct_still_qualifies() {
        // Long show, 4 unwatched out of 44 = 91%. Old rule: count > 3 → P4.
        // New rule: pct ≥ 75% → engagement gate opens → P3.
        assertEquals(3, computePriority(
            snap(aired = 44, watched = 40, lastWatchDaysAgo = 5, releaseDaysAgo = 30, prevReleaseDaysAgo = 37),
            t, now
        ).priority)
    }

    @Test fun p3_falls_to_p4_only_when_both_gates_fail() {
        // aired=44, watched=30 → 68% pct (< 75%) AND 14 unwatched (> 3) →
        // neither engagement gate passes → P4.
        assertEquals(4, computePriority(
            snap(aired = 44, watched = 30, lastWatchDaysAgo = 5, releaseDaysAgo = 30, prevReleaseDaysAgo = 37),
            t, now
        ).priority)
    }

    @Test fun p3_short_show_three_unwatched_qualifies() {
        // 3 unwatched of 12 (= 75%). Under the new rule the count gate
        // opens the engagement band; the user is still actively watching a
        // short show and shouldn't fall to P4 just because 3/12 crosses
        // percentage-wise.
        assertEquals(3, computePriority(
            snap(aired = 12, watched = 9, lastWatchDaysAgo = 5, releaseDaysAgo = 30, prevReleaseDaysAgo = 37),
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

    // ---------- Nothing-to-download short-circuit ----------

    @Test fun nothing_to_download_collapses_to_p5_over_p2() {
        // Frieren-style: fully watched + fully downloaded but lapsed
        // more than 14d. Old rule: P2. New rule: P5 because there's
        // literally nothing Sonarr can grab.
        val r = computePriority(
            snap(aired = 12, watched = 12, lastWatchDaysAgo = 30, releaseDaysAgo = 30, missing = 0),
            t, now
        )
        assertEquals(5, r.priority)
        assertTrue("status=fully_downloaded" in r.reason, "expected fully_downloaded status in reason; got: ${r.reason}")
    }

    @Test fun nothing_to_download_collapses_to_p5_over_p1() {
        // Live-following shape (watched today + fresh release) but
        // nothing missing. The gate fires before P1 so this is P5.
        assertEquals(5, computePriority(
            snap(aired = 10, watched = 10, lastWatchDaysAgo = 1, releaseDaysAgo = 2, missing = 0),
            t, now
        ).priority)
    }

    @Test fun nothing_to_download_toggle_off_keeps_engagement_rules() {
        // With the gate disabled, the series falls through to the
        // normal engagement evaluation. A caught-up show lapsed 30d
        // with missing=0 returns to P2.
        val tOff = t.copy(p5WhenNothingToDownload = false)
        assertEquals(2, computePriority(
            snap(aired = 12, watched = 12, lastWatchDaysAgo = 30, releaseDaysAgo = 30, missing = 0),
            tOff, now
        ).priority)
    }

    // ---------- P3 returning from dormancy ----------

    @Test fun p3_dormant_show_with_new_release() {
        // User finished the show 6 months ago. A new episode just
        // dropped (missing=1, release=3d). Old rule: P5 (lastWatch too
        // old for P1/P2/P3). New rule: P3 "returning from dormancy".
        val r = computePriority(
            snap(aired = 13, watched = 12, lastWatchDaysAgo = 180, releaseDaysAgo = 3, missing = 1),
            t, now
        )
        assertEquals(3, r.priority)
        assertTrue("status=returning" in r.reason, "expected returning status; got: ${r.reason}")
    }

    @Test fun p3_dormant_rescue_window_bounded() {
        // Same setup but the new episode is 90 days old — outside
        // p3DormantReleaseWindowDays (60). Falls through to P5.
        assertEquals(5, computePriority(
            snap(aired = 13, watched = 12, lastWatchDaysAgo = 180, releaseDaysAgo = 90, missing = 1),
            t, now
        ).priority)
    }

    @Test fun p3_dormant_rescue_requires_prior_engagement() {
        // User only watched 5 of 13 (38% watched). Not historically
        // engaged enough (below p2_watch_pct_min). Even with a fresh
        // release + something missing, stays in P4 (backfill).
        assertEquals(4, computePriority(
            snap(aired = 13, watched = 5, lastWatchDaysAgo = 180, releaseDaysAgo = 3, missing = 1),
            t, now
        ).priority)
    }
}
