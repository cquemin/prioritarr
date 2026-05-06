package org.yoshiz.app.prioritarr.backend.reconcile

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.clients.QBitClient
import org.yoshiz.app.prioritarr.backend.clients.SABClient
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.config.P5RatchetConfig
import org.yoshiz.app.prioritarr.backend.database.Database
import org.yoshiz.app.prioritarr.backend.enforcement.QBitDownloadView
import org.yoshiz.app.prioritarr.backend.enforcement.computeQBitPauseActions
import org.yoshiz.app.prioritarr.backend.enforcement.computeSabPriority
import org.yoshiz.app.prioritarr.backend.priority.PriorityResult
import org.yoshiz.app.prioritarr.backend.priority.PriorityService

private val logger = LoggerFactory.getLogger("org.yoshiz.app.prioritarr.backend.reconcile")

data class SonarrQueueInfo(
    val seriesId: Long,
    val episodeId: Long,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
)

/** Build the `download_id (lowercase) → SonarrQueueInfo` map from Sonarr's /queue. */
suspend fun fetchSonarrQueueLookup(sonarr: SonarrClient): Map<String, SonarrQueueInfo> {
    val queue = try {
        sonarr.getQueue()
    } catch (e: Exception) {
        logger.warn("fetchSonarrQueueLookup: failed to fetch sonarr queue: ${e.message}")
        return emptyMap()
    }
    val out = mutableMapOf<String, SonarrQueueInfo>()
    for (el in queue) {
        val obj = el.jsonObject
        val dlId = obj["downloadId"]?.jsonPrimitive?.contentOrNull ?: continue
        val seriesId = obj["seriesId"]?.jsonPrimitive?.longOrNull ?: continue
        val episodeId = obj["episodeId"]?.jsonPrimitive?.longOrNull ?: 0L
        val episode = obj["episode"]?.jsonObject
        val season = episode?.get("seasonNumber")?.jsonPrimitive?.intOrNull
            ?: obj["seasonNumber"]?.jsonPrimitive?.intOrNull
        val epNum = episode?.get("episodeNumber")?.jsonPrimitive?.intOrNull
        out[dlId.lowercase()] = SonarrQueueInfo(seriesId, episodeId, season, epNum)
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
    sonarrQueueLookup: Map<String, SonarrQueueInfo>,
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
    sonarrQueueLookup: Map<String, SonarrQueueInfo>,
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
    sonarrQueueLookup: Map<String, SonarrQueueInfo>,
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
            val seriesId = sonarrInfo.seriesId
            val episodeId = sonarrInfo.episodeId
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

/**
 * Unified reconcile pass over all configured download clients. Replaces
 * the per-client `reconcileQbit` + `reconcileSab` pair: snapshots each
 * client, joins with managed_downloads + Sonarr queue, runs the unified
 * computeEnforcement, then dispatches each client's slice of decisions
 * to its applyEnforcement.
 *
 * Per-client `reconcileQbit` / `reconcileSab` are kept for now (the
 * scheduler call sites in Main.kt switch over in the next step) but are
 * scheduled for removal once the parity tests + a few production
 * cycles confirm the new path produces the same observable behaviour.
 */
suspend fun reconcileAll(
    qbit: QBitClient,
    sab: SABClient,
    sonarr: SonarrClient,
    db: Database,
    priorityService: PriorityService,
    bandwidth: org.yoshiz.app.prioritarr.backend.config.BandwidthSettings,
    p5Ratchet: P5RatchetConfig,
    telemetry: org.yoshiz.app.prioritarr.backend.enforcement.DownloadTelemetry?,
    dryRun: Boolean,
) {
    val sonarrQueueLookup = fetchSonarrQueueLookup(sonarr)

    // Run the existing per-client reconcileImpl (orphan adoption,
    // priority recompute, cleanup). Don't call its enforcement step —
    // we'll do that ourselves below with the unified path.
    runReconcileImplOnly(
        qbit = qbit, sab = sab, db = db,
        sonarrQueueLookup = sonarrQueueLookup,
        priorityService = priorityService,
    )

    if (dryRun) return

    // Snapshot each client and project to ManagedDownloadView via
    // managed_downloads + the queue lookup.
    val rawByClient: Map<String, List<org.yoshiz.app.prioritarr.backend.enforcement.RawDownload>> =
        mapOf(
            "qbit" to qbit.snapshotDownloads(),
            "sab" to sab.snapshotDownloads(),
        )

    // Persist newly-seen seasonNumbers from Sonarr's queue lookup
    // into managed_downloads.season_number so the column is available
    // to subsequent ticks even if Sonarr's queue blips.
    for ((_, raws) in rawByClient) {
        for (r in raws) {
            val info = sonarrQueueLookup[r.clientId.lowercase()] ?: continue
            val season = info.seasonNumber ?: continue
            db.setManagedSeasonNumber(r.client, r.clientId, season)
        }
    }

    val views = rawByClient.flatMap { (clientName, raws) ->
        raws.mapNotNull { r ->
            val managed = db.getManagedDownload(clientName, r.clientId) ?: return@mapNotNull null
            val info = sonarrQueueLookup[r.clientId.lowercase()]
            val state = projectState(rawState = r.rawState, pausedByUs = managed.paused_by_us == 1L)
            org.yoshiz.app.prioritarr.backend.enforcement.ManagedDownloadView(
                client = clientName,
                clientId = r.clientId,
                priority = managed.current_priority.toInt(),
                seriesId = managed.series_id,
                seasonNumber = managed.season_number?.toInt() ?: info?.seasonNumber,
                episodeNumber = info?.episodeNumber,
                state = state,
                etaSeconds = r.etaSeconds,
            )
        }
    }

    // Bandwidth signal — read once.
    val ctx = buildEnforcementContext(views, bandwidth, p5Ratchet, telemetry)

    val decisions = org.yoshiz.app.prioritarr.backend.enforcement.computeEnforcement(views, ctx)

    // Per-client SAB priority bucket pass first (existing behaviour).
    runSabPriorityBucketPass(sab, db)

    // Dispatch enforcement to each client.
    val byClient = decisions.entries.groupBy { e ->
        views.firstOrNull { it.clientId == e.key }?.client ?: "qbit"
    }
    qbit.applyEnforcement(byClient["qbit"]?.associate { it.toPair() } ?: emptyMap())
    sab.applyEnforcement(byClient["sab"]?.associate { it.toPair() } ?: emptyMap())

    // pausedByUs flag in DB — keep in sync with the decision for each
    // qBit / SAB row that we touched.
    for ((cid, decision) in decisions) {
        val v = views.firstOrNull { it.clientId == cid } ?: continue
        val newFlag = decision.targetState ==
            org.yoshiz.app.prioritarr.backend.enforcement.TargetState.DEFERRED
        db.q.setManagedPaused(if (newFlag) 1L else 0L, v.client, cid)
        if (newFlag && v.priority == 5 &&
            ctx.p5SeasonRatchetActive &&
            // Only audit Layer-2 defers (Layer 1 audits stay where they were)
            views.any { it.priority == 5 && it.seriesId == v.seriesId && it.seasonNumber != null && it.seasonNumber!! < (v.seasonNumber ?: Int.MAX_VALUE) }
        ) {
            db.appendAudit(
                action = "p5_ratchet_defer",
                seriesId = v.seriesId,
                client = v.client,
                clientId = v.clientId,
                details = buildJsonObject {
                    put("season_number", v.seasonNumber ?: -1)
                },
            )
        }
    }
}

/**
 * Runs only the orphan-adoption + priority-recompute + cleanup phases
 * of the legacy per-client reconcile, skipping its enforcement step.
 * Lets [reconcileAll] do enforcement once over the unified view.
 */
private suspend fun runReconcileImplOnly(
    qbit: QBitClient,
    sab: SABClient,
    db: Database,
    sonarrQueueLookup: Map<String, SonarrQueueInfo>,
    priorityService: PriorityService,
) {
    val torrents = try { qbit.getTorrents() } catch (_: Exception) { JsonArray(emptyList()) }
    reconcileImpl(
        clientName = "qbit",
        queue = torrents,
        idField = "hash",
        stateField = "state",
        db = db,
        sonarrQueueLookup = sonarrQueueLookup,
        priorityService = priorityService,
        applyEnforcement = { /* no-op here; reconcileAll does it */ },
    )
    val slots = try { sab.getQueue() } catch (_: Exception) { JsonArray(emptyList()) }
    reconcileImpl(
        clientName = "sab",
        queue = slots,
        idField = "nzo_id",
        stateField = null,
        db = db,
        sonarrQueueLookup = sonarrQueueLookup,
        priorityService = priorityService,
        applyEnforcement = { /* no-op */ },
    )
}

/** Per-row SAB priority bucket pass, lifted out of applySabEnforcement. */
private suspend fun runSabPriorityBucketPass(
    sab: SABClient,
    db: Database,
) {
    val managed = db.listManagedDownloads("sab")
    for (row in managed) {
        val sabPriority = computeSabPriority(row.current_priority.toInt())
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

private fun projectState(rawState: String, pausedByUs: Boolean): org.yoshiz.app.prioritarr.backend.enforcement.ManagedState {
    if (pausedByUs) return org.yoshiz.app.prioritarr.backend.enforcement.ManagedState.PAUSED_BY_US
    return when (rawState.lowercase()) {
        "paused", "paused_dl", "pauseddl", "pausedup" ->
            org.yoshiz.app.prioritarr.backend.enforcement.ManagedState.PAUSED_BY_USER
        "error", "missingfiles", "failed" ->
            org.yoshiz.app.prioritarr.backend.enforcement.ManagedState.ERRORED
        else -> org.yoshiz.app.prioritarr.backend.enforcement.ManagedState.RUNNING
    }
}

private fun buildEnforcementContext(
    views: List<org.yoshiz.app.prioritarr.backend.enforcement.ManagedDownloadView>,
    bandwidth: org.yoshiz.app.prioritarr.backend.config.BandwidthSettings,
    p5Ratchet: P5RatchetConfig,
    telemetry: org.yoshiz.app.prioritarr.backend.enforcement.DownloadTelemetry?,
): org.yoshiz.app.prioritarr.backend.enforcement.ComputeEnforcementContext {
    if (bandwidth.maxMbps <= 0) {
        // Bandwidth feature off — preserve today's "always defer per priority band" behaviour.
        // bandwidthAwareEnabled defaults to false; the rest are also defaults.
        return org.yoshiz.app.prioritarr.backend.enforcement.ComputeEnforcementContext()
    }
    val totalBps = telemetry?.observedPeakTotalBps() ?: 0L
    val policy = org.yoshiz.app.prioritarr.backend.enforcement.BandwidthPolicy
    // P5 ratchet has its own threshold knob; if null, fall back to BandwidthSettings.
    val ratchetBandwidth = p5Ratchet.bandwidthThresholdPct?.let { bandwidth.copy(utilisationThresholdPct = it) }
        ?: bandwidth
    val saturated = policy.utilisationExceedsThreshold(bandwidth, totalBps)
    val ratchetSaturated = policy.utilisationExceedsThreshold(ratchetBandwidth, totalBps)
    val p1Hashes = views.filter { it.priority == 1 }.map { it.clientId }
    val p1AvgBps = p1Hashes.mapNotNull { telemetry?.averageBps(it) }.maxOrNull()
    val p1PeerLimited = policy.p1IsPeerLimited(bandwidth, p1AvgBps)
    val ratchetActive = p5Ratchet.enabled && ratchetSaturated
    return org.yoshiz.app.prioritarr.backend.enforcement.ComputeEnforcementContext(
        bandwidthAwareEnabled = true,                  // <-- set the new flag
        bandwidthSaturated = saturated,
        p5SeasonRatchetActive = ratchetActive,
        p1IsPeerLimited = p1PeerLimited,
        isNearDone = { v ->
            policy.closeToFinish(bandwidth, v.etaSeconds)
        },
    )
}
