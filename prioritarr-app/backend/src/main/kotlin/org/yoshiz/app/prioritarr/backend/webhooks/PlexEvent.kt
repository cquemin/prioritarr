package org.yoshiz.app.prioritarr.backend.webhooks

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.yoshiz.app.prioritarr.backend.database.Database

data class WatchedEvent(
    val showTitle: String,
    val plexShowKey: String,
    val season: Int,
    val episode: Int,
    val ratingKey: String,
)

fun parseTautulliWatched(data: JsonObject): WatchedEvent {
    fun intField(key: String): Int =
        data[key]?.jsonPrimitive?.let {
            it.intOrNull ?: it.contentOrNull?.toIntOrNull()
        } ?: 0

    return WatchedEvent(
        showTitle = data["grandparent_title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        plexShowKey = data["grandparent_rating_key"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        season = intField("parent_media_index"),
        episode = intField("media_index"),
        ratingKey = data["rating_key"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    )
}

/** Invalidate priority cache + log audit. Mirrors handle_watched in python. */
fun handleWatched(seriesId: Long, db: Database) {
    db.invalidatePriorityCache(seriesId)
    db.appendAudit(
        action = "cache_invalidated",
        seriesId = seriesId,
        details = buildJsonObject { put("reason", "tautulli_watched") },
    )
}
