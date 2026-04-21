package org.yoshiz.app.prioritarr.backend.priority

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient

private val logger = LoggerFactory.getLogger("org.yoshiz.app.prioritarr.backend.priority.refresh")

/**
 * Compute + cache a priority for every monitored Sonarr series.
 *
 * Before this job existed, priorities were only computed for series
 * with active qBit / SAB queue items (via the reconcile loop) or via
 * webhook hits (OnGrab / Plex-watched). That meant a series the user
 * actively watched but had no pending download sat at priority=null
 * in the UI forever.
 *
 * Rate control: [PriorityService.priorityForSeries] does a TTL-guarded
 * read before recomputing, so a tight per-series loop mostly hits the
 * cache and the upstream cost is bounded by whatever entries actually
 * expired since the last run.
 *
 * Only monitored series are processed — if a user unmonitored a show
 * they no longer care about, prioritarr shouldn't keep computing a
 * priority for it (and the UI already elides unmonitored series from
 * the main list).
 *
 * @return number of series for which a priority was computed (skipped
 *         ones included in the denominator but not the count).
 */
suspend fun refreshAllPriorities(
    sonarr: SonarrClient,
    priorityService: PriorityService,
): RefreshAllStats {
    val all = try {
        sonarr.getAllSeries()
    } catch (e: Exception) {
        logger.warn("refreshAllPriorities: fetching Sonarr /series failed: {}", e.message)
        return RefreshAllStats()
    }

    var monitored = 0
    var computed = 0
    var skippedUnmonitored = 0
    var failed = 0

    for (el in all) {
        val s = el.jsonObject
        val id = s["id"]?.jsonPrimitive?.longOrNull ?: continue
        val isMonitored = s["monitored"]?.jsonPrimitive?.booleanOrNull == true
        if (!isMonitored) {
            skippedUnmonitored++
            continue
        }
        monitored++
        try {
            priorityService.priorityForSeries(id)
            computed++
        } catch (e: Exception) {
            failed++
            logger.debug("refreshAllPriorities: series {} failed: {}", id, e.message)
        }
    }

    logger.info(
        "refreshAllPriorities: monitored={} computed={} skippedUnmonitored={} failed={}",
        monitored, computed, skippedUnmonitored, failed,
    )
    return RefreshAllStats(monitored, computed, skippedUnmonitored, failed)
}

/** Summary returned by [refreshAllPriorities]. */
data class RefreshAllStats(
    val monitored: Int = 0,
    val computed: Int = 0,
    val skippedUnmonitored: Int = 0,
    val failed: Int = 0,
)
