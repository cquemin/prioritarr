package org.yoshiz.app.prioritarr.backend.sweep

import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

/**
 * Execute a [P5RatchetPlan] against Sonarr + persist the cooldown
 * writes. Returns the number of search commands actually fired —
 * caller subtracts from the shared sweep budget.
 *
 * Plan execution is best-effort: a Sonarr error on action N stops the
 * loop and leaves cooldownWrites for unpicked seasons un-applied.
 * That's intentional — failing fast lets the next sweep retry without
 * polluting the cooldown table with attempts that may not have
 * actually hit indexers.
 */
suspend fun runP5SeasonRatchet(
    plan: P5RatchetPlan,
    sonarr: org.yoshiz.app.prioritarr.backend.clients.SonarrClient,
    db: Database,
    delaySeconds: Int,
    dryRun: Boolean,
): Int {
    if (plan.actions.isEmpty()) return 0
    var fired = 0
    val logger = org.slf4j.LoggerFactory.getLogger("p5-ratchet")
    var seasonSearches = 0
    var episodeSearches = 0
    var seriesSearches = 0
    val firstWritesByAction = plan.actions.zip(plan.cooldownWrites).toMap()

    for (action in plan.actions) {
        val attemptStrategy = when (action) {
            is RatchetAction.SeasonSearch -> "season"
            is RatchetAction.EpisodeSearch -> "episode"
            is RatchetAction.SeriesSearch -> "series"
        }
        if (dryRun) {
            logger.info("[p5-ratchet] DRY RUN: would {} for series {} action={}",
                attemptStrategy, action.seriesId, action::class.simpleName)
        } else {
            try {
                when (action) {
                    is RatchetAction.SeasonSearch ->
                        sonarr.triggerSeasonSearch(action.seriesId, action.seasonNumber).also { seasonSearches++ }
                    is RatchetAction.EpisodeSearch ->
                        sonarr.triggerEpisodeSearch(action.episodeIds).also { episodeSearches++ }
                    is RatchetAction.SeriesSearch ->
                        sonarr.triggerSeriesSearch(action.seriesId).also { seriesSearches++ }
                }
            } catch (e: Exception) {
                logger.warn("[p5-ratchet] {} for series {} failed: {}", attemptStrategy, action.seriesId, e.message)
                break
            }
            firstWritesByAction[action]?.let { write ->
                db.upsertP5Attempt(
                    seriesId = write.seriesId,
                    seasonNumber = write.seasonNumber,
                    lastAttemptedAt = write.lastAttemptedAt,
                    lastMissingCount = write.lastMissingCount,
                    consecutiveEmptyAttempts = write.consecutiveEmptyAttempts,
                )
            }
            db.appendAudit(
                action = "p5_ratchet_search",
                seriesId = action.seriesId,
                details = buildJsonObject {
                    put("strategy", attemptStrategy)
                    when (action) {
                        is RatchetAction.SeasonSearch ->
                            put("season_number", action.seasonNumber)
                        is RatchetAction.EpisodeSearch -> {
                            put("season_number", action.seasonNumber)
                            put("episode_count", action.episodeIds.size)
                        }
                        is RatchetAction.SeriesSearch -> { /* nothing extra */ }
                    }
                },
            )
            if (delaySeconds > 0) delay(delaySeconds * 1_000L)
        }
        fired++
    }
    logger.info("[p5-ratchet] sweep: {} actions ({} season + {} episode + {} series)",
        fired, seasonSearches, episodeSearches, seriesSearches)
    return fired
}
