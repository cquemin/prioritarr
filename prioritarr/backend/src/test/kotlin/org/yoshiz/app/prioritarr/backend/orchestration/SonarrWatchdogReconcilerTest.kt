package org.yoshiz.app.prioritarr.backend.orchestration

import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SonarrWatchdogReconcilerTest {
    private val cfg = WatchdogConfig(30, confirmChecks = 2, graceMinutes = 10, cooldownMinutes = 120)
    private val t0 = Instant.parse("2026-06-14T10:00:00Z")
    private fun stuck(id: Long) = SonarrCommand(id, "SeriesSearch", "started", t0.minusSeconds(3600))

    @Test fun escalates_app_app_app_container_across_ticks() = runTest {
        var clock = t0
        val appCalls = mutableListOf<Instant>()
        val containerCalls = mutableListOf<Instant>()
        val w = SonarrWatchdog(
            getStuckCommands = { listOf(stuck(1)) }, // always wedged
            appRestart = { appCalls += clock },
            containerRestart = { containerCalls += clock },
            now = { clock },
            cfg = cfg,
            dryRun = { false },
        )
        // tick1 confirm; ticks 2..4 app x3; tick5 container. Advance 15m between to clear grace.
        repeat(5) { w.reconcile(); clock = clock.plusSeconds(15 * 60) }
        assertEquals(3, appCalls.size)
        assertEquals(1, containerCalls.size)
    }

    @Test fun dry_run_invokes_no_seam_but_advances() = runTest {
        var clock = t0
        var appCalled = false
        val w = SonarrWatchdog(
            getStuckCommands = { listOf(stuck(1)) },
            appRestart = { appCalled = true },
            containerRestart = null,
            now = { clock },
            cfg = cfg,
            dryRun = { true },
        )
        w.reconcile(); clock = clock.plusSeconds(5 * 60)
        val out = w.reconcile() // confirmed -> would app-restart
        assertEquals(false, appCalled)
        assertTrue(out.summary!!.contains("app-restart"))
    }

    @Test fun unreachable_sonarr_is_noop_no_state_change() = runTest {
        val clock = t0
        val appCalls = mutableListOf<Instant>()
        val w = SonarrWatchdog(
            getStuckCommands = { throw RuntimeException("connection refused") },
            appRestart = { appCalls += clock },
            containerRestart = null,
            now = { clock },
            cfg = cfg,
            dryRun = { false },
        )
        val out = w.reconcile()
        assertTrue(out.noop)
        assertEquals(0, appCalls.size)
    }
}
