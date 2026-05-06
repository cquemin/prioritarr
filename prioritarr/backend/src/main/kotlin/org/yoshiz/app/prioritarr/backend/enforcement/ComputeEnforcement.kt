package org.yoshiz.app.prioritarr.backend.enforcement

/**
 * Pure decision function over the unified [ManagedDownloadView] list.
 * Two layers:
 *   1. Cross-band pause rules (existing pause-band semantics).
 *   2. P5 sub-band rule (per-series earlier-season-first, only when
 *      [ComputeEnforcementContext.p5SeasonRatchetActive] is true).
 *
 * Returns a per-clientId decision; downstream client adapters
 * translate to native API calls (qBit pause/resume + setTopPriority,
 * SAB priority bucket + queue/switch).
 */
fun computeEnforcement(
    downloads: List<ManagedDownloadView>,
    ctx: ComputeEnforcementContext,
): Map<String, EnforcementDecision> {
    val result = LinkedHashMap<String, EnforcementDecision>(downloads.size)

    // ---------- Layer 1: cross-band ----------
    val crossBandActives = downloads
        .filter { it.state == ManagedState.RUNNING }
        .map { it.priority }
        .toSet()
    val hasP1 = 1 in crossBandActives
    val hasP2 = 2 in crossBandActives
    val crossBandDeferLevels: Set<Int> = when {
        hasP1 -> setOf(4, 5)
        hasP2 -> setOf(5)
        else -> emptySet()
    }

    // ---------- Layer 2: P5 sub-band, per-series ----------
    // For each P5 series, find the minimum seasonNumber among items
    // that are RUNNING and have a known seasonNumber. Items with a
    // greater seasonNumber and matching seriesId become DEFERRED.
    // Items with seasonNumber == null or seriesId == null are not
    // affected by Layer 2 (they fall through ACTIVE).
    val p5MinSeasonBySeries: Map<Long, Int> =
        if (!ctx.p5SeasonRatchetActive) emptyMap()
        else downloads
            .asSequence()
            .filter { it.priority == 5 }
            .filter { it.seriesId != null && it.seasonNumber != null }
            .filter { it.state == ManagedState.RUNNING }
            .groupBy { it.seriesId!! }
            .mapValues { (_, items) -> items.minOf { it.seasonNumber!! } }

    for (d in downloads) {
        // Layer 1 first
        val deferByCrossBand = d.priority in crossBandDeferLevels &&
            d.state == ManagedState.RUNNING &&
            (
                !ctx.bandwidthAwareEnabled ||
                    (ctx.bandwidthSaturated && !ctx.p1IsPeerLimited && !ctx.isNearDone(d))
            )

        // Layer 2 only kicks in when ratchet is active AND Layer 1
        // didn't already defer the item AND the item is RUNNING
        // (PAUSED_BY_USER / ERRORED stay ACTIVE-target as a no-op signal)
        val deferByP5SubBand = !deferByCrossBand &&
            ctx.p5SeasonRatchetActive &&
            d.priority == 5 &&
            d.state == ManagedState.RUNNING &&
            !ctx.isNearDone(d) &&
            d.seriesId != null &&
            d.seasonNumber != null &&
            p5MinSeasonBySeries[d.seriesId]?.let { min -> d.seasonNumber > min } == true

        val target = if (deferByCrossBand || deferByP5SubBand) TargetState.DEFERRED else TargetState.ACTIVE
        result[d.clientId] = EnforcementDecision(
            targetState = target,
            orderHint = orderHintOf(d),
        )
    }
    return result
}

internal fun orderHintOf(d: ManagedDownloadView): Int =
    d.priority * 1_000_000 +
        (d.seasonNumber ?: 99) * 1_000 +
        (d.episodeNumber ?: 0)
