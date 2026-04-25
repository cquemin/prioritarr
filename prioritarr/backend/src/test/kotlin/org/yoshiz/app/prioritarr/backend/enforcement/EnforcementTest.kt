package org.yoshiz.app.prioritarr.backend.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pause-band enforcement rules. The regression that drove this file:
 * a P1 torrent with `pausedByUs=true` but whose actual qBit state
 * had drifted off `pausedDL` (auto-resume on restart / user click)
 * never got its `pausedByUs` flag cleared by the reconciler.
 */
class EnforcementTest {

    private fun dl(priority: Int, state: String = "downloading", pausedByUs: Boolean = false) =
        QBitDownloadView(hash = "h-p$priority-$state", priority = priority, state = state, pausedByUs = pausedByUs)

    // ---------- pause-band core ----------

    @Test fun p1_active_pauses_p4_and_p5() {
        val actions = computeQBitPauseActions(listOf(
            dl(priority = 1), dl(priority = 4), dl(priority = 5),
        ))
        val byHash = actions.groupBy { it.hash }.mapValues { it.value.map { a -> a.action } }
        // P1 gets top_priority; P4 + P5 paused.
        assertTrue("pause" in byHash["h-p4-downloading"].orEmpty())
        assertTrue("pause" in byHash["h-p5-downloading"].orEmpty())
        assertTrue("top_priority" in byHash["h-p1-downloading"].orEmpty())
    }

    @Test fun p2_active_no_p1_pauses_only_p5() {
        val actions = computeQBitPauseActions(listOf(
            dl(priority = 2), dl(priority = 4), dl(priority = 5),
        ))
        val byHash = actions.groupBy { it.hash }.mapValues { it.value.map { a -> a.action } }
        assertEquals(emptyList(), byHash["h-p4-downloading"] ?: emptyList()) // P4 untouched
        assertTrue("pause" in byHash["h-p5-downloading"].orEmpty())
    }

    @Test fun only_p3_through_p5_no_pauses() {
        val actions = computeQBitPauseActions(listOf(
            dl(priority = 3), dl(priority = 4), dl(priority = 5),
        ))
        assertTrue(actions.none { it.action == "pause" })
    }

    // ---------- stale pausedByUs clearance (the regression) ----------

    @Test fun p1_torrent_with_stale_pausedByUs_and_state_downloading_emits_resume() {
        // Torrent was paused long ago (pausedByUs=true) but qBit has
        // since auto-resumed (state=downloading). Reconciler must clear
        // the flag by emitting "resume" regardless of current state.
        val actions = computeQBitPauseActions(listOf(
            dl(priority = 1, state = "downloading", pausedByUs = true),
        ))
        assertTrue(
            actions.any { it.action == "resume" },
            "priority=1 + pausedByUs=true must trigger resume to clear the DB flag, " +
                "got actions=${actions.map { it.action }}"
        )
    }

    @Test fun p1_torrent_with_stale_pausedByUs_and_state_stalledDL_also_resumes() {
        val actions = computeQBitPauseActions(listOf(
            dl(priority = 1, state = "stalledDL", pausedByUs = true),
        ))
        assertTrue(actions.any { it.action == "resume" })
    }

    @Test fun p1_torrent_actually_paused_with_flag_still_resumes() {
        // Original case still works: priority is out of pause band,
        // torrent actually paused, flag set → resume.
        val actions = computeQBitPauseActions(listOf(
            dl(priority = 1, state = "pausedDL", pausedByUs = true),
        ))
        assertTrue(actions.any { it.action == "resume" })
    }

    @Test fun p5_pausedByUs_stays_paused_while_p1_is_active() {
        // With an active P1 around, pauseLevels = {4, 5} — so the
        // P5's pause is still justified. No resume emitted; the flag
        // stays set.
        val actions = computeQBitPauseActions(listOf(
            dl(priority = 1, state = "downloading"),
            dl(priority = 5, state = "pausedDL", pausedByUs = true),
        ))
        assertTrue(
            actions.none { it.action == "resume" },
            "P5 with active P1 should stay paused, actions=${actions.map { it.action }}"
        )
    }

    @Test fun p5_pausedByUs_resumes_when_pause_band_collapses() {
        // No active P1 or P2 → pauseLevels = ∅ → nothing justifies
        // keeping a P5 paused. Emit resume so the DB flag + qBit
        // state both come back to life.
        val actions = computeQBitPauseActions(listOf(
            dl(priority = 5, state = "pausedDL", pausedByUs = true),
        ))
        assertTrue(actions.any { it.action == "resume" })
    }

    @Test fun torrent_not_pausedByUs_never_resumes_spuriously() {
        // Nothing in the DB said we paused it — never emit a resume.
        val actions = computeQBitPauseActions(listOf(
            dl(priority = 1, state = "downloading", pausedByUs = false),
            dl(priority = 3, state = "downloading", pausedByUs = false),
        ))
        assertTrue(actions.none { it.action == "resume" })
    }

    // ---------- top_priority ----------

    @Test fun p1_downloading_also_gets_top_priority() {
        val actions = computeQBitPauseActions(listOf(dl(priority = 1)))
        assertTrue(actions.any { it.action == "top_priority" })
    }

    @Test fun p1_paused_does_not_get_top_priority() {
        // "top_priority" only makes sense for an active torrent.
        val actions = computeQBitPauseActions(listOf(dl(priority = 1, state = "pausedDL")))
        assertTrue(actions.none { it.action == "top_priority" })
    }
}
