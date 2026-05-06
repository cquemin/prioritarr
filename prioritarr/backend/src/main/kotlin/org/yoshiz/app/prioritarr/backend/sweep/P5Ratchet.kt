package org.yoshiz.app.prioritarr.backend.sweep

import org.yoshiz.app.prioritarr.backend.config.P5RatchetConfig
import org.yoshiz.app.prioritarr.backend.database.Database

/** Per-(series, season) attempt history, mirrors Database.P5SweepAttempt. */
typealias P5Attempt = Database.P5SweepAttempt

/**
 * One missing-episode record fed to the planner — stripped down from
 * Sonarr's /wanted/missing payload to the fields the picker actually
 * uses. Built by the runner before calling [buildP5RatchetPlan].
 */
data class MissingRecord(
    val seriesId: Long,
    val seasonNumber: Int,
    val episodeId: Long,
    val airDateUtc: String,            // ISO date or "9999" for sort fallback
    val priority: Int,                 // recompute-time band (only priority=5 reaches this planner)
)

/**
 * Sonarr /queue cross-ref needed to detect "queue presence" without
 * a separate fetch. The planner only cares about
 * `(seriesId, seasonNumber)` → has-queue-entry?; the runner builds it.
 */
data class QueueSeasonHit(val seriesId: Long, val seasonNumber: Int)

data class P5RatchetInputs(
    val p5Records: List<MissingRecord>,
    val queueHits: Set<QueueSeasonHit>,
    val cooldownRows: List<P5Attempt>,
    val nowEpochSeconds: Long,
    val cfg: P5RatchetConfig,
    val budgetRemaining: Int,
    val ratchetActive: Boolean,
)

sealed interface RatchetAction {
    val seriesId: Long
    data class SeasonSearch(override val seriesId: Long, val seasonNumber: Int) : RatchetAction
    data class EpisodeSearch(
        override val seriesId: Long,
        val seasonNumber: Int,
        val episodeIds: List<Long>,
    ) : RatchetAction
    data class SeriesSearch(override val seriesId: Long) : RatchetAction
}

data class P5RatchetPlan(
    val actions: List<RatchetAction>,
    val cooldownWrites: List<P5Attempt>,
)

/** Pure decision function. See spec §Algorithms. */
fun buildP5RatchetPlan(inputs: P5RatchetInputs): P5RatchetPlan {
    if (!inputs.ratchetActive) {
        // Bandwidth headroom: today's behaviour, expressed as
        // SeriesSearch per series in oldest-air-date order. Up to budget.
        val byOldest = inputs.p5Records
            .groupBy { it.seriesId }
            .mapValues { (_, recs) -> recs.minOf { it.airDateUtc } }
        val ordered = byOldest.entries.sortedBy { it.value }.map { it.key }
        val actions = ordered.take(inputs.budgetRemaining)
            .map { RatchetAction.SeriesSearch(it) as RatchetAction }
        return P5RatchetPlan(actions = actions, cooldownWrites = emptyList())
    }

    val cooldownIndex = inputs.cooldownRows
        .associateBy { it.seriesId to it.seasonNumber }

    // Group missing records by series → season → list-of-records
    val bySeries: Map<Long, Map<Int, List<MissingRecord>>> = inputs.p5Records
        .groupBy { it.seriesId }
        .mapValues { (_, recs) -> recs.groupBy { it.seasonNumber } }

    // Series order: oldest air date first (consistent with the ratchet-off path)
    val seriesOrder = bySeries.entries.sortedBy { (_, seasons) ->
        seasons.values.flatten().minOf { it.airDateUtc }
    }.map { it.key }

    val cooldownSeconds = inputs.cfg.searchCooldownHours * 3600L
    val longCooldownSeconds = inputs.cfg.longCooldownHours * 3600L
    val now = inputs.nowEpochSeconds

    val actions = mutableListOf<RatchetAction>()
    val writes = mutableListOf<P5Attempt>()
    var budget = inputs.budgetRemaining

    seriesLoop@ for (seriesId in seriesOrder) {
        if (budget == 0) break
        val seasonsMap = bySeries[seriesId] ?: continue

        // Sort seasons: numbered ascending; specials (0) last when included; excluded otherwise
        val seasonOrder = seasonsMap.keys
            .filter { inputs.cfg.includeSpecials || it != 0 }
            .sortedWith(compareBy(
                { if (it == 0) 1 else 0 },  // 0=specials sort after numbered
                { it },
            ))

        for (season in seasonOrder) {
            val key = seriesId to season
            val prev = cooldownIndex[key]
            val missingNow = seasonsMap[season]!!.size
            val queuePresent = QueueSeasonHit(seriesId, season) in inputs.queueHits

            // ---- short cooldown gate ----
            if (prev != null && prev.lastAttemptedAt + cooldownSeconds > now) continue

            // ---- compute next counter (progress detection) ----
            val nextCounter = when {
                prev == null -> 0
                prev.lastMissingCount == null -> 0
                missingNow < prev.lastMissingCount || queuePresent -> 0
                else -> prev.consecutiveEmptyAttempts + 1
            }

            // ---- long cooldown gate ----
            if (nextCounter >= inputs.cfg.escalationThreshold &&
                prev != null &&
                prev.lastAttemptedAt + longCooldownSeconds > now
            ) continue

            // ---- pick strategy ----
            val action: RatchetAction = when {
                nextCounter == 0 -> RatchetAction.SeasonSearch(seriesId, season)
                nextCounter == 1 -> RatchetAction.SeasonSearch(seriesId, season)
                else -> RatchetAction.EpisodeSearch(
                    seriesId = seriesId,
                    seasonNumber = season,
                    episodeIds = seasonsMap[season]!!.map { it.episodeId },
                )
            }
            actions += action
            writes += P5Attempt(
                seriesId = seriesId,
                seasonNumber = season,
                lastAttemptedAt = now,
                lastMissingCount = missingNow,
                consecutiveEmptyAttempts = nextCounter,
            )
            budget--
            continue@seriesLoop  // one season per series per sweep
        }
    }

    return P5RatchetPlan(actions = actions, cooldownWrites = writes)
}
