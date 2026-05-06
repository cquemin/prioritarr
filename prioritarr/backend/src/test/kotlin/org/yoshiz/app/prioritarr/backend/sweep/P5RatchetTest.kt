package org.yoshiz.app.prioritarr.backend.sweep

import org.yoshiz.app.prioritarr.backend.config.P5RatchetConfig
import org.yoshiz.app.prioritarr.backend.database.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class P5RatchetTest {

    private val cfg = P5RatchetConfig(
        enabled = true,
        searchCooldownHours = 24,
        longCooldownHours = 168,
        escalationThreshold = 5,
        includeSpecials = false,
        bandwidthThresholdPct = null,
    )
    private val now = 10_000_000L
    private fun hoursAgo(h: Int): Long = now - h * 3600L

    private fun rec(seriesId: Long, season: Int, episodeId: Long, airDate: String = "2020-01-01") =
        MissingRecord(seriesId, season, episodeId, airDate, priority = 5)

    private fun attempt(
        seriesId: Long, season: Int,
        lastAttemptedAt: Long = hoursAgo(25),
        lastMissingCount: Int? = 5,
        consecutive: Int = 0,
    ) = Database.P5SweepAttempt(seriesId, season, lastAttemptedAt, lastMissingCount, consecutive)

    private fun inputs(
        records: List<MissingRecord> = emptyList(),
        cooldowns: List<Database.P5SweepAttempt> = emptyList(),
        queueHits: Set<QueueSeasonHit> = emptySet(),
        budget: Int = 10,
        active: Boolean = true,
        cfgOverride: P5RatchetConfig = cfg,
    ) = P5RatchetInputs(records, queueHits, cooldowns, now, cfgOverride, budget, active)

    // ---------- bandwidth-headroom shortcut ----------

    @Test fun ratchet_inactive_returns_seriesSearch_per_series_in_oldest_air_date_order() {
        val plan = buildP5RatchetPlan(inputs(
            records = listOf(
                rec(1L, 5, 1L, "2024-01-01"),
                rec(2L, 1, 2L, "2018-01-01"),
                rec(3L, 1, 3L, "2020-01-01"),
            ),
            active = false,
        ))
        // Oldest air date first: series 2 (2018) → series 3 (2020) → series 1 (2024)
        assertEquals(
            listOf(2L, 3L, 1L),
            plan.actions.map { it.seriesId },
        )
        assertTrue(plan.actions.all { it is RatchetAction.SeriesSearch })
        assertTrue(plan.cooldownWrites.isEmpty()) // no state writes when ratchet off
    }

    // ---------- happy path ----------

    @Test fun fresh_series_picks_lowest_season_with_seasonSearch() {
        val plan = buildP5RatchetPlan(inputs(
            records = listOf(
                rec(1L, 1, 11L), rec(1L, 1, 12L),
                rec(1L, 2, 21L),
                rec(1L, 3, 31L),
            ),
        ))
        assertEquals(1, plan.actions.size)
        val a = plan.actions.single()
        assertTrue(a is RatchetAction.SeasonSearch)
        assertEquals(1L, a.seriesId)
        assertEquals(1, (a as RatchetAction.SeasonSearch).seasonNumber)
        // First-ever attempt → row written with consecutive=0, count=2
        val w = plan.cooldownWrites.single()
        assertEquals(2, w.lastMissingCount)
        assertEquals(0, w.consecutiveEmptyAttempts)
    }

    @Test fun season_in_cooldown_skipped_picker_advances() {
        val plan = buildP5RatchetPlan(inputs(
            records = listOf(rec(1L, 1, 11L), rec(1L, 2, 21L)),
            cooldowns = listOf(attempt(1L, 1, lastAttemptedAt = hoursAgo(2))), // S1 still cooling
        ))
        val a = plan.actions.single() as RatchetAction.SeasonSearch
        assertEquals(2, a.seasonNumber)
    }

    @Test fun all_seasons_in_cooldown_emits_no_action() {
        val plan = buildP5RatchetPlan(inputs(
            records = listOf(rec(1L, 1, 11L), rec(1L, 2, 21L)),
            cooldowns = listOf(
                attempt(1L, 1, lastAttemptedAt = hoursAgo(2)),
                attempt(1L, 2, lastAttemptedAt = hoursAgo(2)),
            ),
        ))
        assertTrue(plan.actions.isEmpty())
        assertTrue(plan.cooldownWrites.isEmpty())
    }

    // ---------- escalation ladder ----------

    @Test fun no_progress_no_queue_increments_to_seasonSearch_retry() {
        val plan = buildP5RatchetPlan(inputs(
            records = listOf(rec(1L, 1, 11L), rec(1L, 1, 12L), rec(1L, 1, 13L)),
            cooldowns = listOf(attempt(1L, 1, lastAttemptedAt = hoursAgo(25), lastMissingCount = 3, consecutive = 0)),
        ))
        val a = plan.actions.single()
        assertTrue(a is RatchetAction.SeasonSearch, "counter=1 → SeasonSearch retry")
        val w = plan.cooldownWrites.single()
        assertEquals(1, w.consecutiveEmptyAttempts)
    }

    @Test fun counter_2_escalates_to_episodeSearch() {
        val plan = buildP5RatchetPlan(inputs(
            records = listOf(rec(1L, 1, 11L), rec(1L, 1, 12L)),
            cooldowns = listOf(attempt(1L, 1, lastAttemptedAt = hoursAgo(25), lastMissingCount = 2, consecutive = 1)),
        ))
        val a = plan.actions.single()
        assertTrue(a is RatchetAction.EpisodeSearch)
        assertEquals(setOf(11L, 12L), (a as RatchetAction.EpisodeSearch).episodeIds.toSet())
        assertEquals(2, plan.cooldownWrites.single().consecutiveEmptyAttempts)
    }

    @Test fun progress_resets_counter_to_zero() {
        val plan = buildP5RatchetPlan(inputs(
            records = listOf(rec(1L, 1, 11L)),  // 1 missing now
            cooldowns = listOf(attempt(1L, 1, lastAttemptedAt = hoursAgo(25), lastMissingCount = 5, consecutive = 3)),
        ))
        val a = plan.actions.single()
        assertTrue(a is RatchetAction.SeasonSearch, "counter reset → fresh SeasonSearch")
        assertEquals(0, plan.cooldownWrites.single().consecutiveEmptyAttempts)
    }

    @Test fun queue_presence_resets_counter_even_without_missing_drop() {
        val plan = buildP5RatchetPlan(inputs(
            records = listOf(rec(1L, 1, 11L), rec(1L, 1, 12L), rec(1L, 1, 13L)),
            queueHits = setOf(QueueSeasonHit(1L, 1)),
            cooldowns = listOf(attempt(1L, 1, lastAttemptedAt = hoursAgo(25), lastMissingCount = 3, consecutive = 4)),
        ))
        assertEquals(0, plan.cooldownWrites.single().consecutiveEmptyAttempts)
    }

    @Test fun threshold_reached_skips_into_long_cooldown() {
        // counter == threshold = 5; lastAttempt 25h ago → still inside long cooldown (168h)
        val plan = buildP5RatchetPlan(inputs(
            records = listOf(rec(1L, 1, 11L), rec(1L, 2, 21L)),
            cooldowns = listOf(attempt(1L, 1, lastAttemptedAt = hoursAgo(25), lastMissingCount = 1, consecutive = 5)),
        ))
        val a = plan.actions.single() as RatchetAction.SeasonSearch
        assertEquals(2, a.seasonNumber, "S1 in long cooldown → advance to S2")
    }

    @Test fun threshold_reached_long_cooldown_elapsed_runs_episodeSearch_again() {
        val plan = buildP5RatchetPlan(inputs(
            records = listOf(rec(1L, 1, 11L)),
            cooldowns = listOf(attempt(1L, 1, lastAttemptedAt = hoursAgo(200), lastMissingCount = 1, consecutive = 5)),
        ))
        // Long cooldown elapsed (200h > 168h); counter still high → EpisodeSearch
        val a = plan.actions.single()
        assertTrue(a is RatchetAction.EpisodeSearch)
    }

    // ---------- ordering + budget ----------

    @Test fun budget_caps_actions() {
        val plan = buildP5RatchetPlan(inputs(
            records = (1L..15L).map { rec(it, 1, it * 10) },
            budget = 5,
        ))
        assertEquals(5, plan.actions.size)
    }

    @Test fun one_season_per_series_per_sweep() {
        val plan = buildP5RatchetPlan(inputs(
            records = listOf(
                rec(1L, 1, 11L), rec(1L, 2, 21L), rec(1L, 3, 31L),
                rec(2L, 1, 12L), rec(2L, 2, 22L),
            ),
        ))
        assertEquals(2, plan.actions.size)
        // Each action is for a distinct series
        assertEquals(setOf(1L, 2L), plan.actions.map { it.seriesId }.toSet())
    }

    // ---------- specials ----------

    @Test fun specials_excluded_by_default() {
        val plan = buildP5RatchetPlan(inputs(
            records = listOf(rec(1L, 0, 1L)),
        ))
        assertTrue(plan.actions.isEmpty())
    }

    @Test fun specials_included_sorts_after_numbered_seasons() {
        val plan = buildP5RatchetPlan(inputs(
            records = listOf(rec(1L, 0, 1L), rec(1L, 2, 22L)),
            cfgOverride = cfg.copy(includeSpecials = true),
        ))
        val a = plan.actions.single() as RatchetAction.SeasonSearch
        assertEquals(2, a.seasonNumber, "Numbered seasons before specials")
    }

    @Test fun specials_only_with_includeSpecials_picks_S0() {
        val plan = buildP5RatchetPlan(inputs(
            records = listOf(rec(1L, 0, 1L)),
            cfgOverride = cfg.copy(includeSpecials = true),
        ))
        val a = plan.actions.single() as RatchetAction.SeasonSearch
        assertEquals(0, a.seasonNumber)
    }

    // ---------- per-series isolation ----------

    @Test fun series_X_progress_does_not_reset_series_Y_counter() {
        val plan = buildP5RatchetPlan(inputs(
            records = listOf(rec(1L, 1, 11L), rec(2L, 1, 21L), rec(2L, 1, 22L)),
            cooldowns = listOf(
                attempt(2L, 1, lastAttemptedAt = hoursAgo(25), lastMissingCount = 2, consecutive = 0),
            ),
        ))
        // Series 2 row exists with count=2 last time, count is still 2 now → counter increments
        val s2 = plan.cooldownWrites.first { it.seriesId == 2L }
        assertEquals(1, s2.consecutiveEmptyAttempts)
    }
}
