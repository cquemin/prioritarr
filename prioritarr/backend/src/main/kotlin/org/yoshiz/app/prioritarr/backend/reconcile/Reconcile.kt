package org.yoshiz.app.prioritarr.backend.reconcile

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.clients.QBitClient
import org.yoshiz.app.prioritarr.backend.clients.SABClient
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.database.Database
import org.yoshiz.app.prioritarr.backend.enforcement.QBitDownloadView
import org.yoshiz.app.prioritarr.backend.enforcement.computeQBitPauseActions
import org.yoshiz.app.prioritarr.backend.enforcement.computeSabPriority
import org.yoshiz.app.prioritarr.backend.priority.PriorityResult
import org.yoshiz.app.prioritarr.backend.priority.PriorityService

private val logger = LoggerFactory.getLogger("org.yoshiz.app.prioritarr.backend.reconcile")

/** Build the `download_id (lowercase) → {seriesId, episodeId}` map from Sonarr's /queue. */
suspend fun fetchSonarrQueueLookup(sonarr: SonarrClient): Map<String, Pair<Long, Long>> {
    val queue = try {
        sonarr.getQueue()
    } catch (e: Exception) {
        logger.warn("fetchSonarrQueueLookup: failed to fetch sonarr queue: ${e.message}")
        return emptyMap()
    }
    val out = mutableMapOf<String, Pair<Long, Long>>()
    for (el in queue) {
        val obj = el.jsonObject
        val dlId = obj["downloadId"]?.jsonPrimitive?.contentOrNull ?: continue
        val seriesId = obj["seriesId"]?.jsonPrimitive?.longOrNull ?: continue
        val episodeId = obj["episodeId"]?.jsonPrimitive?.longOrNull ?: 0L
        out[dlId.lowercase()] = seriesId to episodeId
    }
    return out
}

/**
 * Reconcile a single download client's queue against managed_downloads.
 *
 * 1. Adopt orphans (in the client queue but not yet tracked) — look up via
 *    sonarr's /queue to resolve which series they belong to.
 * 2. Recompute priorities for tracked downloads; log and persist changes.
 * 3. Apply enforcement (qBit pause/resume, SAB priority) unless dry_run.
 * 4. Remove managed_download rows whose download is no longer in the queue.
 *
 * Mirrors reconcile.py::reconcile_client.
 */
suspend fun reconcileQbit(
    qbit: QBitClient,
    db: Database,
    sonarrQueueLookup: Map<String, Pair<Long, Long>>,
    priorityService: PriorityService,
    dryRun: Boolean,
    bandwidth: org.yoshiz.app.prioritarr.backend.config.BandwidthSettings =
        org.yoshiz.app.prioritarr.backend.config.BandwidthSettings(),
    telemetry: org.yoshiz.app.prioritarr.backend.enforcement.DownloadTelemetry? = null,
) {
    val torrents = try {
        qbit.getTorrents()
    } catch (e: Exception) {
        logger.warn("[qbit] reconcile: get torrents failed: ${e.message}")
        return
    }
    // Feed observed speeds into the telemetry cache so the bandwidth
    // policy's rolling averages stay fresh. Separate from enforcement
    // so the data path is observable even when the feature is off.
    if (telemetry != null) {
        var total = 0L
        val seen = HashSet<String>()
        for (t in torrents) {
            val obj = t.jsonObject
            val hash = obj["hash"]?.jsonPrimitive?.contentOrNull ?: continue
            val speed = obj["dlspeed"]?.jsonPrimitive?.longOrNull ?: 0L
            telemetry.recordSample(hash, speed)
            total += speed
            seen += hash
        }
        telemetry.recordPeakTotal(total)
        telemetry.prune(seen)
    }
    reconcileImpl(
        clientName = "qbit",
        queue = torrents,
        idField = "hash",
        stateField = "state",
        db = db,
        sonarrQueueLookup = sonarrQueueLookup,
        priorityService = priorityService,
        applyEnforcement = {
            if (!dryRun) applyQbitEnforcement(qbit, db, torrents, bandwidth, telemetry)
        },
    )
}

