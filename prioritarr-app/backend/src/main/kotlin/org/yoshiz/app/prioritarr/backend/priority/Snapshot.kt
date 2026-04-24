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

/**
 * Bundle returned by [PriorityService.preview] — the actual
 * computed priority, the snapshot that produced it, and the
 * thresholds that were in effect. The UI reads every field so the
 * sandbox can render a complete what-if breakdown.
 */
data class PriorityPreview(
    val snapshot: SeriesSnapshot,
    val result: PriorityResult,
    val thresholds: org.yoshiz.app.prioritarr.backend.config.PriorityThresholds,
)

/**
 * Watched-episode count per watch-history provider, per series.
 * [ok]=false with [errorMessage] when the provider's fetch blew up;
 * the UI still renders the row with a visible "unreachable" state.
 */
@kotlinx.serialization.Serializable
data class ProviderWatchStatus(
    val source: String,
    val ok: Boolean,
    val watchedEpisodeCount: Int,
    val lastWatchedAt: String?,
    val errorMessage: String? = null,
)
