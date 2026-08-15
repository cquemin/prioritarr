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
/**
 * Ceiling on how long a priority computed from *incomplete* watch
 * history may be cached. A degraded entry is still useful (better than
 * no priority at all) but must expire quickly so the series is
 * re-scored once the failing provider recovers.
 */
const val DEGRADED_PRIORITY_CACHE_TTL_MINUTES: Long = 5

/**
 * Result of asking every configured watch provider for a series'
 * history.
 *
 * [events] is null only when *every* provider failed — the caller turns
 * that into dependency_unreachable. [degraded] is true whenever at
 * least one configured provider failed, including the all-failed case;
 * it exists so a priority built on partial data is never mistaken for a
 * complete one.
 */
data class ProviderFetchOutcome(
    val events: List<WatchEvent>?,
    val degraded: Boolean,
)

/** Collapse per-provider results, tracking whether any of them failed. */
fun mergeProviderResults(
    results: List<Pair<String, Result<List<WatchEvent>>>>,
): ProviderFetchOutcome {
    if (results.isEmpty()) return ProviderFetchOutcome(emptyList(), degraded = false)
    val successes = results.mapNotNull { (_, res) -> res.getOrNull() }
    if (successes.isEmpty()) return ProviderFetchOutcome(events = null, degraded = true)
    return ProviderFetchOutcome(
        events = mergeWatchHistory(successes),
        degraded = successes.size != results.size,
    )
}

/** Cache lifetime for a computed priority, shortened when [degraded]. */
fun priorityCacheTtlMinutes(baseTtlMinutes: Long, degraded: Boolean): Long =
    if (degraded) minOf(baseTtlMinutes, DEGRADED_PRIORITY_CACHE_TTL_MINUTES) else baseTtlMinutes

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
