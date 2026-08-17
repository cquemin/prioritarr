package org.yoshiz.app.prioritarr.backend.reconcile

import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleLadderModelTest {

    private val base = "Show - S01E01 - Title WEBDL-1080p"

    @Test
    fun exact_en_srt_is_satisfied() {
        assertEquals(
            SidecarState.SATISFIED,
            classifySidecars(setOf("$base.mkv", "$base.en.srt"), base),
        )
    }

    @Test
    fun hi_variant_is_variant_only() {
        assertEquals(
            SidecarState.VARIANT_ONLY,
            classifySidecars(setOf("$base.mkv", "$base.en.hi.srt"), base),
        )
    }

    @Test
    fun bare_srt_is_variant_only() {
        assertEquals(
            SidecarState.VARIANT_ONLY,
            classifySidecars(setOf("$base.mkv", "$base.srt"), base),
        )
    }

    @Test
    fun forced_alone_is_none_not_satisfied() {
        // Forced tracks carry signs/songs only, never dialogue.
        assertEquals(
            SidecarState.NONE,
            classifySidecars(setOf("$base.mkv", "$base.en.forced.srt"), base),
        )
    }

    @Test
    fun exact_wins_over_variant() {
        assertEquals(
            SidecarState.SATISFIED,
            classifySidecars(setOf("$base.en.srt", "$base.en.hi.srt"), base),
        )
    }

    @Test
    fun nothing_is_none() {
        assertEquals(SidecarState.NONE, classifySidecars(setOf("$base.mkv"), base))
    }

    @Test
    fun other_episode_sidecar_does_not_leak() {
        assertEquals(
            SidecarState.NONE,
            classifySidecars(setOf("$base.mkv", "Show - S01E02 - Other.en.srt"), base),
        )
    }

    @Test
    fun satisfied_short_circuits() {
        assertEquals(
            Rung.R0_SATISFIED,
            nextRung(SidecarState.SATISFIED, hasEmbeddedEnglishText = true, priority = 1,
                whisperMaxPriority = 2, whisperEnabled = true),
        )
    }

    @Test
    fun embedded_text_track_is_tried_first() {
        assertEquals(
            Rung.R1_EMBEDDED,
            nextRung(SidecarState.NONE, hasEmbeddedEnglishText = true, priority = 5,
                whisperMaxPriority = 2, whisperEnabled = true),
        )
    }

    @Test
    fun variant_still_allows_the_free_rung() {
        assertEquals(
            Rung.R1_EMBEDDED,
            nextRung(SidecarState.VARIANT_ONLY, hasEmbeddedEnglishText = true, priority = 1,
                whisperMaxPriority = 2, whisperEnabled = true),
        )
    }

    @Test
    fun variant_blocks_the_expensive_rungs() {
        // No embedded track to extract, so R1 is unavailable. A P1 series
        // would normally reach Whisper — the variant must stop it.
        assertEquals(
            Rung.R4_EXHAUSTED,
            nextRung(SidecarState.VARIANT_ONLY, hasEmbeddedEnglishText = false, priority = 1,
                whisperMaxPriority = 2, whisperEnabled = true),
        )
    }

    @Test
    fun no_embedded_track_falls_to_bazarr() {
        assertEquals(
            Rung.R2_BAZARR,
            nextRung(SidecarState.NONE, hasEmbeddedEnglishText = false, priority = 5,
                whisperMaxPriority = 2, whisperEnabled = true),
        )
    }

    @Test
    fun whisper_only_within_the_priority_gate() {
        // P1 is inside the gate.
        assertEquals(
            Rung.R3_WHISPER,
            nextRung(SidecarState.NONE, hasEmbeddedEnglishText = false, priority = 1,
                whisperMaxPriority = 2, whisperEnabled = true, bazarrAlreadyTried = true),
        )
        // P3 is outside it.
        assertEquals(
            Rung.R4_EXHAUSTED,
            nextRung(SidecarState.NONE, hasEmbeddedEnglishText = false, priority = 3,
                whisperMaxPriority = 2, whisperEnabled = true, bazarrAlreadyTried = true),
        )
    }

    @Test
    fun whisper_master_switch_off_exhausts_instead() {
        assertEquals(
            Rung.R4_EXHAUSTED,
            nextRung(SidecarState.NONE, hasEmbeddedEnglishText = false, priority = 1,
                whisperMaxPriority = 2, whisperEnabled = false, bazarrAlreadyTried = true),
        )
    }

    @Test
    fun backoff_escalates_then_caps_at_four_weeks() {
        assertEquals(java.time.Duration.ofDays(1), backoffFor(1))
        assertEquals(java.time.Duration.ofDays(3), backoffFor(2))
        assertEquals(java.time.Duration.ofDays(7), backoffFor(3))
        assertEquals(java.time.Duration.ofDays(28), backoffFor(4))
        assertEquals(java.time.Duration.ofDays(28), backoffFor(99), "must cap")
    }

    @Test
    fun upstream_down_does_not_consume_an_attempt() {
        // A Bazarr outage must never push an episode toward 4-week backoff.
        assertEquals(false, consumesAttempt(LadderOutcome.UPSTREAM_DOWN))
        assertEquals(true, consumesAttempt(LadderOutcome.NO_SOURCE))
        assertEquals(true, consumesAttempt(LadderOutcome.UNREADABLE))
    }

    @Test
    fun plex_streaming_closes_the_gate() {
        val g = decideLadderGate(plexSessions = 1, congested = false, idleTicks = 5, resumeAfterIdleTicks = 3)
        assertEquals(false, g.open)
        assertEquals(0, g.idleTicks, "a live session resets the idle counter")
    }

    @Test
    fun plex_probe_error_means_busy_not_idle() {
        // Deliberately unlike decideTdarrPause, which treats an error as 0
        // sessions. For a CPU-bound rung a false "idle" during a stream is
        // the expensive mistake, so null fails CLOSED.
        val g = decideLadderGate(plexSessions = null, congested = false, idleTicks = 99, resumeAfterIdleTicks = 3)
        assertEquals(false, g.open)
        assertEquals(0, g.idleTicks)
    }

    @Test
    fun congestion_closes_the_gate_even_when_plex_is_idle() {
        val g = decideLadderGate(plexSessions = 0, congested = true, idleTicks = 9, resumeAfterIdleTicks = 3)
        assertEquals(false, g.open)
    }

    @Test
    fun gate_opens_only_after_enough_idle_ticks() {
        // Plex reports 0 momentarily mid-playback; debounce before acting.
        val t1 = decideLadderGate(0, congested = false, idleTicks = 0, resumeAfterIdleTicks = 3)
        assertEquals(false, t1.open); assertEquals(1, t1.idleTicks)
        val t2 = decideLadderGate(0, congested = false, idleTicks = t1.idleTicks, resumeAfterIdleTicks = 3)
        assertEquals(false, t2.open); assertEquals(2, t2.idleTicks)
        val t3 = decideLadderGate(0, congested = false, idleTicks = t2.idleTicks, resumeAfterIdleTicks = 3)
        assertEquals(true, t3.open); assertEquals(3, t3.idleTicks)
    }
}
