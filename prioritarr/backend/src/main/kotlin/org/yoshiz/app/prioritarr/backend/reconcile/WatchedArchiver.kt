package org.yoshiz.app.prioritarr.backend.reconcile

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.config.ArchiveSettings
import org.yoshiz.app.prioritarr.backend.database.Database
import org.yoshiz.app.prioritarr.backend.priority.SeriesRef
import org.yoshiz.app.prioritarr.backend.priority.WatchHistoryProvider

/**
 * Sweeps the library and deletes **watched** episodes that fall
 * outside the "keep" window:
 *
 *   - Keep every episode in the series' **latest** season (by
 *     seasonNumber among aired+monitored).
 *   - If that season has more than [ArchiveSettings.latestSeasonMaxEpisodes]
 *     episodes, keep only the LAST N by episodeNumber.
 *   - Everything outside that window that has been watched AND has
 *     a file → delete file via Sonarr + unmonitor the episode so the
 *     backfill sweep doesn't immediately re-grab it.
 *   - Unwatched episodes are NEVER touched (user may still want to
 *     watch them).
 *
 * Specials (season 0) are ignored — they don't fit the "latest
 * season" model and users usually want them around regardless.
 *
 * dryRun=true enumerates every action without writing.
 */
class WatchedArchiver(
    private val sonarr: SonarrClient,
    private val watchProviders: List<WatchHistoryProvider>,
    private val db: Database,
) {
    private val logger = LoggerFactory.getLogger(WatchedArchiver::class.java)

    suspend fun sweep(settings: ArchiveSettings, dryRun: Boolean): ArchiveReport {
        if (!settings.watchedEnabled && !dryRun) {
            logger.info("watched-archiver: feature disabled, skipping")
            return ArchiveReport(0, 0, 0, 0, emptyList())
        }
        val allSeries = try {
            sonarr.getAllSeries()
        } catch (e: Exception) {
            logger.warn("watched-archiver: sonarr fetch failed: {}", e.message)
            return ArchiveReport(0, 0, 0, 0, emptyList())
        }

        var seriesVisited = 0
        var candidates = 0
        var deleted = 0
        var errors = 0
        val perSeriesEntries = mutableListOf<ArchiveReport.SeriesEntry>()

        for (seriesEl in allSeries) {
            val seriesObj = seriesEl.jsonObject
            val seriesId = seriesObj["id"]?.jsonPrimitive?.longOrNull ?: continue
            val title = seriesObj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val tvdb = seriesObj["tvdbId"]?.jsonPrimitive?.longOrNull
            val monitored = seriesObj["monitored"]?.jsonPrimitive?.booleanOrNull == true
            if (!monitored) continue  // respect Sonarr's monitored flag at series level
            seriesVisited++

            val episodes = try { sonarr.getEpisodes(seriesId) } catch (_: Exception) { continue }
            val ref = SeriesRef(seriesId, title, tvdb?.takeIf { it > 0 })

            val watched = fetchMergedWatchedSet(ref)

            val keep = computeKeepSet(episodes, settings.latestSeasonMaxEpisodes)

            val deletedHere = mutableListOf<String>()
            for (epEl in episodes) {
                val ep = epEl.jsonObject
                val epId = ep["id"]?.jsonPrimitive?.longOrNull ?: continue
                val seasonNum = ep["seasonNumber"]?.jsonPrimitive?.longOrNull?.toInt() ?: continue
                val epNum = ep["episodeNumber"]?.jsonPrimitive?.longOrNull?.toInt() ?: continue
                if (seasonNum == 0) continue  // specials stay
                if ((seasonNum to epNum) in keep) continue
                val hasFile = ep["hasFile"]?.jsonPrimitive?.booleanOrNull == true
                if (!hasFile) continue
                if ((seasonNum to epNum) !in watched) continue  // never touch unwatched

                candidates++
                val episodeFileId = ep["episodeFileId"]?.jsonPrimitive?.longOrNull
                if (episodeFileId == null || episodeFileId == 0L) continue
                val label = "S%02dE%02d".format(seasonNum, epNum)
                deletedHere += label

                logger.info("watched-archiver: series={} id={} {} delete+unmonitor{}",
                    title, seriesId, label, if (dryRun) " [DRY RUN]" else "")

                if (!dryRun) {
                    try {
                        // CRITICAL ORDER: unmonitor BEFORE deleting the
                        // file. If we delete first, Sonarr's "missing
                        // monitored episode" detection fires between
                        // the delete and the monitored=false flip and
                        // queues an immediate re-search + re-grab,
                        // undoing everything this sweep just did.
                        sonarr.setEpisodeMonitored(epId, false)
                        sonarr.deleteEpisodeFile(episodeFileId)
                        deleted++
                        try {
                            db.appendAudit(
                                action = "watched_archived",
                                seriesId = seriesId,
                                client = null,
                                clientId = null,
                                details = kotlinx.serialization.json.Json.parseToJsonElement(
                                    """{"episode":"$label","file_id":$episodeFileId}""",
                                ),
                            )
                        } catch (_: Exception) { /* audit best-effort */ }
                    } catch (e: Exception) {
                        errors++
                        logger.warn("watched-archiver: delete failed series={} {} : {}", title, label, e.message)
                    }
                } else {
                    deleted++  // would have deleted
                }
            }

            if (deletedHere.isNotEmpty()) {
                perSeriesEntries += ArchiveReport.SeriesEntry(
                    seriesId = seriesId,
                    title = title,
                    episodes = deletedHere,
                )
            }
        }

        logger.info(
            "watched-archiver: series={} candidates={} deleted={} errors={} dryRun={}",
            seriesVisited, candidates, deleted, errors, dryRun,
        )
        return ArchiveReport(
            seriesVisited = seriesVisited,
            candidates = candidates,
            deleted = deleted,
            errors = errors,
            entries = perSeriesEntries,
        )
    }

    /**
     * Compute which (season, episode) pairs fall inside the keep
     * window for a given [episodes] JSON array. Public internally
     * so tests can hit it directly without a Sonarr mock.
     */
    internal fun computeKeepSet(episodes: JsonArray, latestSeasonMaxEpisodes: Int): Set<Pair<Int, Int>> {
        // Find the latest aired season with at least one monitored
        // episode. "Latest" = max(seasonNumber > 0).
        val airedSeasons = episodes
            .mapNotNull { (it.jsonObject)["seasonNumber"]?.jsonPrimitive?.longOrNull?.toInt() }
            .filter { it > 0 }
        if (airedSeasons.isEmpty()) return emptySet()
        val latest = airedSeasons.max()

        // Keep every episode in the latest season unless it has > N
        // episodes, in which case keep only the last N.
        val latestEpisodes = episodes
            .map { it.jsonObject }
            .filter { (it["seasonNumber"]?.jsonPrimitive?.longOrNull?.toInt() ?: -1) == latest }
            .mapNotNull { (it["episodeNumber"]?.jsonPrimitive?.longOrNull?.toInt()) }
            .sorted()
        val keepEpisodes = if (latestEpisodes.size > latestSeasonMaxEpisodes) {
            latestEpisodes.takeLast(latestSeasonMaxEpisodes)
        } else {
            latestEpisodes
        }
        return keepEpisodes.map { latest to it }.toSet()
    }

    /** Union of (season, episode) pairs any watch provider reports as watched. */
    private suspend fun fetchMergedWatchedSet(ref: SeriesRef): Set<Pair<Int, Int>> {
        if (watchProviders.isEmpty()) return emptySet()
        val results = coroutineScope {
            watchProviders.map { p -> async { p.historyFor(ref).getOrNull().orEmpty() } }.awaitAll()
        }
        val out = HashSet<Pair<Int, Int>>()
        for (list in results) for (ev in list) out += ev.season to ev.episode
        return out
    }
}

@Serializable
data class ArchiveReport(
    val seriesVisited: Int,
    val candidates: Int,
    val deleted: Int,
    val errors: Int,
    val entries: List<SeriesEntry>,
) {
    @Serializable
    data class SeriesEntry(
        val seriesId: Long,
        val title: String,
        val episodes: List<String>,
    )
}
