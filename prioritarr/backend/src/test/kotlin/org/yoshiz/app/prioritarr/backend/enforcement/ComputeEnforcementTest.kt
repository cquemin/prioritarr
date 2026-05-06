package org.yoshiz.app.prioritarr.backend.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals

class ComputeEnforcementTest {

    private fun dl(
        clientId: String,
        priority: Int,
        client: String = "qbit",
        seriesId: Long? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        state: ManagedState = ManagedState.RUNNING,
        etaSeconds: Long? = null,
    ) = ManagedDownloadView(
        client = client, clientId = clientId, priority = priority,
        seriesId = seriesId, seasonNumber = seasonNumber, episodeNumber = episodeNumber,
        state = state, etaSeconds = etaSeconds,
    )

    // ---------- Layer 1 — cross-band pause rules ----------

    @Test fun layer1_p1_active_defers_p4_and_p5() {
        val decisions = computeEnforcement(
            listOf(dl("p1", 1), dl("p4", 4), dl("p5", 5)),
            ComputeEnforcementContext(),
        )
        assertEquals(TargetState.ACTIVE, decisions["p1"]!!.targetState)
        assertEquals(TargetState.DEFERRED, decisions["p4"]!!.targetState)
        assertEquals(TargetState.DEFERRED, decisions["p5"]!!.targetState)
    }

    @Test fun layer1_p2_active_no_p1_defers_only_p5() {
        val decisions = computeEnforcement(
            listOf(dl("p2", 2), dl("p4", 4), dl("p5", 5)),
            ComputeEnforcementContext(),
        )
        assertEquals(TargetState.ACTIVE, decisions["p2"]!!.targetState)
        assertEquals(TargetState.ACTIVE, decisions["p4"]!!.targetState)
        assertEquals(TargetState.DEFERRED, decisions["p5"]!!.targetState)
    }

    @Test fun layer1_only_p3_through_p5_no_defers() {
        val decisions = computeEnforcement(
            listOf(dl("p3", 3), dl("p4", 4), dl("p5", 5)),
            ComputeEnforcementContext(),
        )
        decisions.values.forEach { assertEquals(TargetState.ACTIVE, it.targetState) }
    }

    @Test fun layer1_paused_by_user_does_not_count_as_active_band_member() {
        // P1 only "active" via PAUSED_BY_USER → P1 doesn't count → no defer
        val decisions = computeEnforcement(
            listOf(
                dl("p1", 1, state = ManagedState.PAUSED_BY_USER),
                dl("p5", 5),
            ),
            ComputeEnforcementContext(),
        )
        assertEquals(TargetState.ACTIVE, decisions["p5"]!!.targetState)
    }

    @Test fun layer1_p1_peer_limited_skips_layer1_defer() {
        // P1 is peer-limited → freeing bandwidth on P5 won't help → don't defer P5
        val decisions = computeEnforcement(
            listOf(dl("p1", 1), dl("p5", 5)),
            ComputeEnforcementContext(bandwidthAwareEnabled = true, bandwidthSaturated = true, p1IsPeerLimited = true),
        )
        assertEquals(TargetState.ACTIVE, decisions["p5"]!!.targetState)
    }

    @Test fun layer1_near_done_p5_skips_defer() {
        val nearDone = dl("p5", 5)
        val decisions = computeEnforcement(
            listOf(dl("p1", 1), nearDone),
            ComputeEnforcementContext(bandwidthAwareEnabled = true, bandwidthSaturated = true, isNearDone = { it.clientId == "p5" }),
        )
        assertEquals(TargetState.ACTIVE, decisions["p5"]!!.targetState)
    }

    // ---------- Layer 2 — P5 sub-band ----------

    @Test fun layer2_off_when_ratchet_inactive() {
        // Same series, two seasons, P5 — ratchet inactive → both ACTIVE
        val decisions = computeEnforcement(
            listOf(
                dl("s1", 5, seriesId = 1L, seasonNumber = 1),
                dl("s2", 5, seriesId = 1L, seasonNumber = 2),
            ),
            ComputeEnforcementContext(p5SeasonRatchetActive = false),
        )
        assertEquals(TargetState.ACTIVE, decisions["s1"]!!.targetState)
        assertEquals(TargetState.ACTIVE, decisions["s2"]!!.targetState)
    }

