package org.yoshiz.app.prioritarr.backend.priority

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bug these cover: when Trakt was rate limited but Tautulli was
 * healthy, the merge kept only the successes and the resulting priority
 * was cached for the full TTL. Priorities were then computed from
 * partial watch history and acted on — silently, with nothing marking
 * the entry as degraded.
 */
class ProviderFetchOutcomeTest {

    private fun event(season: Int, ep: Int, source: String) = WatchEvent(
        season = season,
        episode = ep,
        watchedAt = Instant.parse("2026-07-31T12:00:00Z"),
        source = source,
    )

    @Test fun all_providers_succeeding_is_not_degraded() {
        val outcome = mergeProviderResults(
            listOf(
                "tautulli" to Result.success(listOf(event(1, 1, "tautulli"))),
                "trakt" to Result.success(listOf(event(1, 2, "trakt"))),
            ),
        )
        assertFalse(outcome.degraded)
        assertEquals(2, outcome.events?.size)
    }

    @Test fun a_partial_failure_is_degraded_but_still_returns_events() {
        val outcome = mergeProviderResults(
            listOf(
                "tautulli" to Result.success(listOf(event(1, 1, "tautulli"))),
                "trakt" to Result.failure(RuntimeException("429")),
            ),
        )
        assertTrue(outcome.degraded)
        assertEquals(1, outcome.events?.size)
    }

    @Test fun every_provider_failing_yields_null_events() {
        val outcome = mergeProviderResults(
            listOf(
                "tautulli" to Result.failure(RuntimeException("down")),
                "trakt" to Result.failure(RuntimeException("429")),
            ),
        )
        assertNull(outcome.events)
        assertTrue(outcome.degraded)
    }

    @Test fun no_configured_providers_is_empty_and_not_degraded() {
        val outcome = mergeProviderResults(emptyList())
        assertEquals(emptyList(), outcome.events)
        assertFalse(outcome.degraded)
    }

    @Test fun degraded_results_get_a_short_cache_ttl() {
        assertEquals(
            DEGRADED_PRIORITY_CACHE_TTL_MINUTES,
            priorityCacheTtlMinutes(baseTtlMinutes = 120L, degraded = true),
        )
    }

    @Test fun healthy_results_keep_the_configured_ttl() {
        assertEquals(120L, priorityCacheTtlMinutes(baseTtlMinutes = 120L, degraded = false))
    }

    @Test fun a_base_ttl_shorter_than_the_degraded_floor_is_not_extended() {
        assertEquals(1L, priorityCacheTtlMinutes(baseTtlMinutes = 1L, degraded = true))
    }
}
