package org.yoshiz.app.prioritarr.backend.sweep

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.database.Database
import org.yoshiz.app.prioritarr.backend.priority.PriorityResult
import org.yoshiz.app.prioritarr.backend.priority.PriorityService

private val logger = LoggerFactory.getLogger("org.yoshiz.app.prioritarr.backend.sweep")

/**
 * Maximum number of oldest missing episodes to fire EpisodeSearch for
 * per P1/P2 series per sweep. Hardcoded — see the spec for rationale
 * (operator value in surfacing as a setting is low).
 */
private const val P1P2_PER_SERIES_CAP = 5

internal data class SweepEntry(
    val seriesId: Long,
    val oldestAirDate: String,
    var priority: Int = 99,
    var label: String = "",
)

/**
 * Group missing-episode records by series, attach a computed priority,
 * sort by priority asc then oldest airDateUtc asc. Mirrors
 * sweep.py::build_sweep_order.
 */
internal suspend fun buildSweepOrder(
    records: JsonArray,
    priorityFn: suspend (Long) -> PriorityResult,
): List<SweepEntry> {
    val map = mutableMapOf<Long, SweepEntry>()
    for (r in records) {
        val obj = r.jsonObject
        val sid = obj["seriesId"]?.jsonPrimitive?.longOrNull ?: continue
        val air = obj["airDateUtc"]?.jsonPrimitive?.contentOrNull ?: "9999"
        val existing = map[sid]
        if (existing == null) {
            map[sid] = SweepEntry(sid, air)
        } else if (air < existing.oldestAirDate) {
            map[sid] = existing.copy(oldestAirDate = air)
        }
    }
    for (entry in map.values) {
        val result = priorityFn(entry.seriesId)
        entry.priority = result.priority
        entry.label = result.label
    }
    return map.values.sortedWith(compareBy({ it.priority }, { it.oldestAirDate }))
}

/** Overload retained for callers that pass a [PriorityService] directly. */
internal suspend fun buildSweepOrder(
    records: JsonArray,
    priorityService: PriorityService,
): List<SweepEntry> = buildSweepOrder(records, priorityService::priorityForSeries)

