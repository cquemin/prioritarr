package org.yoshiz.app.prioritarr.backend.webhooks

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.database.Database
import org.yoshiz.app.prioritarr.backend.sweep.toEpisodeIdSet

private val logger = LoggerFactory.getLogger("org.yoshiz.app.prioritarr.backend.webhooks.followup")

/**
 * Side effect of an On-Grab event for a P1/P2 series — searches the
 * next [followupCap] oldest missing episodes of the same series,
 * skipping the just-grabbed episode IDs, anything already in Sonarr's
 * queue, and anything inside the P1/P2 cooldown window. Records cooldown
 * + audit row for every episode it fires. Never throws — failures are
 * warn-logged because the webhook response has already been sent.
 */
suspend fun runOnGrabFollowup(
    event: OnGrabEvent,
    priority: Int,
    sonarr: SonarrClient,
    db: Database,
    followupCap: Int,
    cooldownSeconds: Long,
    nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
) {
    if (priority !in 1..2) return
    if (followupCap <= 0) return
    try {
        val missing = sonarr.getWantedMissing()
        val queueIds = runCatching { sonarr.getQueue().toEpisodeIdSet() }.getOrDefault(emptySet())
        val cooldownIds = db.listP1P2AttemptedSince(nowEpochSeconds - cooldownSeconds).toSet()
        val grabbedIds = event.episodeIds.toSet()

        val candidates = missing.mapNotNull { row ->
            val o = row.jsonObject
            val sid = o["seriesId"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            if (sid != event.seriesId) return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            if (id in grabbedIds || id in queueIds || id in cooldownIds) return@mapNotNull null
            val air = o["airDateUtc"]?.jsonPrimitive?.contentOrNull ?: "9999"
            id to air
        }
            .sortedBy { it.second }
            .take(followupCap)
            .map { it.first }

        if (candidates.isEmpty()) return

        sonarr.triggerEpisodeSearch(candidates)
        candidates.forEach { db.upsertP1P2Attempt(it, nowEpochSeconds) }
        db.appendAudit(
            action = "ongrab_followup",
            seriesId = event.seriesId,
            client = null, clientId = null,
            details = buildJsonObject {
                put("episode_ids", buildJsonArray { candidates.forEach { add(it) } })
                put("priority", priority)
            },
        )
        logger.info("[ongrab-followup] series {} fired EpisodeSearch for {}", event.seriesId, candidates)
    } catch (e: Exception) {
        logger.warn("[ongrab-followup] series {} failed: {}", event.seriesId, e.message)
    }
}
