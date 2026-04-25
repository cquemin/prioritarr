package org.yoshiz.app.prioritarr.backend.priority

import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.clients.PlexClient
import org.yoshiz.app.prioritarr.backend.mapping.MappingState
import java.time.Instant

/**
 * Pulls episode watch state directly from Plex Media Server.
 *
 * Why this exists separately from Tautulli: Tautulli only knows what
 * was *observed* by its history listener. If a series was already
 * marked watched on Plex before Tautulli was installed (or before its
 * notification webhook was wired up), Tautulli has no record. Plex
 * itself, on the other hand, always knows the current viewCount /
 * lastViewedAt per episode regardless of how it got there.
 *
 * The Wistoria P4 bug was the canonical case: Plex showed every
 * episode watched, Tautulli's history was empty, and the union of the
 * two sources was therefore "nothing watched".
 *
 * Source value on emitted events: "plex".
 */
class PlexHistoryProvider(
    private val plex: PlexClient,
    private val mappings: MappingState,
) : WatchHistoryProvider {

    override val name: String = "plex"
    private val logger = LoggerFactory.getLogger(PlexHistoryProvider::class.java)

    override suspend fun historyFor(ref: SeriesRef): Result<List<WatchEvent>> = runCatching {
        // Plex needs a per-show ratingKey; reuse the title→plex_key
        // mapping the Tautulli pipeline already populates. No mapping
        // means we don't know which Plex show this Sonarr series is —
        // skip rather than guess.
        val plexKey = mappings.plexKeyForSeriesTitle(ref.title) ?: return@runCatching emptyList()

        val rows = plex.getShowEpisodesWatchStatus(plexKey)
        rows.mapNotNull { row ->
            val watched = row["watched"] as? Boolean ?: false
            if (!watched) return@mapNotNull null
            val season = row["season"] as? Int ?: return@mapNotNull null
            val episode = row["episode"] as? Int ?: return@mapNotNull null
            // lastViewedAt missing on a watched episode shouldn't happen
            // in practice (Plex stamps it whenever viewCount > 0), but
            // fall back to "now" so we still emit the event rather than
            // dropping it — the watched flag is what downstream cares
            // about, the timestamp is only a tie-breaker.
            val lastViewed = (row["last_viewed_at"] as? Long)?.let { Instant.ofEpochSecond(it) }
                ?: Instant.now()
            WatchEvent(
                season = season,
                episode = episode,
                watchedAt = lastViewed,
                source = name,
            )
        }
    }.onFailure {
        logger.info("plex history failed for series {}: {}", ref.seriesId, it.message)
    }
}