    @Test fun layer2_per_series_lowest_season_active_higher_deferred() {
        val decisions = computeEnforcement(
            listOf(
                dl("s1e1", 5, seriesId = 1L, seasonNumber = 1, episodeNumber = 1),
                dl("s1e2", 5, seriesId = 1L, seasonNumber = 1, episodeNumber = 2),
                dl("s2e1", 5, seriesId = 1L, seasonNumber = 2, episodeNumber = 1),
                dl("s3e1", 5, seriesId = 1L, seasonNumber = 3, episodeNumber = 1),
            ),
            ComputeEnforcementContext(p5SeasonRatchetActive = true),
        )
        assertEquals(TargetState.ACTIVE, decisions["s1e1"]!!.targetState)
        assertEquals(TargetState.ACTIVE, decisions["s1e2"]!!.targetState)
        assertEquals(TargetState.DEFERRED, decisions["s2e1"]!!.targetState)
        assertEquals(TargetState.DEFERRED, decisions["s3e1"]!!.targetState)
    }

    @Test fun layer2_two_series_independent_min_seasons() {
        val decisions = computeEnforcement(
            listOf(
                dl("a-s2", 5, seriesId = 1L, seasonNumber = 2),
                dl("a-s3", 5, seriesId = 1L, seasonNumber = 3),
                dl("b-s5", 5, seriesId = 2L, seasonNumber = 5),
                dl("b-s6", 5, seriesId = 2L, seasonNumber = 6),
            ),
            ComputeEnforcementContext(p5SeasonRatchetActive = true),
        )
        // A's min=2 active, A's S3 deferred. B's min=5 active, B's S6 deferred.
        assertEquals(TargetState.ACTIVE, decisions["a-s2"]!!.targetState)
        assertEquals(TargetState.DEFERRED, decisions["a-s3"]!!.targetState)
        assertEquals(TargetState.ACTIVE, decisions["b-s5"]!!.targetState)
        assertEquals(TargetState.DEFERRED, decisions["b-s6"]!!.targetState)
    }

    @Test fun layer2_null_seasonNumber_stays_active() {
        val decisions = computeEnforcement(
            listOf(
                dl("known", 5, seriesId = 1L, seasonNumber = 1),
                dl("unknown", 5, seriesId = 1L, seasonNumber = null),
            ),
            ComputeEnforcementContext(p5SeasonRatchetActive = true),
        )
        assertEquals(TargetState.ACTIVE, decisions["known"]!!.targetState)
        assertEquals(TargetState.ACTIVE, decisions["unknown"]!!.targetState)
    }

    @Test fun layer2_null_seriesId_skips_rule() {
        val decisions = computeEnforcement(
            listOf(
                dl("orphan-s2", 5, seriesId = null, seasonNumber = 2),
                dl("orphan-s5", 5, seriesId = null, seasonNumber = 5),
            ),
            ComputeEnforcementContext(p5SeasonRatchetActive = true),
        )
        // No seriesId → no grouping → both ACTIVE
        assertEquals(TargetState.ACTIVE, decisions["orphan-s2"]!!.targetState)
        assertEquals(TargetState.ACTIVE, decisions["orphan-s5"]!!.targetState)
    }

    @Test fun layer2_does_not_touch_non_p5() {
        val decisions = computeEnforcement(
            listOf(
                dl("p3-s1", 3, seriesId = 1L, seasonNumber = 1),
                dl("p3-s5", 3, seriesId = 1L, seasonNumber = 5),
            ),
            ComputeEnforcementContext(p5SeasonRatchetActive = true),
        )
        assertEquals(TargetState.ACTIVE, decisions["p3-s1"]!!.targetState)
        assertEquals(TargetState.ACTIVE, decisions["p3-s5"]!!.targetState)
    }