internal suspend fun runBackfillSweep(
    sonarr: SonarrClient,
    db: Database,
    p5Ratchet: org.yoshiz.app.prioritarr.backend.config.P5RatchetConfig,
    bandwidth: org.yoshiz.app.prioritarr.backend.config.BandwidthSettings,
    telemetry: org.yoshiz.app.prioritarr.backend.enforcement.DownloadTelemetry?,
    maxSearches: Int,
    delaySeconds: Int,
    dryRun: Boolean,
    p1p2MaxPerSweep: Int,
    p1p2CooldownMinutes: Int,
    // Test seam: allows unit tests to supply a simple lambda without
    // constructing a full PriorityService (which needs SonarrClient,
    // watch providers, DB, etc.). Production callers use the overload
    // below that accepts a PriorityService directly.
    priorityForSeriesFn: suspend (Long) -> PriorityResult,
): Int {
    val records = try { sonarr.getWantedMissing() } catch (e: Exception) {
        logger.warn("[backfill] fetch failed: {}", e.message); return 0
    }
    if (records.isEmpty()) {
        logger.info("[backfill] nothing to search")
        return 0
    }

    val order = buildSweepOrder(records, priorityForSeriesFn)
    val priorityBySeriesId = order.associate { it.seriesId to it.priority }
    val queueArr = try { sonarr.getQueue() } catch (_: Exception) { JsonArray(emptyList()) }
    val queuedIds = queueArr.toEpisodeIdSet()
    val nowSec = System.currentTimeMillis() / 1000L
    val cooldownIds = db.listP1P2AttemptedSince(nowSec - p1p2CooldownMinutes * 60L).toSet()

    logger.info(
        "[backfill] {} records / {} series; queue={}, cooldown={}, p1p2_budget={}, p3p4_budget={}",
        records.size, order.size, queuedIds.size, cooldownIds.size, p1p2MaxPerSweep, maxSearches,
    )

    // ---- Pass A1: P1/P2 episode-level ----
    val p1p2 = buildP1P2Candidates(
        records = records,
        priorityBySeriesId = priorityBySeriesId,
        queuedEpisodeIds = queuedIds,
        cooldownEpisodeIds = cooldownIds,
        perSeriesCap = P1P2_PER_SERIES_CAP,
    )
    val p1p2Fired = if (p1p2MaxPerSweep > 0) {
        runP1P2EpisodePass(
            candidates = p1p2,
            sonarr = sonarr, db = db,
            budget = p1p2MaxPerSweep,
            delaySeconds = delaySeconds,
            dryRun = dryRun,
            nowEpochSeconds = nowSec,
        )
    } else 0

    // ---- Pass A2: P3/P4 series-level ----
    var fired = 0
    val passA2 = order.filter { it.priority in 3..4 }
    for (entry in passA2) {
        if (fired >= maxSearches) {
            logger.info("[backfill] hit max searches ({}), deferring rest", maxSearches)
            break
        }
        if (dryRun) {
            logger.info("[backfill] DRY RUN: would series-search {} ({})", entry.seriesId, entry.label)
        } else {
            try { sonarr.triggerSeriesSearch(entry.seriesId) }
            catch (e: Exception) {
                logger.warn("[backfill] series-search failed for {}: {}", entry.seriesId, e.message); break
            }
            logger.info("[backfill] triggered: series {} ({})", entry.seriesId, entry.label)
            if (delaySeconds > 0) delay(delaySeconds * 1_000L)
        }
        fired++
    }

    // ---- Pass B: P5 ratchet (unchanged) ----
    if (fired >= maxSearches) return p1p2Fired + fired
    val p5Entries = order.filter { it.priority == 5 }
    if (p5Entries.isEmpty()) return p1p2Fired + fired

    val ratchetActive = p5Ratchet.enabled && run {
        if (bandwidth.maxMbps <= 0) return@run false
        val totalBps = telemetry?.observedPeakTotalBps() ?: 0L
        val ratchetBandwidth = p5Ratchet.bandwidthThresholdPct?.let {
            bandwidth.copy(utilisationThresholdPct = it)
        } ?: bandwidth
        org.yoshiz.app.prioritarr.backend.enforcement.BandwidthPolicy
            .utilisationExceedsThreshold(ratchetBandwidth, totalBps)
    }

    val p5SeriesIds = p5Entries.map { it.seriesId }.toSet()

    // Re-walk the raw `records` JsonArray to extract per-episode rows
    // for each P5 series. SweepEntry collapses to one row per series;
    // the planner needs season+episode fan-out.
    val p5Records: List<MissingRecord> = records.mapNotNull { el ->
        val o = el.jsonObject
        val sid = o["seriesId"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        if (sid !in p5SeriesIds) return@mapNotNull null
        val season = o["seasonNumber"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
        val episodeId = o["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        val air = o["airDateUtc"]?.jsonPrimitive?.contentOrNull ?: "9999"
        MissingRecord(sid, season, episodeId, air, priority = 5)
    }.distinctBy { it.episodeId }

    val queueHits: Set<QueueSeasonHit> = queueArr.mapNotNull {
        val o = it.jsonObject
        val sid = o["seriesId"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        val season = o["episode"]?.jsonObject?.get("seasonNumber")?.jsonPrimitive?.intOrNull
            ?: o["seasonNumber"]?.jsonPrimitive?.intOrNull
            ?: return@mapNotNull null
        QueueSeasonHit(sid, season)
    }.toSet()

    val planInputs = P5RatchetInputs(
        p5Records = p5Records, queueHits = queueHits,
        cooldownRows = db.listP5Attempts(),
        nowEpochSeconds = nowSec, cfg = p5Ratchet,
        budgetRemaining = maxSearches - fired, ratchetActive = ratchetActive,
    )
    val plan = buildP5RatchetPlan(planInputs)
    fired += runP5SeasonRatchet(plan = plan, sonarr = sonarr, db = db, delaySeconds = delaySeconds, dryRun = dryRun)
    return p1p2Fired + fired
}

/**
 * Pull episode IDs out of Sonarr's /queue payload. Handles both response
 * shapes: rows with an `episode` object containing `id`, or flat rows with
 * a top-level `episodeId`. Used by Pass A1 (filter out queued episodes
 * from P1/P2 candidates) and the on-grab follow-up in
 * `webhooks/OnGrabFollowup.kt`. Lives here for now since `sweep` was the
 * first caller; if a third caller appears in another package, consider
 * moving to a shared util.
 */
internal fun JsonArray.toEpisodeIdSet(): Set<Long> = mapNotNull {
    val o = it.jsonObject
    o["episode"]?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull
        ?: o["episodeId"]?.jsonPrimitive?.longOrNull
}.toSet()

/**
 * Convenience overload for production callers that hold a [PriorityService].
 * Delegates to the lambda-based overload using [PriorityService.priorityForSeries].
 */
suspend fun runBackfillSweep(
    sonarr: SonarrClient,
    priorityService: PriorityService,
    db: Database,
    p5Ratchet: org.yoshiz.app.prioritarr.backend.config.P5RatchetConfig,
    bandwidth: org.yoshiz.app.prioritarr.backend.config.BandwidthSettings,
    telemetry: org.yoshiz.app.prioritarr.backend.enforcement.DownloadTelemetry?,
    maxSearches: Int,
    delaySeconds: Int,
    dryRun: Boolean,
    p1p2MaxPerSweep: Int,
    p1p2CooldownMinutes: Int,
): Int = runBackfillSweep(
    sonarr = sonarr,
    db = db,
    p5Ratchet = p5Ratchet,
    bandwidth = bandwidth,
    telemetry = telemetry,
    maxSearches = maxSearches,
    delaySeconds = delaySeconds,
    dryRun = dryRun,
    p1p2MaxPerSweep = p1p2MaxPerSweep,
    p1p2CooldownMinutes = p1p2CooldownMinutes,
    priorityForSeriesFn = priorityService::priorityForSeries,
)

suspend fun runCutoffSweep(
    sonarr: SonarrClient,
    priorityService: PriorityService,
    maxSearches: Int,
    delaySeconds: Int,
    dryRun: Boolean,
): Int = runSweep(
    name = "cutoff",
    fetch = { sonarr.getWantedCutoff() },
    trigger = { sonarr.triggerCutoffSearch(it) },
    priorityService = priorityService,
    maxSearches = maxSearches,
    delaySeconds = delaySeconds,
    dryRun = dryRun,
)

private suspend fun runSweep(
    name: String,
    fetch: suspend () -> JsonArray,
    trigger: suspend (Long) -> Any,
    priorityService: PriorityService,
    maxSearches: Int,
    delaySeconds: Int,
    dryRun: Boolean,
): Int {
    val records = try { fetch() } catch (e: Exception) {
        logger.warn("[{}] fetch failed: {}", name, e.message); return 0
    }
    if (records.isEmpty()) {
        logger.info("[{}] nothing to search", name)
        return 0
    }
    val order = buildSweepOrder(records, priorityService)
    logger.info("[{}] {} records across {} series (max {})", name, records.size, order.size, maxSearches)
    var searched = 0
    for (entry in order) {
        if (searched >= maxSearches) {
            logger.info("[{}] hit max searches ({}), deferring rest", name, maxSearches)
            break
        }
        if (dryRun) {
            logger.info("[{}] DRY RUN: would search series {} ({})", name, entry.seriesId, entry.label)
        } else {
            try {
                trigger(entry.seriesId)
                logger.info("[{}] triggered: series {} ({})", name, entry.seriesId, entry.label)
            } catch (e: Exception) {
                logger.warn("[{}] search failed for series {}: {}", name, entry.seriesId, e.message)
                break
            }
            if (delaySeconds > 0) delay(delaySeconds * 1_000L)
        }
        searched++
    }
    logger.info("[{}] sweep complete: {}/{} searched", name, searched, order.size)
    return searched
}
