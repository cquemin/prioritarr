package org.cquemin.prioritarr.priority

import java.time.Instant

/** Snapshot of a Sonarr series combined with Tautulli watch history. */
data class SeriesSnapshot(
    val seriesId: Long,
    val title: String,
    val tvdbId: Long,
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
