package org.yoshiz.app.prioritarr.backend.orchestration

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit test of the Tdarr-pause hysteresis decision. The reconciler must
 * pause Tdarr the instant Plex reports a stream, but resume only after
 * Plex has been idle for several consecutive ticks — Plex's
 * `/status/sessions` momentarily reports 0 mid-playback (a throttled,
 * fully-buffered transcode stops pinging its timeline), and a single
 * such false-negative must NOT un-pause Tdarr. Without this, Tdarr flaps
 * on/off every couple minutes and starves the live transcode.
 *
 * The decision is a pure function of (sessions, currentlyPaused,
 * idleTicks, resumeAfterIdleTicks) so the interesting behaviour is
 * tested in isolation from the HTTP clients.
 */
class TdarrPauseDecisionTest {

    @Test fun active_session_pauses_immediately_and_resets_idle() {
        val d = decideTdarrPause(sessions = 1, currentlyPaused = false, idleTicks = 0, resumeAfterIdleTicks = 3)
        assertEquals(true, d.desiredPaused)
        assertEquals(0, d.idleTicks)
    }

    @Test fun single_idle_tick_stays_paused_during_grace_window() {
        val d = decideTdarrPause(sessions = 0, currentlyPaused = true, idleTicks = 0, resumeAfterIdleTicks = 3)
        assertEquals(true, d.desiredPaused, "one idle reading must not resume Tdarr")
        assertEquals(1, d.idleTicks)
    }

    @Test fun resumes_only_after_threshold_consecutive_idle_ticks() {
        // idleTicks already 2; this third consecutive idle tick crosses the threshold.
        val d = decideTdarrPause(sessions = 0, currentlyPaused = true, idleTicks = 2, resumeAfterIdleTicks = 3)
        assertEquals(false, d.desiredPaused)
        assertEquals(3, d.idleTicks)
    }

    @Test fun momentary_idle_then_active_never_resumes() {
        // Regression for the flapping bug: a false-negative idle reading
        // (idleTicks=1) followed by the session reappearing must keep
        // Tdarr paused and reset the counter — no un-pause in between.
        val d = decideTdarrPause(sessions = 1, currentlyPaused = true, idleTicks = 1, resumeAfterIdleTicks = 3)
        assertEquals(true, d.desiredPaused)
        assertEquals(0, d.idleTicks)
    }

    @Test fun idle_while_already_running_stays_running() {
        val d = decideTdarrPause(sessions = 0, currentlyPaused = false, idleTicks = 0, resumeAfterIdleTicks = 3)
        assertEquals(false, d.desiredPaused)
        assertEquals(1, d.idleTicks)
    }

    @Test fun idle_counter_caps_at_threshold() {
        // Once resumed and still idle, the counter must not grow without bound.
        val d = decideTdarrPause(sessions = 0, currentlyPaused = false, idleTicks = 3, resumeAfterIdleTicks = 3)
        assertEquals(false, d.desiredPaused)
        assertEquals(3, d.idleTicks)
    }
}
