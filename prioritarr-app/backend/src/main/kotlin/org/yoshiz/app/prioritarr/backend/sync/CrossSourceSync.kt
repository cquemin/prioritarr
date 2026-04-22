package org.yoshiz.app.prioritarr.backend.sync

import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.clients.PlexClient
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.clients.TraktClient
import org.yoshiz.app.prioritarr.backend.mapping.MappingState
import org.yoshiz.app.prioritarr.backend.schemas.EpisodeRef
import org.yoshiz.app.prioritarr.backend.schemas.SeriesSyncReport
import java.time.Instant

/**
 * Per-series and library-wide watch-state mirroring between Plex and
 * Trakt. The downstream priority engine already merges both sources by
 * union, so the user-visible priority can never go *down* from running
 * a sync — but the user's own Plex / Trakt UIs do diverge until we
 * push, and they want both apps to reflect the same truth.
 *
 * Symmetry: anything Plex has watched but Trakt doesn't gets POSTed to
 * Trakt /sync/history; anything Trakt has watched but Plex doesn't
 * gets scrobbled to Plex (one PUT per episode — Plex has no batch
 * scrobble). Errors on individual episodes are collected, not fatal.
 *
 * Dependencies:
 *   - plex + Plex mapping → required for Plex side. Missing → skip.
 *   - trakt + tvdb id     → required for Trakt side. Missing → skip.
 * One missing side disables that direction; the other still runs.
 */
