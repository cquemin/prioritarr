package org.yoshiz.app.prioritarr.backend.priority

import java.time.Instant

/**
 * Single episode watch event — the minimum we actually care about
 * across all upstream sources. Tautulli gives us this via its
 * get_history API; Trakt gives us this via /sync/history. Anything
 * richer a specific source offers (scrobble vs checkin, device,
 * resume-point, etc.) is stripped here because prioritarr's downstream
 * logic only cares about "did this episode get watched, and when".
 *
 * [absoluteEpisode] is the absolute episode number when the source
 * exposes it (Trakt does, via `episode.number_abs`; Tautulli/Plex
 * does not). Used as a fallback identifier when (season, episode)
 * doesn't match Sonarr's numbering — e.g. long-running anime where
 * Trakt's per-arc seasons drift from TVDB/Sonarr's cours-based
 * seasons. PriorityService canonicalises against Sonarr's actual
 * monitored-aired set; events that don't match by either key are
 * dropped.
 */
data class WatchEvent(
    val season: Int,
    val episode: Int,
    val watchedAt: Instant,
    /** "tautulli" or "trakt" — used for attribution in logs / UI. */
    val source: String,
    val absoluteEpisode: Int? = null,
)

/**
 * Series identity passed to providers. Carries every id form we have
 * on hand; each provider picks the ones it knows how to look up.
 * Tautulli resolves via plex_key (through the mapping state); Trakt
 * resolves via tvdb id (native lookup).
 */
data class SeriesRef(
    val seriesId: Long,
    val title: String,
    val tvdbId: Long?,
)

/**
 * Pluggable watch-history source. Must be safe to call from any
 * coroutine context. Implementations return [Result] so we can
 * distinguish "empty history" from "fetch failed" — the priority
 * service treats them differently (empty → watched=0, failure →
 * degrade to dependency_unreachable when *every* provider fails).
 */
interface WatchHistoryProvider {
    /** Short identifier used in logs + the [WatchEvent.source] field. */
    val name: String

    suspend fun historyFor(ref: SeriesRef): Result<List<WatchEvent>>
}