suspend fun reconcileSab(
    sab: SABClient,
    db: Database,
    sonarrQueueLookup: Map<String, Pair<Long, Long>>,
    priorityService: PriorityService,
    dryRun: Boolean,
) {
    val slots = try {
        sab.getQueue()
    } catch (e: Exception) {
        logger.warn("[sab] reconcile: get queue failed: ${e.message}")
        return
    }
    reconcileImpl(
        clientName = "sab",
        queue = slots,
        idField = "nzo_id",
        stateField = null,
        db = db,
        sonarrQueueLookup = sonarrQueueLookup,
        priorityService = priorityService,
        applyEnforcement = { if (!dryRun) applySabEnforcement(sab, db) },
    )
}

private suspend fun reconcileImpl(
    clientName: String,
    queue: JsonArray,
    idField: String,
    stateField: String?,
    db: Database,
    sonarrQueueLookup: Map<String, Pair<Long, Long>>,
    priorityService: PriorityService,
    applyEnforcement: suspend () -> Unit,
) {
    val now = Database.nowIsoOffset()
    val currentIds = mutableSetOf<String>()
    logger.debug("[{}] reconcile: {} items in client queue", clientName, queue.size)

    for (el in queue) {
        val obj = el.jsonObject
        val clientId = obj[idField]?.jsonPrimitive?.contentOrNull ?: continue
        currentIds += clientId
        val row = db.getManagedDownload(clientName, clientId)
        if (row == null) {
            val sonarrInfo = sonarrQueueLookup[clientId.lowercase()]
            if (sonarrInfo == null) {
                logger.debug("[{}] orphan {} not in Sonarr queue, skipping", clientName, clientId.take(12))
                continue
            }
            val (seriesId, episodeId) = sonarrInfo
            val result = priorityService.priorityForSeries(seriesId)
            logger.info(
                "[{}] adopted orphan {} -> series {}, assigned P{}",
                clientName, clientId.take(12), seriesId, result.priority,
            )
            db.upsertManagedDownload(
                client = clientName, clientId = clientId, seriesId = seriesId,
                episodeIds = listOf(episodeId),
                initialPriority = result.priority.toLong(),
                currentPriority = result.priority.toLong(),
                pausedByUs = false,
                firstSeenAt = now, lastReconciledAt = now,
            )
            db.appendAudit(
                action = org.yoshiz.app.prioritarr.backend.AuditAction.PRIORITY_SET,
                seriesId = seriesId,
                client = clientName,
                clientId = clientId,
                details = buildJsonObject {
                    put("priority", result.priority)
                    put("source", "orphan_adopt")
                },
            )
        } else {
            val seriesId = row.series_id
            val result = priorityService.priorityForSeries(seriesId)
            val old = row.current_priority.toInt()
            if (result.priority != old) {
                logger.info(
                    "[{}] priority changed {}: P{} -> P{} ({})",
                    clientName, clientId.take(12), old, result.priority, result.reason,
                )
                db.upsertManagedDownload(
                    client = clientName, clientId = clientId, seriesId = seriesId,
                    episodeIds = emptyList(),
                    initialPriority = row.initial_priority,
                    currentPriority = result.priority.toLong(),
                    pausedByUs = row.paused_by_us == 1L,
                    firstSeenAt = row.first_seen_at, lastReconciledAt = now,
                )
                db.appendAudit(
                    action = org.yoshiz.app.prioritarr.backend.AuditAction.REORDER,
                    seriesId = seriesId,
                    client = clientName,
                    clientId = clientId,
                    details = buildJsonObject {
                        put("old", old)
                        put("new", result.priority)
                    },
                )
            }
        }
    }

    applyEnforcement()

    // Cleanup finished downloads.
    val managed = db.listManagedDownloads(clientName)
    var cleaned = 0
    for (row in managed) {
        if (row.client_id !in currentIds) {
            logger.info("[{}] cleaned up finished download {} (series {})", clientName, row.client_id.take(12), row.series_id)
            db.deleteManagedDownload(clientName, row.client_id)
            cleaned++
        }
    }
    if (cleaned > 0) logger.info("[{}] reconcile complete: cleaned {} finished downloads", clientName, cleaned)
}

