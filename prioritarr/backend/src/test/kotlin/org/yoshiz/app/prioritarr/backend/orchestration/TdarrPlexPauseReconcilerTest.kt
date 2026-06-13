package org.yoshiz.app.prioritarr.backend.orchestration

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Behavioural test of the stateful Tdarr-pause reconciler across several
 * ticks. Drives the reconciler through scripted Plex session readings
 * and asserts the writes it makes to Tdarr — proving the idle counter is
 * carried between ticks and that Tdarr is written only when its desired
 * state actually changes.
 *
 * Seams are plain in-memory lambdas (the dependency-injection remedy),
 * so the reconciler's own logic is exercised without any HTTP.
 */
class TdarrPlexPauseReconcilerTest {

    @Test fun momentary_idle_readings_do_not_resume_until_threshold() = runTest {
        // Stream playing, then three consecutive idle polls (the middle
        // ones model Plex's mid-playback false-negatives).
        val sessions = ArrayDeque(listOf(1, 0, 0, 0))
        var paused = false
        val setCalls = mutableListOf<Boolean>()
        val r = TdarrPlexPause(
            sessionCount = { sessions.removeFirst() },
            isPaused = { paused },
            setPaused = { p -> paused = p; setCalls.add(p) },
            resumeAfterIdleTicks = 3,
        )

        r.reconcile() // sessions=1 -> pause
        r.reconcile() // idle #1 -> stay paused
        r.reconcile() // idle #2 -> stay paused
        r.reconcile() // idle #3 -> resume

        // Exactly one pause and one resume — no flapping in between.
        assertEquals(listOf(true, false), setCalls)
        assertEquals(false, paused)
    }

    @Test fun single_idle_reading_mid_stream_never_resumes() = runTest {
        // play, one false-negative idle, then playing again.
        val sessions = ArrayDeque(listOf(1, 0, 1))
        var paused = false
        val setCalls = mutableListOf<Boolean>()
        val r = TdarrPlexPause(
            sessionCount = { sessions.removeFirst() },
            isPaused = { paused },
            setPaused = { p -> paused = p; setCalls.add(p) },
            resumeAfterIdleTicks = 3,
        )

        r.reconcile()
        r.reconcile()
        r.reconcile()

        assertEquals(listOf(true), setCalls, "the false-negative must not produce a resume")
        assertEquals(true, paused)
    }

    @Test fun writes_only_when_desired_state_changes() = runTest {
        // Already paused and a stream is active -> nothing to write.
        var paused = true
        val setCalls = mutableListOf<Boolean>()
        val r = TdarrPlexPause(
            sessionCount = { 1 },
            isPaused = { paused },
            setPaused = { p -> paused = p; setCalls.add(p) },
            resumeAfterIdleTicks = 3,
        )

        r.reconcile()

        assertEquals(emptyList(), setCalls)
    }
}
