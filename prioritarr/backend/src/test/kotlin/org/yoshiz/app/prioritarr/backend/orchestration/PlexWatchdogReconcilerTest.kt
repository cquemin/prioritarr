package org.yoshiz.app.prioritarr.backend.orchestration

import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlexWatchdogReconcilerTest {
    private val cfg = PlexWatchdogConfig(graceMinutes = 10, analyzeWaitMinutes = 5, cooldownMinutes = 120)
    private val t0 = Instant.parse("2026-08-29T15:00:00Z")
    private fun old(key: String) = PlexRecentItem(key, "ep $key", t0.minusSeconds(3600))
    private fun fresh(key: String) = PlexRecentItem(key, "ep $key", t0.minusSeconds(60))

    private class Calls {
        val analyzed = mutableListOf<String>()
        val refreshed = mutableListOf<String>()
        var restarts = 0
    }

    private fun watchdog(
        items: () -> List<PlexRecentItem>,
        streams: (String) -> Int,
        sessions: () -> Int? = { 0 },
        clock: () -> Instant,
        calls: Calls,
        container: Boolean = true,
        dryRun: Boolean = false,
    ) = PlexWatchdog(
        listRecentItems = { items() },
        streamCount = { streams(it) },
        analyze = { calls.analyzed += it },
        refresh = { calls.refreshed += it },
        activeSessions = { sessions() },
        containerRestart = if (container) ({ calls.restarts++ }) else null,
        now = clock,
        cfg = { cfg },
        dryRun = { dryRun },
    )

    @Test fun zero_stream_items_past_grace_get_analyze_and_refresh() = runTest {
        val calls = Calls()
        val w = watchdog(
            items = { listOf(old("1"), old("2"), fresh("3")) },
            streams = { if (it == "2") 8 else 0 },
            clock = { t0 },
            calls = calls,
        )
        val out = w.reconcile()
        assertEquals(listOf("1"), calls.analyzed) // "3" is inside grace, "2" is healthy
        assertEquals(listOf("1"), calls.refreshed)
        assertEquals(false, out.noop)
        assertTrue(out.summary!!.contains("analyze 1 item"))
    }

    @Test fun healthy_library_is_noop_and_never_probes_sessions() = runTest {
        val calls = Calls()
        var sessionProbes = 0
        val w = watchdog(
            items = { listOf(old("1")) },
            streams = { 5 },
            sessions = { sessionProbes++; 0 },
            clock = { t0 },
            calls = calls,
        )
        val out = w.reconcile()
        assertTrue(out.noop)
        assertEquals(0, sessionProbes)
        assertEquals(0, calls.analyzed.size)
    }

    @Test fun ladder_analyze_then_restart_then_reanalyze_then_exhausted() = runTest {
        val calls = Calls()
        var clock = t0
        val w = watchdog(
            items = { listOf(PlexRecentItem("1", "ep", t0.minusSeconds(3600))) },
            streams = { 0 }, // never recovers
            clock = { clock },
            calls = calls,
        )
        val summaries = mutableListOf<String>()
        repeat(5) { summaries += w.reconcile().summary!!; clock = clock.plusSeconds(6 * 60) }
        assertEquals(2, calls.analyzed.size)
        assertEquals(1, calls.restarts)
        assertTrue(summaries[3].contains("cooldown"))
        assertTrue(summaries[4].contains("cooldown"))
    }

    @Test fun restart_waits_while_plex_is_streaming() = runTest {
        val calls = Calls()
        var clock = t0
        var sessions = 1
        val w = watchdog(
            items = { listOf(old("1")) },
            streams = { 0 },
            sessions = { sessions },
            clock = { clock },
            calls = calls,
        )
        w.reconcile(); clock = clock.plusSeconds(6 * 60) // analyze
        w.reconcile(); clock = clock.plusSeconds(6 * 60) // busy → hold
        assertEquals(0, calls.restarts)
        sessions = 0
        w.reconcile()
        assertEquals(1, calls.restarts)
    }

    @Test fun recovery_after_analyze_resets_state() = runTest {
        val calls = Calls()
        var clock = t0
        var streams = 0
        val w = watchdog(
            items = { listOf(old("1")) },
            streams = { streams },
            clock = { clock },
            calls = calls,
        )
        w.reconcile(); clock = clock.plusSeconds(6 * 60)
        streams = 7 // analyze worked
        val out = w.reconcile()
        assertTrue(out.noop)
        assertEquals("healthy", out.summary)
        assertEquals(0, calls.restarts)
    }

    @Test fun dry_run_invokes_no_seam_but_advances() = runTest {
        val calls = Calls()
        var clock = t0
        val w = watchdog(
            items = { listOf(old("1")) },
            streams = { 0 },
            clock = { clock },
            calls = calls,
            dryRun = true,
        )
        val first = w.reconcile(); clock = clock.plusSeconds(6 * 60)
        val second = w.reconcile()
        assertTrue(first.summary!!.contains("analyze"))
        assertTrue(second.summary!!.contains("container-restart"))
        assertEquals(0, calls.analyzed.size)
        assertEquals(0, calls.restarts)
    }

    @Test fun unreachable_plex_is_noop_no_state_change() = runTest {
        val calls = Calls()
        val w = watchdog(
            items = { throw RuntimeException("connection refused") },
            streams = { 0 },
            clock = { t0 },
            calls = calls,
        )
        val out = w.reconcile()
        assertTrue(out.noop)
        assertEquals(0, calls.analyzed.size)
    }

    @Test fun failed_stream_probe_skips_that_item_only() = runTest {
        val calls = Calls()
        val w = watchdog(
            items = { listOf(old("1"), old("2")) },
            streams = { if (it == "1") throw RuntimeException("500") else 0 },
            clock = { t0 },
            calls = calls,
        )
        w.reconcile()
        assertEquals(listOf("2"), calls.analyzed)
    }
}
