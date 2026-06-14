package org.yoshiz.app.prioritarr.backend.orchestration

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class SonarrWatchdogDecisionTest {
    private val cfg = WatchdogConfig(stallMinutes = 30, confirmChecks = 2, graceMinutes = 10, cooldownMinutes = 120)
    private val t0 = Instant.parse("2026-06-14T10:00:00Z")
    private fun at(min: Long) = t0.plusSeconds(min * 60)

    @Test fun healthy_resets_all_state() {
        val dirty = WatchdogState(confirmStreak = 2, appRestartCount = 2, lastActionAt = t0)
        val d = decideWatchdog(wedged = false, containerAvailable = true, state = dirty, now = t0, cfg = cfg)
        assertEquals(WatchdogAction.NONE, d.action)
        assertEquals(WatchdogState(), d.state)
    }

    @Test fun first_wedge_tick_confirms_only() {
        val d = decideWatchdog(true, true, WatchdogState(), t0, cfg)
        assertEquals(WatchdogAction.NONE, d.action)
        assertEquals(1, d.state.confirmStreak)
    }

    @Test fun second_wedge_tick_fires_first_app_restart() {
        val s1 = decideWatchdog(true, true, WatchdogState(), t0, cfg).state
        val d = decideWatchdog(true, true, s1, at(5), cfg)
        assertEquals(WatchdogAction.APP_RESTART, d.action)
        assertEquals(1, d.state.appRestartCount)
        assertEquals(at(5), d.state.lastActionAt)
    }

    @Test fun grace_blocks_a_second_restart_too_soon() {
        val s = WatchdogState(confirmStreak = 2, appRestartCount = 1, lastActionAt = t0)
        val d = decideWatchdog(true, true, s, at(5), cfg) // only 5m < 10m grace
        assertEquals(WatchdogAction.NONE, d.action)
        assertEquals(1, d.state.appRestartCount)
    }

    @Test fun escalates_app_x3_then_container() {
        var s = WatchdogState(confirmStreak = 2)
        val actions = mutableListOf<WatchdogAction>()
        var clock = 0L
        repeat(4) {
            val d = decideWatchdog(true, true, s, at(clock), cfg)
            actions += d.action
            s = d.state
            clock += 15 // beyond grace each time
        }
        assertEquals(
            listOf(
                WatchdogAction.APP_RESTART,
                WatchdogAction.APP_RESTART,
                WatchdogAction.APP_RESTART,
                WatchdogAction.CONTAINER_RESTART,
            ),
            actions,
        )
    }

    @Test fun container_unavailable_goes_straight_to_exhausted_after_three_app_restarts() {
        val s = WatchdogState(confirmStreak = 2, appRestartCount = 3)
        val d = decideWatchdog(true, containerAvailable = false, state = s, now = at(30), cfg = cfg)
        assertEquals(WatchdogAction.EXHAUSTED, d.action)
        assertEquals(at(30).plusSeconds(120 * 60), d.state.cooldownUntil)
    }

    @Test fun exhausted_then_cooldown_blocks_further_action() {
        val cooled = WatchdogState(confirmStreak = 2, cooldownUntil = at(120))
        val d = decideWatchdog(true, true, cooled, at(60), cfg) // still inside cooldown
        assertEquals(WatchdogAction.NONE, d.action)
    }
}
