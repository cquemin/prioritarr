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
import org.yoshiz.app.prioritarr.backend.priority.PriorityService

private val logger = LoggerFactory.getLogger("org.yoshiz.app.prioritarr.backend.sweep")

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
    priorityService: PriorityService,
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
        val result = priorityService.priorityForSeries(entry.seriesId)
        entry.priority = result.priority
        entry.label = result.label
    }
    return map.values.sortedWith(compareBy({ it.priority }, { it.oldestAirDate }))
}

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
): Int {
    // ---- Pass A: P1-P4 today's behaviour ----
    val records = try { sonarr.getWantedMissing() } catch (e: Exception) {
        logger.warn("[backfill] fetch failed: {}", e.message); return 0
    }
    if (records.isEmpty()) {
        logger.info("[backfill] nothing to search")
        return 0
    }
    val order = buildSweepOrder(records, priorityService)
    logger.info("[backfill] {} records across {} series (max {})", records.size, order.size, maxSearches)
    var fired = 0
    val passARemaining = order.filter { it.priority < 5 }
    for (entry in passARemaining) {
        if (fired >= maxSearches) {
            logger.info("[backfill] hit max searches ({}), deferring rest", maxSearches)
            return fired
        }
        if (dryRun) {
            logger.info("[backfill] DRY RUN: would search series {} ({})", entry.seriesId, entry.label)
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

    // ---- Pass B: P5 ratchet ----
    if (fired >= maxSearches) return fired
    val p5Entries = order.filter { it.priority == 5 }
    if (p5Entries.isEmpty()) return fired

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

    val queueHits: Set<QueueSeasonHit> = run {
        val q = try { sonarr.getQueue() } catch (_: Exception) { JsonArray(emptyList()) }
        q.mapNotNull {
            val o = it.jsonObject
            val sid = o["seriesId"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val season = o["episode"]?.jsonObject?.get("seasonNumber")?.jsonPrimitive?.intOrNull
                ?: o["seasonNumber"]?.jsonPrimitive?.intOrNull
                ?: return@mapNotNull null
            QueueSeasonHit(sid, season)
        }.toSet()
    }

    val planInputs = P5RatchetInputs(
        p5Records = p5Records,
        queueHits = queueHits,
        cooldownRows = db.listP5Attempts(),
        nowEpochSeconds = System.currentTimeMillis() / 1000L,
        cfg = p5Ratchet,
        budgetRemaining = maxSearches - fired,
        ratchetActive = ratchetActive,
    )
    val plan = buildP5RatchetPlan(planInputs)
    fired += runP5SeasonRatchet(
        plan = plan, sonarr = sonarr, db = db,
        delaySeconds = delaySeconds, dryRun = dryRun,
    )
    return fired
}

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
