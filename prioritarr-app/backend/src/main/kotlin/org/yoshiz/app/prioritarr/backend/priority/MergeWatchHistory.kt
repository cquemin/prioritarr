package org.yoshiz.app.prioritarr.backend.priority

/**
 * Collapse per-source history lists into one deduplicated list.
 *
 * A single (season, episode) pair is one "watched episode" regardless
 * of how many sources reported a watch for it, and how many times
 * each source reported it (rewatches show up as multiple events in
 * Tautulli and Trakt alike). We keep the newest timestamp across all
 * events for that pair — that's what downstream priority compute
 * reads as `lastWatchedAt`.
 *
 * Attribution: when multiple sources report the same pair, the source
 * of the *latest* watched_at wins. This is informational only
 * (surfaces in logs), it has no impact on the priority calculation.
 */
fun mergeWatchHistory(perSource: List<List<WatchEvent>>): List<WatchEvent> {
    if (perSource.isEmpty()) return emptyList()
    val byEpisode = HashMap<Pair<Int, Int>, WatchEvent>()
    for (source in perSource) {
        for (event in source) {
            val key = event.season to event.episode
            val existing = byEpisode[key]
            if (existing == null || event.watchedAt.isAfter(existing.watchedAt)) {
                byEpisode[key] = event
            }
        }
    }
    return byEpisode.values.toList()
}
