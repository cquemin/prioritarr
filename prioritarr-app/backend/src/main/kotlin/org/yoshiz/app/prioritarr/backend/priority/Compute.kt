package org.yoshiz.app.prioritarr.backend.priority

import org.yoshiz.app.prioritarr.backend.config.PriorityThresholds
import java.time.Duration
import java.time.Instant

/**
 * Compute P1..P5 for a series. First matching bucket wins — identical
 * branch ordering to prioritarr/priority.py::compute_priority.
 *
 * [now] is injected for testability; the production main path uses
 * [Instant.now] as in the python implementation.
 */
fun computePriority(
    snap: SeriesSnapshot,
    t: PriorityThresholds,
    now: Instant = Instant.now(),
): PriorityResult {
    val aired = snap.monitoredEpisodesAired
    val watched = snap.monitoredEpisodesWatched

    val watchPct = if (aired > 0) watched.toDouble() / aired.toDouble() else 0.0
    val unwatched = aired - watched
    val daysSinceWatch = snap.lastWatchedAt?.let { daysBetween(it, now) }
    val daysSinceRelease = snap.episodeReleaseDate?.let { daysBetween(it, now) }
    val isPostHiatus = isHiatus(snap, t.p1HiatusGapDays)

    // P1: Live-following
    if (watchPct >= t.p1WatchPctMin &&
        daysSinceWatch != null && daysSinceWatch <= t.p1DaysSinceWatchMax &&
        daysSinceRelease != null &&
        (daysSinceRelease <= t.p1DaysSinceReleaseMax ||
            (isPostHiatus && daysSinceRelease <= t.p1HiatusReleaseWindowDays))
    ) {
        return PriorityResult(
            priority = 1,
            label = "P1 Live-following",
            reason = "watch=${pct(watchPct)}, last_watch=${daysSinceWatch}d, release=${daysSinceRelease}d, hiatus=$isPostHiatus",
        )
    }

    // P2: Caught-up but lapsed
    if (watchPct >= t.p1WatchPctMin &&
        daysSinceWatch != null &&
        daysSinceWatch > t.p1DaysSinceWatchMax &&
        daysSinceWatch <= t.p2DaysSinceWatchMax
    ) {
        return PriorityResult(
            priority = 2,
            label = "P2 Caught-up but lapsed",
            reason = "watch=${pct(watchPct)}, last_watch=${daysSinceWatch}d",
        )
    }

    // P3: A few unwatched
    if (unwatched in 1..t.p3UnwatchedMax &&
        daysSinceWatch != null && daysSinceWatch <= t.p3DaysSinceWatchMax
    ) {
        return PriorityResult(
            priority = 3,
            label = "P3 A few unwatched",
            reason = "unwatched=$unwatched, last_watch=${daysSinceWatch}d",
        )
    }

    // P4: Partial backfill
    if (watched >= t.p4MinWatched && unwatched > t.p3UnwatchedMax) {
        return PriorityResult(
            priority = 4,
            label = "P4 Partial backfill",
            reason = "watched=$watched, unwatched=$unwatched",
        )
    }

    // P5: Catch-all
    return PriorityResult(
        priority = 5,
        label = "P5 Full backfill / dormant",
        reason = "watched=$watched of aired=$aired across ${snap.monitoredSeasons} monitored season(s), last_watch=$daysSinceWatch",
    )
}

private fun isHiatus(snap: SeriesSnapshot, gapDays: Int): Boolean {
    val current = snap.episodeReleaseDate ?: return false
    val previous = snap.previousEpisodeReleaseDate ?: return false
    return daysBetween(previous, current) >= gapDays
}

private fun daysBetween(from: Instant, to: Instant): Int =
    Duration.between(from, to).toDays().toInt()

/** Matches Python's `f"{watch_pct:.0%}"` — zero decimals, percent sign. */
private fun pct(value: Double): String =
    "${(value * 100).toInt()}%"