class CrossSourceSync(
    private val sonarr: SonarrClient,
    private val plex: PlexClient?,
    private val trakt: TraktClient?,
    private val mappings: MappingState,
) {
    private val logger = LoggerFactory.getLogger(CrossSourceSync::class.java)

    /**
     * Sync one series. Looks up the Sonarr title + tvdbId, the Plex
     * key from the mapping table, and the Trakt show id via search.
     * Returns a report — never throws on per-episode failures (those
     * land in `errors`). A whole-side failure (e.g. Plex 502 on the
     * initial fetch) is logged + counted as 0 added on that side.
     *
     * `dryRun=true` plans the diff and returns counts but skips both
     * the Plex scrobbles and the Trakt POST. Logs what would happen.
     */
    suspend fun syncSeries(seriesId: Long, dryRun: Boolean): SeriesSyncReport {
        val sonarrSeries = try {
            sonarr.getSeries(seriesId)
        } catch (e: Exception) {
            return SeriesSyncReport(
                seriesId = seriesId,
                title = "(unknown)",
                skippedReason = "sonarr lookup failed: ${e.message}",
            )
        }
        val title = (sonarrSeries["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        val tvdbId = (sonarrSeries["tvdbId"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()

        val errors = mutableListOf<String>()

        // ---- pull Plex side (one fetch — yields both watched-set and ratingKey index) ----
        val plexKey = mappings.plexKeyForSeriesTitle(title)
        val plexEpisodes: List<Map<String, Any?>> = if (plex != null && plexKey != null) {
            try {
                plex.getShowEpisodesWatchStatus(plexKey)
            } catch (e: Exception) {
                errors += "plex fetch failed: ${e.message}"
                emptyList()
            }
        } else emptyList()
        val plexWatched: Set<Pair<Int, Int>> = plexEpisodes
            .filter { (it["watched"] as? Boolean) == true }
            .mapNotNull {
                val s = it["season"] as? Int ?: return@mapNotNull null
                val e = it["episode"] as? Int ?: return@mapNotNull null
                s to e
            }.toSet()
        val plexRatingKeyByEpisode: Map<Pair<Int, Int>, String> = plexEpisodes
            .mapNotNull {
                val s = it["season"] as? Int ?: return@mapNotNull null
                val e = it["episode"] as? Int ?: return@mapNotNull null
                val rk = it["rating_key"] as? String ?: return@mapNotNull null
                (s to e) to rk
            }.toMap()

        // ---- pull Trakt side ----
        val (traktShowId, traktWatched) = if (trakt != null && tvdbId != null) {
            try {
                val showId = trakt.searchShowByTvdb(tvdbId)
                if (showId == null) {
                    null to emptySet<Pair<Int, Int>>()
                } else {
                    val raw = trakt.getShowHistory(showId, limit = 1000)
                    val set = raw.mapNotNull { el ->
                        val obj = (el as? kotlinx.serialization.json.JsonObject) ?: return@mapNotNull null
                        val ep = (obj["episode"] as? kotlinx.serialization.json.JsonObject) ?: return@mapNotNull null
                        val s = (ep["season"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: return@mapNotNull null
                        val e = (ep["number"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: return@mapNotNull null
                        s to e
                    }.toSet()
                    showId to set
                }
            } catch (e: Exception) {
                errors += "trakt fetch failed: ${e.message}"
                null to emptySet()
            }
        } else null to emptySet()

        // Skip-with-reason short-circuit: nothing on either side is
        // resolvable, surface that in the report instead of silently
        // returning zeros.
        if ((plex == null || plexKey == null) && (trakt == null || tvdbId == null)) {
            return SeriesSyncReport(
                seriesId = seriesId,
                title = title,
                skippedReason = "no plex mapping and no trakt-resolvable tvdb id",
            )
        }

        // ---- diff ----
        val toPushToPlex = (traktWatched - plexWatched).toList().sortedWith(compareBy({ it.first }, { it.second }))
        val toPushToTrakt = (plexWatched - traktWatched).toList().sortedWith(compareBy({ it.first }, { it.second }))

        var plexAdded = 0
        var traktAdded = 0
        // Track episodes we *attempted* to push, regardless of outcome.
        // The frontend uses these for the per-series detail breakdown.
        val pushedToPlex = mutableListOf<EpisodeRef>()
        val pushedToTrakt = mutableListOf<EpisodeRef>()

        // ---- write to Plex ----
        if (plex != null && plexKey != null && toPushToPlex.isNotEmpty()) {
            for ((s, e) in toPushToPlex) {
                val rk = plexRatingKeyByEpisode[s to e]
                if (rk == null) {
                    errors += "plex: no rating_key for s${s}e${e}"
                    continue
                }
                pushedToPlex += EpisodeRef(season = s, number = e)
                if (!dryRun) {
                    try {
                        plex.markEpisodeWatched(rk)
                        plexAdded++
                    } catch (ex: Exception) {
                        errors += "plex scrobble s${s}e${e}: ${ex.message}"
                    }
                } else {
                    plexAdded++
                }
            }
        }

        // ---- write to Trakt ----
        if (trakt != null && traktShowId != null && toPushToTrakt.isNotEmpty()) {
            for ((s, e) in toPushToTrakt) pushedToTrakt += EpisodeRef(season = s, number = e)
            if (!dryRun) {
                try {
                    val resp = trakt.addEpisodesToHistory(traktShowId, toPushToTrakt, Instant.now())
                    val added = (resp["added"] as? kotlinx.serialization.json.JsonObject)
                        ?.get("episodes") as? kotlinx.serialization.json.JsonPrimitive
                    traktAdded = added?.content?.toIntOrNull() ?: toPushToTrakt.size
                } catch (ex: Exception) {
                    errors += "trakt sync/history: ${ex.message}"
                }
            } else {
                traktAdded = toPushToTrakt.size
            }
        }

        logger.info(
            "sync series id={} title='{}' plex+={} trakt+={} dryRun={} errors={}",
            seriesId, title, plexAdded, traktAdded, dryRun, errors.size,
        )

        return SeriesSyncReport(
            seriesId = seriesId,
            title = title,
            plexAdded = plexAdded,
            traktAdded = traktAdded,
            pushedToPlex = pushedToPlex,
            pushedToTrakt = pushedToTrakt,
            errors = errors,
            skippedReason = null,
        )
    }
}
