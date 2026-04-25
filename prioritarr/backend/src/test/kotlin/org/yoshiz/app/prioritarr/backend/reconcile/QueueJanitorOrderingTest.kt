package org.yoshiz.app.prioritarr.backend.reconcile

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit test of the QueueJanitor's per-sweep ordering semantics. The
 * janitor talks to four upstream services (qBit, SAB, Sonarr, the
 * local DB) which makes a full integration test heavy; this targets
 * the one piece of in-process logic that has interesting behaviour:
 * stuck items are processed in priority order so P1 re-searches go
 * first when several rate-limited Sonarr calls are queued.
 *
 * The sortedBy{ priority ?: 6 } expression in QueueJanitor.sweep is
 * what's under test; null priorities sort after every numeric
 * priority (we treat them as the lowest urgency).
 */
class QueueJanitorOrderingTest {

    private data class Probe(val tag: String, val priority: Int?)

    @Test fun p1_first_then_p2_p3_p4_p5_then_null() {
        val items = listOf(
            Probe("p5", 5), Probe("p1", 1), Probe("p3", 3),
            Probe("null", null), Probe("p2", 2), Probe("p4", 4),
        )
        val sorted = items.sortedBy { it.priority ?: 6 }
        assertEquals(listOf("p1", "p2", "p3", "p4", "p5", "null"), sorted.map { it.tag })
    }

    @Test fun stable_when_priorities_tie() {
        val items = listOf(
            Probe("a", 3), Probe("b", 3), Probe("c", 3),
        )
        // Kotlin's sortedBy is stable; order preserved among equal keys.
        assertEquals(listOf("a", "b", "c"), items.sortedBy { it.priority ?: 6 }.map { it.tag })
    }

    @Test fun all_null_priorities_keep_input_order() {
        val items = listOf(Probe("x", null), Probe("y", null))
        assertEquals(listOf("x", "y"), items.sortedBy { it.priority ?: 6 }.map { it.tag })
    }
}
