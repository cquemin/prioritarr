package org.yoshiz.app.prioritarr.backend.priority

import java.time.Instant

/**
 * Snapshot of a Sonarr series combined with Tautulli watch history.
 *
 * "Monitored" everywhere in this type means *both* the season is
 * monitored AND the episode is monitored — unmonitored seasons/episodes
 * are fully excluded from [monitoredEpisodesAired] / [monitoredEpisodesWatched],
 * so unmonitored content never influences the priority computation.
 */
data class SeriesSnapshot(
    val seriesId: Long,
    val title: String,
    val tvdbId: Long,
    val monitoredSeasons: Int,
    val monitoredEpisodesAired: Int,
    val monitoredEpisodesWatched: Int,
    val lastWatchedAt: Instant?,
    val episodeReleaseDate: Instant?,
    val previousEpisodeReleaseDate: Instant?,
)

/** Computed priority for a single series. Mirrors PriorityResult in models.py. */
data class PriorityResult(
    val priority: Int, // 1..5
    val label: String,
    val reason: String,
)
