package org.yoshiz.app.prioritarr.backend.reconcile

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.clients.QBitClient
import org.yoshiz.app.prioritarr.backend.clients.SABClient
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.database.Database
import java.time.Instant

/**
 * Cleans up download queues so the active set always reflects what
 * prioritarr's priority compute thinks should be downloading.
 *
 * Two stuck-detection rules:
 *   - **qBit**: any tracked torrent whose `last_activity` is older than
 *     [stuckAfter]. This catches stalled torrents (no peers / dead
 *     trackers / unsupported codec quietly aborted), error states, and
 *     paused-not-by-us cases. Items we paused on purpose (via boost /
 *     demote) are excluded from the stuck check by `paused_by_us`.
 *   - **SAB**: history entries with `status="Failed"` (immediate, no
 *     wait — Failed is by definition stuck) AND queue items whose
 *     status is "Paused" without progress for [stuckAfter]. SAB's
 *     `Failed` covers duplicate-detected, par failures, missing
 *     articles, expired retention, etc.
 *
 * Per stuck item:
 *   1. Untrack from prioritarr's `managed_downloads` table.
 *   2. Tell Sonarr to remove + blocklist the linked queue entry —
 *      Sonarr will refuse to grab the same release again until the
 *      blocklist entry is manually removed. Also deletes from the
 *      client (qBit / SAB) with files in the same call.
 *   3. Schedule an EpisodeSearch for the release's episodeIds. Items
 *      are processed in priority order (P1 first), with a small pause
 *      between calls so Sonarr's per-indexer rate limits stay happy.
 *
 * In dry-run mode every action is logged + audited but no destructive
 * call is made.
 */
