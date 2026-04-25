package org.yoshiz.app.prioritarr.backend.reconcile

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.database.Database

/**
 * Reaps download-client queue entries whose corresponding series /
 * season / episode in Sonarr is no longer monitored. If the user
 * un-monitors a show (or a specific season) while it's actively
 * downloading, Sonarr on its own leaves the queue entry running;
 * this sweep cancels it.
 *
 * Strategy: walk Sonarr's /queue, for each queue record:
 *   - Look up the series + episodeIds.
 *   - If the series is unmonitored OR all referenced episodes are
 *     unmonitored OR the containing season is unmonitored →
 *     remove the queue entry via `removeQueueEntry(removeFromClient=
 *     true, blocklist=true, skipRedownload=true)` so the client
 *     drops the download AND Sonarr won't re-grab the same release.
 *
 * dryRun=true logs what it would do without acting.
 */
class UnmonitoredReaper(
    private val sonarr: SonarrClient,
    private val db: Database,
) {
    private val logger = LoggerFactory.getLogger(UnmonitoredReaper::class.java)

    suspend fun sweep(dryRun: Boolean): UnmonitoredReport {
        val queue: JsonArray = try {
            sonarr.getQueue(pageSize = 1000)
        } catch (e: Exception) {
            logger.warn("unmonitored-reaper: queue fetch failed: {}", e.message)
            return UnmonitoredReport(0, 0, 0)
        }
        if (queue.isEmpty()) {
            logger.info("unmonitored-reaper: swept, queue empty")
            return UnmonitoredReport(0, 0, 0)
        }

        // Cache series + episode monitored flags to avoid N per-record
        // Sonarr calls — a typical queue is ~20 records, series are
        // usually repeated across them.
        val seriesMonitored = HashMap<Long, Boolean>()
        val seasonMonitored = HashMap<Pair<Long, Int>, Boolean>()
        val episodeMonitored = HashMap<Long, Boolean>()

        var inspected = 0
        var removed = 0
        var errors = 0

        for (el in queue) {
            val rec = (el as? JsonObject) ?: continue
            val queueId = rec["id"]?.jsonPrimitive?.longOrNull ?: continue
            val seriesId = rec["seriesId"]?.jsonPrimitive?.longOrNull ?: continue
            val episodeId = rec["episodeId"]?.jsonPrimitive?.longOrNull
            inspected++

            val sMon = seriesMonitored.getOrPut(seriesId) {
                try {
                    sonarr.getSeries(seriesId)["monitored"]?.jsonPrimitive?.booleanOrNull == true
                } catch (_: Exception) { true /* fail open */ }
            }

            // Episode-level + season-level require fetching /episodes once per series.
            var epMon = true
            var seMon = true
            if (episodeId != null) {
                val cached = episodeMonitored[episodeId]
                if (cached != null) {
                    epMon = cached
                } else {
                    try {
                        val eps = sonarr.getEpisodes(seriesId)
                        for (epEl in eps) {
                            val obj = epEl.jsonObject
                            val id = obj["id"]?.jsonPrimitive?.longOrNull ?: continue
                            episodeMonitored[id] = obj["monitored"]?.jsonPrimitive?.booleanOrNull == true
                        }
                        epMon = episodeMonitored[episodeId] ?: true
                    } catch (_: Exception) { /* fail open */ }
                }
            }
            rec["episode"]?.jsonObject?.let { ep ->
                val seriesIdForEp = seriesId
                val seasonNum = ep["seasonNumber"]?.jsonPrimitive?.longOrNull?.toInt()
                if (seasonNum != null) {
                    val key = seriesIdForEp to seasonNum
                    seMon = seasonMonitored.getOrPut(key) {
                        try {
                            val seasons = sonarr.getSeries(seriesIdForEp)["seasons"] as? JsonArray
                            seasons?.any { s ->
                                val obj = s.jsonObject
                                obj["seasonNumber"]?.jsonPrimitive?.longOrNull?.toInt() == seasonNum &&
                                    obj["monitored"]?.jsonPrimitive?.booleanOrNull == true
                            } ?: true
                        } catch (_: Exception) { true }
                    }
                }
            }

            val shouldRemove = !sMon || !epMon || !seMon
            if (!shouldRemove) continue

            val reason = buildString {
                if (!sMon) append("series=unmonitored;")
                if (!seMon) append("season=unmonitored;")
                if (!epMon) append("episode=unmonitored;")
            }.trimEnd(';')

            logger.info("unmonitored-reaper: queueId={} series={} ({}){}", queueId, seriesId,
                reason, if (dryRun) " [DRY RUN]" else "")

            if (!dryRun) {
                try {
                    sonarr.removeQueueEntry(queueId = queueId, removeFromClient = true, blocklist = true)
                    removed++
                    try {
                        db.appendAudit(
                            action = "unmonitored_queue_removed",
                            seriesId = seriesId,
                            client = null,
                            clientId = null,
                            details = kotlinx.serialization.json.Json.parseToJsonElement(
                                """{"queue_id":$queueId,"reason":"$reason"}""",
                            ),
                        )
                    } catch (_: Exception) { /* audit best effort */ }
                } catch (e: Exception) {
                    errors++
                    logger.warn("unmonitored-reaper: remove failed queueId={}: {}", queueId, e.message)
                }
            } else {
                removed++  // would have removed
            }
        }

        logger.info(
            "unmonitored-reaper: swept inspected={} removed={} errors={} dryRun={}",
            inspected, removed, errors, dryRun,
        )
        return UnmonitoredReport(inspected = inspected, removed = removed, errors = errors)
    }
}

data class UnmonitoredReport(val inspected: Int, val removed: Int, val errors: Int)
