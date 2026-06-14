package org.yoshiz.app.prioritarr.backend.orchestration

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchQueueControlTest {
    private fun cmd(id: Long, name: String, status: String) = SonarrCommand(id, name, status, null)

    @Test fun isCongested_true_at_or_above_threshold() = runTest {
        val cmds = listOf(cmd(1, "SeriesSearch", "started"), cmd(2, "EpisodeSearch", "queued"), cmd(3, "SeasonSearch", "queued"))
        val c = SearchQueueControl(getCommands = { cmds }, cancelCommand = {}, threshold = { 3 }, dryRun = { false })
        assertEquals(true, c.isCongested())
    }

    @Test fun isCongested_false_below_threshold() = runTest {
        val cmds = listOf(cmd(1, "SeriesSearch", "started"))
        val c = SearchQueueControl(getCommands = { cmds }, cancelCommand = {}, threshold = { 3 }, dryRun = { false })
        assertEquals(false, c.isCongested())
    }

    @Test fun isCongested_false_on_error() = runTest {
        val c = SearchQueueControl(getCommands = { throw RuntimeException("down") }, cancelCommand = {}, threshold = { 1 }, dryRun = { false })
        assertEquals(false, c.isCongested())
    }

    @Test fun cancelBackfill_cancels_each_id_and_returns_count() = runTest {
        val cmds = listOf(cmd(1, "SeriesSearch", "started"), cmd(2, "CutoffUnmetSearch", "queued"), cmd(3, "EpisodeSearch", "started"))
        val cancelled = mutableListOf<Long>()
        val c = SearchQueueControl(getCommands = { cmds }, cancelCommand = { cancelled += it }, threshold = { 3 }, dryRun = { false })
        val n = c.cancelBackfill()
        assertEquals(2, n)
        assertEquals(listOf(1L, 2L), cancelled) // never the EpisodeSearch
    }

    @Test fun cancelBackfill_dry_run_cancels_nothing_but_returns_count() = runTest {
        val cmds = listOf(cmd(1, "SeriesSearch", "started"))
        val cancelled = mutableListOf<Long>()
        val c = SearchQueueControl(getCommands = { cmds }, cancelCommand = { cancelled += it }, threshold = { 3 }, dryRun = { true })
        assertEquals(1, c.cancelBackfill())
        assertEquals(emptyList(), cancelled)
    }
}