private suspend fun applyQbitEnforcement(
    qbit: QBitClient,
    db: Database,
    queueItems: JsonArray,
    bandwidth: org.yoshiz.app.prioritarr.backend.config.BandwidthSettings =
        org.yoshiz.app.prioritarr.backend.config.BandwidthSettings(),
    telemetry: org.yoshiz.app.prioritarr.backend.enforcement.DownloadTelemetry? = null,
) {
    val managed = db.listManagedDownloads("qbit")
    val views = managed.map { row ->
        val qItem = queueItems
            .map { it.jsonObject }
            .firstOrNull { it["hash"]?.jsonPrimitive?.contentOrNull == row.client_id }
        val state = qItem?.get("state")?.jsonPrimitive?.contentOrNull ?: "downloading"
        val eta = qItem?.get("eta")?.jsonPrimitive?.longOrNull
        QBitDownloadView(
            hash = row.client_id,
            priority = row.current_priority.toInt(),
            state = state,
            pausedByUs = row.paused_by_us == 1L,
            etaSeconds = eta,
        )
    }

    // Build the bandwidth-aware predicate. Skip the pause when:
    //   - A P1 download is currently peer-limited (pausing others
    //     wouldn't help it go faster), OR
    //   - The candidate-for-pause is within the ETA buffer of
    //     finishing (wastes more than it frees).
    // When the feature is disabled (maxMbps <= 0), the predicate is a
    // no-op and every eligible torrent gets paused as before.
    val ctx = if (bandwidth.maxMbps <= 0) {
        org.yoshiz.app.prioritarr.backend.enforcement.EnforcementContext()
    } else {
        val policy = org.yoshiz.app.prioritarr.backend.enforcement.BandwidthPolicy
        val totalBps = queueItems.sumOf { it.jsonObject["dlspeed"]?.jsonPrimitive?.longOrNull ?: 0L }
        val p1Hashes = views.filter { it.priority == 1 }.map { it.hash }
        val p1AvgBps = p1Hashes
            .mapNotNull { telemetry?.averageBps(it) }
            .maxOrNull()
        val p1PeerLimited = policy.p1IsPeerLimited(bandwidth, p1AvgBps)
        val overUtilised = policy.utilisationExceedsThreshold(bandwidth, totalBps)
        org.yoshiz.app.prioritarr.backend.enforcement.EnforcementContext(
            shouldPauseLowBand = { candidate ->
                when {
                    p1PeerLimited -> false
                    !overUtilised -> false   // plenty of headroom; no need to pause
                    policy.closeToFinish(bandwidth, candidate.etaSeconds) -> false
                    else -> true
                }
            },
        )
    }
    val actions = computeQBitPauseActions(views, ctx)
    if (actions.isNotEmpty()) logger.info("[qbit] enforcement: {} actions to apply", actions.size)
    for (action in actions) {
        val priority = views.first { it.hash == action.hash }.priority
        when (action.action) {
            "pause" -> {
                logger.info("[qbit] PAUSE {} (P{} torrent, higher priority active)", action.hash.take(12), priority)
                try { qbit.pause(listOf(action.hash)) } catch (_: Exception) {}
                db.q.setManagedPaused(1L, "qbit", action.hash)
            }
            "resume" -> {
                logger.info("[qbit] RESUME {} (no longer needs to be paused)", action.hash.take(12))
                try { qbit.resume(listOf(action.hash)) } catch (_: Exception) {}
                db.q.setManagedPaused(0L, "qbit", action.hash)
            }
            "top_priority" -> {
                logger.info("[qbit] TOP_PRIORITY {} (P1 item)", action.hash.take(12))
                try { qbit.topPriority(listOf(action.hash)) } catch (_: Exception) {}
            }
        }
        db.appendAudit(
            action = action.action, client = "qbit", clientId = action.hash,
            details = buildJsonObject { put("source", "reconcile") },
        )
    }
}

private suspend fun applySabEnforcement(sab: SABClient, db: Database) {
    val managed = db.listManagedDownloads("sab")
    for (row in managed) {
        val sabPriority = computeSabPriority(row.current_priority.toInt())
        val names = mapOf(2 to "Force", 1 to "High", 0 to "Normal", -1 to "Low")
        logger.info(
            "[sab] SET_PRIORITY {} -> {} (P{})",
            row.client_id.take(12), names[sabPriority] ?: sabPriority.toString(), row.current_priority,
        )
        try { sab.setPriority(row.client_id, sabPriority) } catch (_: Exception) {}
        db.appendAudit(
            action = org.yoshiz.app.prioritarr.backend.AuditAction.PRIORITY_SET,
            client = org.yoshiz.app.prioritarr.backend.DownloadClientName.SAB.wire,
            clientId = row.client_id,
            seriesId = row.series_id,
            details = buildJsonObject {
                put("sab_priority", sabPriority)
                put("source", "reconcile")
            },
        )
    }
}