class QueueJanitor(
    private val sonarr: SonarrClient,
    private val qbit: QBitClient,
    private val sab: SABClient,
    private val db: Database,
    private val stuckAfter: java.time.Duration = java.time.Duration.ofHours(48),
    private val perItemPauseMillis: Long = 5000,
) {
    private val logger = LoggerFactory.getLogger(QueueJanitor::class.java)

    /**
     * Single sweep. Safe to call concurrently with reconcile — each
     * item is operated on independently, and Sonarr's API is
     * idempotent enough that double-acting is at worst a wasted call.
     */
    suspend fun sweep(dryRun: Boolean): JanitorReport {
        val now = Instant.now()
        val stuckQbit = findStuckQbit(now)
        val stuckSabFromQueue = findStuckSabQueue(now)
        val failedSab = findFailedSabHistory()

        val all = (stuckQbit + stuckSabFromQueue + failedSab)
            .sortedBy { it.priority ?: 6 }   // P1 first; null priorities last
        if (all.isEmpty()) {
            logger.debug("queue-janitor: nothing stuck")
            return JanitorReport(0, 0, 0)
        }

        var cleaned = 0
        var researched = 0
        for (item in all) {
            val ok = handleStuck(item, dryRun)
            if (ok) {
                cleaned++
                if (item.episodeIds.isNotEmpty()) researched++
            }
            // Pace re-search calls so Sonarr's per-indexer rate limits
            // don't refuse later items in the same batch.
            if (!dryRun && item.episodeIds.isNotEmpty()) delay(perItemPauseMillis)
        }
        logger.info(
            "queue-janitor: scanned={} cleaned={} re_searched={} dryRun={}",
            all.size, cleaned, researched, dryRun,
        )
        return JanitorReport(scanned = all.size, cleaned = cleaned, researched = researched)
    }

    private suspend fun findStuckQbit(now: Instant): List<StuckItem> {
        val torrents = try {
            qbit.getTorrents()
        } catch (e: Exception) {
            logger.warn("queue-janitor: qbit.getTorrents failed: {}", e.message)
            return emptyList()
        }

        val byHash = db.listManagedDownloads("qbit").associateBy { it.client_id.lowercase() }
        return torrents.mapNotNull { t ->
            val obj = t.jsonObject
            val hash = obj["hash"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: return@mapNotNull null
            val managed = byHash[hash] ?: return@mapNotNull null  // not ours, leave alone
            if (managed.paused_by_us == 1L) return@mapNotNull null  // paused on purpose, not stuck
            val lastActivityEpoch = obj["last_activity"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val lastActivity = Instant.ofEpochSecond(lastActivityEpoch)
            val state = obj["state"]?.jsonPrimitive?.contentOrNull
            val isStuck = java.time.Duration.between(lastActivity, now) > stuckAfter ||
                state in TERMINAL_QBIT_STATES
            if (!isStuck) return@mapNotNull null
            StuckItem(
                client = "qbit",
                clientId = managed.client_id,
                seriesId = managed.series_id,
                episodeIds = parseEpisodeIds(managed.episode_ids),
                priority = managed.current_priority.toInt(),
                reason = "qbit state=$state, idle for ${java.time.Duration.between(lastActivity, now).toHours()}h",
            )
        }
    }

    private suspend fun findStuckSabQueue(now: Instant): List<StuckItem> {
        val queue = try {
            sab.getQueue()
        } catch (e: Exception) {
            logger.warn("queue-janitor: sab.getQueue failed: {}", e.message)
            return emptyList()
        }
        val bySabId = db.listManagedDownloads("sab").associateBy { it.client_id }
        return queue.mapNotNull { entry ->
            val obj = entry.jsonObject
            val nzo = obj["nzo_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val managed = bySabId[nzo] ?: return@mapNotNull null
            if (managed.paused_by_us == 1L) return@mapNotNull null
            val status = obj["status"]?.jsonPrimitive?.contentOrNull
            // SAB doesn't expose a per-item "last activity" timestamp on
            // the queue endpoint, so we use the prioritarr-side
            // last_reconciled_at as a proxy: if reconcile keeps seeing
            // the same paused row for > stuckAfter without it ever
            // having progressed (no transition out of Paused), that's
            // stuck.
            if (status != "Paused") return@mapNotNull null
            val lastSeen = try {
                java.time.OffsetDateTime.parse(managed.last_reconciled_at).toInstant()
            } catch (_: Exception) { return@mapNotNull null }
            if (java.time.Duration.between(lastSeen, now) <= stuckAfter) return@mapNotNull null
            StuckItem(
                client = "sab",
                clientId = nzo,
                seriesId = managed.series_id,
                episodeIds = parseEpisodeIds(managed.episode_ids),
                priority = managed.current_priority.toInt(),
                reason = "sab queue Paused for >48h",
            )
        }
    }

    private suspend fun findFailedSabHistory(): List<StuckItem> {
        val history = try {
            sab.getHistory(limit = 100)
        } catch (e: Exception) {
            logger.warn("queue-janitor: sab.getHistory failed: {}", e.message)
            return emptyList()
        }
        val bySabId = db.listManagedDownloads("sab").associateBy { it.client_id }
        return history.mapNotNull { entry ->
            val obj = entry.jsonObject
            val nzo = obj["nzo_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val status = obj["status"]?.jsonPrimitive?.contentOrNull
            if (status != "Failed") return@mapNotNull null
            val managed = bySabId[nzo] ?: return@mapNotNull null
            val failMsg = obj["fail_message"]?.jsonPrimitive?.contentOrNull.orEmpty()
            StuckItem(
                client = "sab",
                clientId = nzo,
                seriesId = managed.series_id,
                episodeIds = parseEpisodeIds(managed.episode_ids),
                priority = managed.current_priority.toInt(),
                reason = "sab history Failed: $failMsg",
            )
        }
    }

    private suspend fun handleStuck(item: StuckItem, dryRun: Boolean): Boolean {
        logger.info(
            "queue-janitor: {} {}/{} (priority={}) — {}{}",
            if (dryRun) "[dry-run]" else "[CLEANING]",
            item.client, item.clientId, item.priority, item.reason,
            if (item.episodeIds.isEmpty()) " (no episodeIds known)" else "",
        )
        if (dryRun) {
            db.appendAudit(
                action = "queue_janitor_dry_run",
                seriesId = item.seriesId,
                client = item.client,
                clientId = item.clientId,
                details = Json.parseToJsonElement(
                    """{"reason":"${item.reason.replace("\"","'")}","priority":${item.priority}}"""
                ),
            )
            return true
        }

        // 1. Find Sonarr's queue entry (if any) so we can remove + blocklist
        //    in one Sonarr call. We pass the same client id (qBit hash /
        //    SAB nzo_id) — Sonarr stores it as the queue entry's downloadId.
        val sonarrEntry = try {
            sonarr.findQueueEntryForDownloadId(item.clientId)
        } catch (e: Exception) {
            logger.warn("queue-janitor: sonarr.findQueueEntryForDownloadId failed: {}", e.message)
            null
        }
        var blacklisted = false
        if (sonarrEntry != null) {
            val queueId = sonarrEntry["id"]?.jsonPrimitive?.longOrNull
            if (queueId != null) {
                try {
                    sonarr.removeQueueEntry(queueId, removeFromClient = true, blocklist = true)
                    blacklisted = true
                } catch (e: Exception) {
                    logger.warn("queue-janitor: sonarr.removeQueueEntry($queueId) failed: {}", e.message)
                }
            }
        }

        // 2. If Sonarr didn't already remove from the client, do it
        //    ourselves. Always safe to call delete on already-gone items.
        if (!blacklisted) {
            try {
                when (item.client) {
                    "qbit" -> qbit.delete(listOf(item.clientId), deleteFiles = true)
                    "sab" -> sab.deleteFromQueue(item.clientId, delFiles = true)
                }
            } catch (e: Exception) {
                logger.warn("queue-janitor: client delete failed for {}/{}: {}",
                    item.client, item.clientId, e.message)
            }
            // Also try the SAB history — Failed entries live there.
            if (item.client == "sab") {
                try {
                    sab.deleteFromHistory(item.clientId, delFiles = true)
                } catch (_: Exception) { /* may not be in history; fine */ }
            }
        }

        // 3. Untrack from our state.
        try {
            db.deleteManagedDownload(item.client, item.clientId)
        } catch (e: Exception) {
            logger.warn("queue-janitor: deleteManagedDownload failed: {}", e.message)
        }

        // 4. Trigger a re-search for the linked episodes. Sonarr's
        //    EpisodeSearch will pick a new release from the indexers,
        //    skipping the one we just blocklisted.
        if (item.episodeIds.isNotEmpty()) {
            try {
                sonarr.triggerEpisodeSearch(item.episodeIds)
            } catch (e: Exception) {
                logger.warn("queue-janitor: triggerEpisodeSearch({}) failed: {}",
                    item.episodeIds, e.message)
            }
        }

        db.appendAudit(
            action = "queue_janitor_cleanup",
            seriesId = item.seriesId,
            client = item.client,
            clientId = item.clientId,
            details = Json.parseToJsonElement(
                """{"reason":"${item.reason.replace("\"","'")}","priority":${item.priority},"blacklisted":$blacklisted,"researched":${item.episodeIds.isNotEmpty()}}"""
            ),
        )
        return true
    }

    private fun parseEpisodeIds(raw: String?): List<Long> =
        raw?.let {
            try {
                (Json.parseToJsonElement(it) as JsonArray).map { e ->
                    (e as JsonPrimitive).content.toLong()
                }
            } catch (_: Exception) { emptyList() }
        } ?: emptyList()

    /** Internal record of a stuck item; one per client/clientId. */
    private data class StuckItem(
        val client: String,
        val clientId: String,
        val seriesId: Long,
        val episodeIds: List<Long>,
        val priority: Int?,
        val reason: String,
    )

    companion object {
        /**
         * qBit states that are considered terminal-stuck regardless
         * of last_activity. error/missingFiles can't recover without
         * intervention; pausedDL is included only when paused_by_us=0.
         */
        private val TERMINAL_QBIT_STATES = setOf("error", "missingFiles")
    }
}

/** Per-sweep summary. Returned by [QueueJanitor.sweep] for logging/tests. */
data class JanitorReport(
    val scanned: Int,
    val cleaned: Int,
    val researched: Int,
)
