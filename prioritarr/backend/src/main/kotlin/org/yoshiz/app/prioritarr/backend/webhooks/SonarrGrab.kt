package org.yoshiz.app.prioritarr.backend.webhooks

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import org.yoshiz.app.prioritarr.backend.database.Database
import java.security.MessageDigest

/** Mirrors CLIENT_MAP in prioritarr/webhooks/sonarr_grab.py. */
val CLIENT_MAP = mapOf(
    "qbittorrent" to "qbit",
    "sabnzbd" to "sab",
    "nzbget" to "sab",
)

data class OnGrabEvent(
    val seriesId: Long,
    val seriesTitle: String,
    val tvdbId: Long,
    val episodeIds: List<Long>,
    val downloadClient: String,
    val downloadId: String,
    val airDate: String?,
)

fun parseOnGrabPayload(data: JsonObject): OnGrabEvent {
    val series = (data["series"] as? JsonObject) ?: JsonObject(emptyMap())
    val episodes = (data["episodes"]?.jsonArray) ?: kotlinx.serialization.json.JsonArray(emptyList())

    val rawClient = data["downloadClient"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
    val normalizedClient = CLIENT_MAP[rawClient] ?: rawClient

    val episodeIds = episodes.mapNotNull {
        it.jsonObject["id"]?.jsonPrimitive?.longOrNull
    }

    val airDate = episodes.firstOrNull()?.jsonObject?.get("airDateUtc")?.jsonPrimitive?.contentOrNull

    return OnGrabEvent(
        seriesId = series["id"]?.jsonPrimitive?.longOrNull ?: 0L,
        seriesTitle = series["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        tvdbId = series["tvdbId"]?.jsonPrimitive?.longOrNull ?: 0L,
        episodeIds = episodeIds,
        downloadClient = normalizedClient,
        downloadId = data["downloadId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        airDate = airDate,
    )
}

fun eventKey(event: OnGrabEvent): String {
    val raw = "Grab:${event.seriesId}:${event.episodeIds.sorted()}:${event.downloadId}"
    val sha = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
    return sha.joinToString("") { "%02x".format(it) }
}

/**
 * Deduplicate, write managed_download row (unless dry-run), append audit.
 * Returns true if processed, false if a duplicate.
 */
fun handleOnGrab(
    event: OnGrabEvent,
    db: Database,
    priority: Int,
    dryRun: Boolean = false,
): Boolean {
    val now = Database.nowIsoOffset()
    val key = eventKey(event)

    if (!db.tryInsertDedupe(key, now)) return false

    if (!dryRun) {
        db.upsertManagedDownload(
            client = event.downloadClient,
            clientId = event.downloadId,
            seriesId = event.seriesId,
            episodeIds = event.episodeIds,
            initialPriority = priority.toLong(),
            currentPriority = priority.toLong(),
            pausedByUs = false,
            firstSeenAt = now,
            lastReconciledAt = now,
        )
    }

    db.appendAudit(
        action = "ongrab",
        seriesId = event.seriesId,
        client = event.downloadClient,
        clientId = event.downloadId,
        details = buildJsonObject {
            put("series_title", event.seriesTitle)
            put("episode_ids", kotlinx.serialization.json.buildJsonArray {
                event.episodeIds.forEach { add(it) }
            })
            put("priority", priority)
            put("dry_run", dryRun)
        },
    )
    return true
}
