package org.yoshiz.app.prioritarr.backend.series

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.database.Database

private val logger = LoggerFactory.getLogger("org.yoshiz.app.prioritarr.backend.series.EpisodeCacheRefresh")

/**
 * Pull every monitored episode title for every cached series and
 * replace the episode_cache table. Feeds the Series page's global
 * search box (matches title OR episode).
 *
 * Concurrency: up to [parallelism] Sonarr fetches in flight at once —
 * Sonarr's API handles moderate parallelism fine but 150 simultaneous
 * requests would trip its internal rate limiter.
 *
 * Transaction strategy: one per series. A 150-series lock would block
 * reads for seconds; per-series scopes keep each lock at ~50 rows max.
 */
suspend fun refreshEpisodeCache(
    sonarr: SonarrClient,
    db: Database,
    parallelism: Int = 6,
): Int {
    val seriesRows = db.q.listSeriesCache().executeAsList()
    if (seriesRows.isEmpty()) {
        logger.info("refreshEpisodeCache: no series cached yet, skipping")
        return 0
    }
    val now = Database.nowIsoOffset()
    var totalEpisodes = 0

    // Chunk the series list so we bound in-flight requests without
    // needing a Semaphore. Each chunk is fetched concurrently, results
    // applied serially to the DB.
    for (chunk in seriesRows.chunked(parallelism)) {
        val fetched = coroutineScope {
            chunk.map { row ->
                async {
                    row.id to try {
                        sonarr.getEpisodes(row.id)
                    } catch (e: Exception) {
                        logger.warn("refreshEpisodeCache: getEpisodes failed for series {}: {}", row.id, e.message)
                        null
                    }
                }
            }.awaitAll()
        }
        db.q.transaction {
            for ((seriesId, episodes) in fetched) {
                if (episodes == null) continue
                db.q.deleteEpisodeCacheForSeries(seriesId)
                for (epEl in episodes) {
                    val ep = epEl.jsonObject
                    // Only monitored episodes. Unmonitored entries are
                    // usually specials / announcements — searching them
                    // pollutes results without useful hits.
                    if (ep["monitored"]?.jsonPrimitive?.booleanOrNull != true) continue
                    val season = ep["seasonNumber"]?.jsonPrimitive?.intOrNull ?: continue
                    val number = ep["episodeNumber"]?.jsonPrimitive?.intOrNull ?: continue
                    val title = ep["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (title.isEmpty()) continue
                    db.q.upsertEpisodeCache(
                        series_id = seriesId,
                        season_number = season.toLong(),
                        episode_number = number.toLong(),
                        title = title,
                        updated_at = now,
                    )
                    totalEpisodes++
                }
            }
        }
    }
    logger.info(
        "refreshEpisodeCache: cached {} episodes across {} series",
        totalEpisodes, seriesRows.size,
    )
    return totalEpisodes
}
