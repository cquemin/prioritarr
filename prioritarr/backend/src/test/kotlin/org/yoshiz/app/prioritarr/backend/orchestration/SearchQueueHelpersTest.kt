package org.yoshiz.app.prioritarr.backend.orchestration

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchQueueHelpersTest {
    private fun cmd(id: Long, name: String, status: String) = SonarrCommand(id, name, status, null)

    private val sample = listOf(
        cmd(1, "SeriesSearch", "started"),
        cmd(2, "SeasonSearch", "queued"),
        cmd(3, "CutoffUnmetSearch", "queued"),
        cmd(4, "EpisodeSearch", "started"), // P1/P2 — counts toward congestion, NOT cancellable
        cmd(5, "SeriesSearch", "completed"), // not active
        cmd(6, "RefreshSeries", "started"), // not a search
    )

    @Test fun pendingSearchCount_counts_active_searches_only() {
        assertEquals(4, pendingSearchCount(sample))
    }

    @Test fun cancellable_excludes_episodesearch_and_inactive() {
        assertEquals(listOf(1L, 2L, 3L), cancellableBackfillSearchIds(sample))
    }

    @Test fun cancellable_never_includes_a_queued_p1_episode_search() {
        val ids = cancellableBackfillSearchIds(listOf(cmd(9, "EpisodeSearch", "queued")))
        assertEquals(emptyList(), ids)
    }
}
