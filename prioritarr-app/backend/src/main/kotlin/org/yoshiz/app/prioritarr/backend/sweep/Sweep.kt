package org.yoshiz.app.prioritarr.backend.sweep

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
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
    maxSearches: Int,
    delaySeconds: Int,
    dryRun: Boolean,
): Int = runSweep(
    name = "backfill",
    fetch = { sonarr.getWantedMissing() },
    trigger = { sonarr.triggerSeriesSearch(it) },
    priorityService = priorityService,
    maxSearches = maxSearches,
    delaySeconds = delaySeconds,
    dryRun = dryRun,
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
