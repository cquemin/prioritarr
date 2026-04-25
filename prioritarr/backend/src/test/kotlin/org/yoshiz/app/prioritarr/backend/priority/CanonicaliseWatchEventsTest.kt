package org.yoshiz.app.prioritarr.backend.priority

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * White-box test of the watch-event canonicalisation logic that lives
 * inline in [PriorityService.buildSnapshot]. Reproduces the same
 * data-flow against a small fixed Sonarr-aired set so we can assert
 * the invariant `watched ≤ aired` and the abs-fallback semantics
 * without spinning up the full HTTP layer.
 *
 * The canonicalisation rule (mirrors PriorityService):
 *   1. Exact (season, episode) hit on the monitored-aired set → use it.
 *   2. Else, if the event has an absolute episode number that maps to
 *      a monitored-aired (season, episode) → use the canonical pair.
 *   3. Else, drop.
 */
class CanonicaliseWatchEventsTest {

    /** Inline copy of the matching rule in PriorityService.buildSnapshot. */
    private fun canonicalise(
        events: List<WatchEvent>,
        monitoredAiredSE: Set<Pair<Int, Int>>,
        monitoredAiredByAbs: Map<Int, Pair<Int, Int>>,
    ): Set<Pair<Int, Int>> = buildSet {
        for (e in events) {
            val canonical: Pair<Int, Int>? = when {
                (e.season to e.episode) in monitoredAiredSE -> e.season to e.episode
                e.absoluteEpisode != null -> monitoredAiredByAbs[e.absoluteEpisode]
                else -> null
            }
            if (canonical != null) add(canonical)
        }
    }

    private fun ev(s: Int, e: Int, abs: Int? = null, src: String = "trakt") =
        WatchEvent(s, e, Instant.EPOCH, src, absoluteEpisode = abs)

    @Test fun exact_se_hit_counts_once() {
        val aired = setOf(2 to 1, 2 to 2, 3 to 1)
        val abs = mapOf(25 to (2 to 1), 26 to (2 to 2), 49 to (3 to 1))
        val out = canonicalise(listOf(ev(2, 1), ev(2, 2)), aired, abs)
        assertEquals(setOf(2 to 1, 2 to 2), out)
    }

    @Test fun watched_never_exceeds_aired_even_with_excess_events() {
        // 3 aired episodes, 50 watch events all hitting the same 3 (s,e) — rewatches.
        val aired = setOf(1 to 1, 1 to 2, 1 to 3)
        val abs = emptyMap<Int, Pair<Int, Int>>()
        val events = (1..50).flatMap { listOf(ev(1, 1), ev(1, 2), ev(1, 3)) }
        val out = canonicalise(events, aired, abs)
        assertEquals(3, out.size)
        assertTrue(out.all { it in aired })
    }

    @Test fun unmatched_se_with_no_abs_is_dropped() {
        // Event reports a season Sonarr doesn't monitor — drop.
        val aired = setOf(2 to 1)
        val abs = mapOf(25 to (2 to 1))
        val out = canonicalise(listOf(ev(99, 1)), aired, abs)
        assertTrue(out.isEmpty())
    }

    @Test fun abs_fallback_when_se_does_not_match() {
        // Trakt-style scenario: Trakt reports S5E50, but Sonarr's
        // monitored-aired set numbers the same episode S2E2 with abs=26.
        val aired = setOf(2 to 1, 2 to 2)
        val abs = mapOf(25 to (2 to 1), 26 to (2 to 2))
        val out = canonicalise(listOf(ev(5, 50, abs = 26)), aired, abs)
        assertEquals(setOf(2 to 2), out)
    }

    @Test fun abs_fallback_does_not_help_when_abs_unknown() {
        val aired = setOf(2 to 1)
        val abs = mapOf(25 to (2 to 1))
        // abs=999 isn't in the map → drop.
        val out = canonicalise(listOf(ev(5, 50, abs = 999)), aired, abs)
        assertTrue(out.isEmpty())
    }

    @Test fun mixed_sources_dedup_via_canonical_pair() {
        // Tautulli reports S2E1 directly (no abs). Trakt reports the
        // same physical episode as S5E10 with abs=25. Both should
        // collapse onto the canonical (2,1).
        val aired = setOf(2 to 1)
        val abs = mapOf(25 to (2 to 1))
        val events = listOf(
            ev(2, 1, src = "tautulli"),
            ev(5, 10, abs = 25, src = "trakt"),
        )
        val out = canonicalise(events, aired, abs)
        assertEquals(setOf(2 to 1), out)
    }

    @Test fun no_aired_means_no_watched() {
        val out = canonicalise(listOf(ev(1, 1), ev(2, 5, abs = 10)), emptySet(), emptyMap())
        assertTrue(out.isEmpty())
    }
}
