package org.yoshiz.app.prioritarr.backend.series

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.database.Database

private val logger = LoggerFactory.getLogger("org.yoshiz.app.prioritarr.backend.series")

/**
 * Refresh the local series_cache table from Sonarr's /series. v2 list
 * endpoints read exclusively from this table so every UI request is
 * served from local SQLite, not a 7-second Sonarr round-trip.
 *
 * Wrapped in a transaction so readers either see the old snapshot or
 * the new one, never mid-update.
 */
suspend fun refreshSeriesCache(sonarr: SonarrClient, db: Database): Int {
    val all = try {
        sonarr.getAllSeries()
    } catch (e: Exception) {
        logger.warn("refreshSeriesCache: sonarr fetch failed: {}", e.message)
        return 0
    }
    val now = Database.nowIsoOffset()
    var n = 0
    db.q.transaction {
        db.q.deleteAllSeriesCache()
        for (el in all) {
            val obj = el.jsonObject
            val id = obj["id"]?.jsonPrimitive?.longOrNull ?: continue
            db.q.upsertSeriesCache(
                id = id,
                title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                tvdb_id = obj["tvdbId"]?.jsonPrimitive?.longOrNull,
                path = obj["path"]?.jsonPrimitive?.contentOrNull,
                updated_at = now,
            )
            n++
        }
    }
    logger.info("refreshSeriesCache: cached {} series", n)
    return n
}