    @Test fun layer2_excludes_paused_user_from_min_calc() {
        // S1 is paused-by-user; the "effective" min should be S2.
        // Only S3+ get deferred; S1 stays untouched (PAUSED_BY_USER).
        val decisions = computeEnforcement(
            listOf(
                dl("s1", 5, seriesId = 1L, seasonNumber = 1, state = ManagedState.PAUSED_BY_USER),
                dl("s2", 5, seriesId = 1L, seasonNumber = 2),
                dl("s3", 5, seriesId = 1L, seasonNumber = 3),
            ),
            ComputeEnforcementContext(p5SeasonRatchetActive = true),
        )
        assertEquals(TargetState.ACTIVE, decisions["s1"]!!.targetState)  // user-paused = ACTIVE target, never touch
        assertEquals(TargetState.ACTIVE, decisions["s2"]!!.targetState)
        assertEquals(TargetState.DEFERRED, decisions["s3"]!!.targetState)
    }

    @Test fun layer2_layer1_wins_when_both_apply() {
        // P1 is active in some other series → Layer 1 defers all P5 anyway
        val decisions = computeEnforcement(
            listOf(
                dl("p1", 1, seriesId = 9L),
                dl("s1", 5, seriesId = 1L, seasonNumber = 1),
                dl("s2", 5, seriesId = 1L, seasonNumber = 2),
            ),
            ComputeEnforcementContext(p5SeasonRatchetActive = true),
        )
        assertEquals(TargetState.DEFERRED, decisions["s1"]!!.targetState)
        assertEquals(TargetState.DEFERRED, decisions["s2"]!!.targetState)
    }

    // ---------- orderHint ----------

    @Test fun orderHint_priority_dominates_season() {
        val p1 = dl("p1", 1, seasonNumber = 9, episodeNumber = 99)
        val p5 = dl("p5", 5, seasonNumber = 1, episodeNumber = 1)
        val decisions = computeEnforcement(listOf(p1, p5), ComputeEnforcementContext())
        // Lower hint = earlier; P1 must outrank P5 regardless of season
        kotlin.test.assertTrue(decisions["p1"]!!.orderHint < decisions["p5"]!!.orderHint)
    }

    @Test fun orderHint_within_priority_uses_season_then_episode() {
        val s1e2 = dl("s1e2", 5, seasonNumber = 1, episodeNumber = 2)
        val s1e1 = dl("s1e1", 5, seasonNumber = 1, episodeNumber = 1)
        val s2e1 = dl("s2e1", 5, seasonNumber = 2, episodeNumber = 1)
        val decisions = computeEnforcement(listOf(s1e2, s1e1, s2e1), ComputeEnforcementContext())
        val sorted = listOf("s1e2", "s1e1", "s2e1").sortedBy { decisions[it]!!.orderHint }
        kotlin.test.assertEquals(listOf("s1e1", "s1e2", "s2e1"), sorted)
    }

    // ---------- bandwidth-aware gating ----------

    @Test fun layer1_bandwidth_aware_enabled_but_not_saturated_does_not_defer() {
        val decisions = computeEnforcement(
            listOf(dl("p1", 1), dl("p5", 5)),
            ComputeEnforcementContext(bandwidthAwareEnabled = true, bandwidthSaturated = false),
        )
        // Plenty of headroom → P5 keeps running even though P1 is active.
        assertEquals(TargetState.ACTIVE, decisions["p5"]!!.targetState)
    }

    @Test fun layer1_bandwidth_aware_enabled_and_saturated_defers() {
        val decisions = computeEnforcement(
            listOf(dl("p1", 1), dl("p5", 5)),
            ComputeEnforcementContext(bandwidthAwareEnabled = true, bandwidthSaturated = true),
        )
        assertEquals(TargetState.DEFERRED, decisions["p5"]!!.targetState)
    }

    @Test fun layer2_near_done_higher_season_skips_defer() {
        // P5 S3 is 95% done; ratchet would normally defer it, but
        // close-to-finish wins.
        val decisions = computeEnforcement(
            listOf(
                dl("s1", 5, seriesId = 1L, seasonNumber = 1),
                dl("s3-near", 5, seriesId = 1L, seasonNumber = 3),
            ),
            ComputeEnforcementContext(
                p5SeasonRatchetActive = true,
                isNearDone = { it.clientId == "s3-near" },
            ),
        )
        assertEquals(TargetState.ACTIVE, decisions["s1"]!!.targetState)
        assertEquals(TargetState.ACTIVE, decisions["s3-near"]!!.targetState)
    }
}
