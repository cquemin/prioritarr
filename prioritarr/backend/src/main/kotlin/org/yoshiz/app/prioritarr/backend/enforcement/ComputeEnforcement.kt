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

    for (d in downloads) {
        val deferByCrossBand = d.priority in crossBandDeferLevels &&
            !ctx.p1IsPeerLimited &&
            !ctx.isNearDone(d) &&
            d.state == ManagedState.RUNNING

        val target = if (deferByCrossBand) TargetState.DEFERRED else TargetState.ACTIVE
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
