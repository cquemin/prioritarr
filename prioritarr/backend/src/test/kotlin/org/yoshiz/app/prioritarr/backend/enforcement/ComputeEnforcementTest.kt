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
            ComputeEnforcementContext(p1IsPeerLimited = true),
        )
        assertEquals(TargetState.ACTIVE, decisions["p5"]!!.targetState)
    }

    @Test fun layer1_near_done_p5_skips_defer() {
        val nearDone = dl("p5", 5)
        val decisions = computeEnforcement(
            listOf(dl("p1", 1), nearDone),
            ComputeEnforcementContext(isNearDone = { it.clientId == "p5" }),
        )
        assertEquals(TargetState.ACTIVE, decisions["p5"]!!.targetState)
    }
}
