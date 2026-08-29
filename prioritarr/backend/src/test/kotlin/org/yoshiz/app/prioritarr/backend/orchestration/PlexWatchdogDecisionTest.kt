package org.yoshiz.app.prioritarr.backend.orchestration

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlexWatchdogDecisionTest {
    private val cfg = PlexWatchdogConfig(graceMinutes = 10, analyzeWaitMinutes = 5, cooldownMinutes = 120)
    private val t0 = Instant.parse("2026-08-29T15:00:00Z")
    private fun at(min: Long) = t0.plusSeconds(min * 60)

    @Test fun healthy_resets_all_state() {
        val dirty = PlexWatchdogState(analyzeRequestedAt = mapOf("1" to t0), containerRestartAt = t0)
        val d = decidePlexWatchdog(emptyList(), false, true, dirty, at(1), cfg)
        assertEquals(PlexWatchdogAction.NONE, d.action)
        assertEquals(PlexWatchdogState(), d.state)
    }

    @Test fun first_sighting_requests_analyze_for_every_broken_key() {
        val d = decidePlexWatchdog(listOf("1", "2"), false, true, PlexWatchdogState(), t0, cfg)
        assertEquals(PlexWatchdogAction.ANALYZE, d.action)
        assertEquals(listOf("1", "2"), d.keys)
        assertEquals(mapOf("1" to t0, "2" to t0), d.state.analyzeRequestedAt)
    }

    @Test fun new_broken_key_is_analyzed_without_re_analyzing_old_ones() {
        val s = PlexWatchdogState(analyzeRequestedAt = mapOf("1" to t0))
        val d = decidePlexWatchdog(listOf("1", "2"), false, true, s, at(1), cfg)
        assertEquals(PlexWatchdogAction.ANALYZE, d.action)
        assertEquals(listOf("2"), d.keys)
        assertEquals(t0, d.state.analyzeRequestedAt["1"])
    }

    @Test fun waits_for_analyze_before_escalating() {
        val s = PlexWatchdogState(analyzeRequestedAt = mapOf("1" to t0))
        val d = decidePlexWatchdog(listOf("1"), false, true, s, at(2), cfg) // 2m < 5m
        assertEquals(PlexWatchdogAction.NONE, d.action)
        assertTrue(d.reason.contains("waiting"))
    }

    @Test fun escalates_to_container_restart_only_when_plex_idle() {
        val s = PlexWatchdogState(analyzeRequestedAt = mapOf("1" to t0))
        val busy = decidePlexWatchdog(listOf("1"), true, true, s, at(6), cfg)
        assertEquals(PlexWatchdogAction.NONE, busy.action)
        assertEquals(null, busy.state.containerRestartAt)

        val unknown = decidePlexWatchdog(listOf("1"), null, true, s, at(6), cfg)
        assertEquals(PlexWatchdogAction.NONE, unknown.action)

        val idle = decidePlexWatchdog(listOf("1"), false, true, s, at(6), cfg)
        assertEquals(PlexWatchdogAction.CONTAINER_RESTART, idle.action)
        assertEquals(at(6), idle.state.containerRestartAt)
    }

    @Test fun after_container_restart_broken_keys_are_re_analyzed() {
        val s = PlexWatchdogState(analyzeRequestedAt = mapOf("1" to t0), containerRestartAt = at(6))
        val d = decidePlexWatchdog(listOf("1"), false, true, s, at(11), cfg)
        assertEquals(PlexWatchdogAction.ANALYZE, d.action)
        assertEquals(listOf("1"), d.keys)
        assertEquals(at(11), d.state.analyzeRequestedAt["1"])
        assertEquals(at(6), d.state.containerRestartAt)
    }

    @Test fun exhausted_after_restart_and_second_analyze_fail() {
        val s = PlexWatchdogState(analyzeRequestedAt = mapOf("1" to at(11)), containerRestartAt = at(6))
        val d = decidePlexWatchdog(listOf("1"), false, true, s, at(17), cfg)
        assertEquals(PlexWatchdogAction.EXHAUSTED, d.action)
        assertEquals(at(17).plusSeconds(120 * 60), d.state.cooldownUntil)
        assertEquals(emptyMap(), d.state.analyzeRequestedAt)
        assertEquals(null, d.state.containerRestartAt)
    }

    @Test fun no_container_fallback_exhausts_after_analyze() {
        val s = PlexWatchdogState(analyzeRequestedAt = mapOf("1" to t0))
        val d = decidePlexWatchdog(listOf("1"), false, false, s, at(6), cfg)
        assertEquals(PlexWatchdogAction.EXHAUSTED, d.action)
        assertTrue(d.reason.contains("no container fallback"))
    }

    @Test fun cooldown_blocks_everything_until_expiry() {
        val s = PlexWatchdogState(cooldownUntil = at(120))
        val d = decidePlexWatchdog(listOf("1"), false, true, s, at(60), cfg)
        assertEquals(PlexWatchdogAction.NONE, d.action)
        assertEquals(s, d.state)

        val after = decidePlexWatchdog(listOf("1"), false, true, s, at(121), cfg)
        assertEquals(PlexWatchdogAction.ANALYZE, after.action)
    }

    @Test fun full_ladder_sequence() {
        var s = PlexWatchdogState()
        val actions = mutableListOf<PlexWatchdogAction>()
        var clock = 0L
        repeat(5) {
            val d = decidePlexWatchdog(listOf("1"), false, true, s, at(clock), cfg)
            actions += d.action
            s = d.state
            clock += 6 // beyond analyzeWait each tick
        }
        assertEquals(
            listOf(
                PlexWatchdogAction.ANALYZE,
                PlexWatchdogAction.CONTAINER_RESTART,
                PlexWatchdogAction.ANALYZE,
                PlexWatchdogAction.EXHAUSTED,
                PlexWatchdogAction.NONE, // cooldown
            ),
            actions,
        )
    }
}
