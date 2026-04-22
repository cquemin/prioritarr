package org.yoshiz.app.prioritarr.backend.priority

import org.yoshiz.app.prioritarr.backend.config.PriorityThresholds
import java.time.Duration
import java.time.Instant

/**
 * Compute P1..P5 for a series. First matching bucket wins.
 *
 * P1..P3 use OR-combined engagement gates: a series counts as
 * "engaged" when *either* the watch-percent threshold is met *or* the
 * absolute unwatched episode count is small. This avoids two failure
 * modes of the single-metric rule:
 *   - short shows where 3 unwatched is 25% and fails pct-only gates
 *   - long shows where 5 unwatched is 3% and passes pct-only gates
 *     but fails count-only gates
 *
 * Release recency still gates P1 specifically — P1 means "new episodes
 * are dropping and the user is keeping up", so it requires both recent
 * viewing *and* a recent release (or a post-hiatus release within the
 * widened window).
 *
 * [now] is injected for testability.
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

    // Engagement gates — separate thresholds per band so a user who
    // wants P1 strict-90% but P3 lenient-60% can dial each in.
    val engagedP1 = watchPct >= t.p1WatchPctMin || (unwatched in 1..t.p3UnwatchedMax)
    val engagedP2 = watchPct >= t.p2WatchPctMin || (unwatched in 1..t.p3UnwatchedMax)
    val engagedP3 = watchPct >= t.p3WatchPctMin || (unwatched in 1..t.p3UnwatchedMax)

    val releaseOpen = daysSinceRelease != null && (
        daysSinceRelease <= t.p1DaysSinceReleaseMax ||
            (isPostHiatus && daysSinceRelease <= t.p1HiatusReleaseWindowDays)
        )

    // P1 requires you to also actually be watching — zero-watch on a
    // fresh-release show isn't "live-following", it's "not started yet".
    val p1NotIdle = watched > 0

    // P1: Live-following
    if (engagedP1 && p1NotIdle &&
        daysSinceWatch != null && daysSinceWatch <= t.p1DaysSinceWatchMax &&
        releaseOpen
    ) {
        return PriorityResult(
            priority = 1,
            label = "P1 Live-following",
            reason = "watch=${pct(watchPct)}, unwatched=$unwatched, " +
                "last_watch=${daysSinceWatch}d, release=${daysSinceRelease}d, hiatus=$isPostHiatus",
        )
    }

    // P2: Caught-up (or nearly so) but lapsed
    if (engagedP2 &&
        daysSinceWatch != null &&
        daysSinceWatch > t.p1DaysSinceWatchMax &&
        daysSinceWatch <= t.p2DaysSinceWatchMax
    ) {
        return PriorityResult(
            priority = 2,
            label = "P2 Caught-up but lapsed",
            reason = "watch=${pct(watchPct)}, unwatched=$unwatched, last_watch=${daysSinceWatch}d",
        )
    }

    // P3: A few unwatched (or a high pct) — actively watching.
    if (engagedP3 &&
        daysSinceWatch != null && daysSinceWatch <= t.p3DaysSinceWatchMax
    ) {
        return PriorityResult(
            priority = 3,
            label = "P3 A few unwatched",
            reason = "watch=${pct(watchPct)}, unwatched=$unwatched, last_watch=${daysSinceWatch}d",
        )
    }

    // P4: Partial backfill — some progress, but neither engagement gate
    // opens at all. A "mostly caught up but dormant for months" series
    // is NOT P4 — it lands in P5 because prioritarr treats it as
    // abandoned, not as a fresh-eyes backfill candidate.
    val everEngaged = watchPct >= t.p3WatchPctMin || (unwatched in 1..t.p3UnwatchedMax)
    if (watched >= t.p4MinWatched && !everEngaged) {
        return PriorityResult(
            priority = 4,
            label = "P4 Partial backfill",
            reason = "watched=$watched, unwatched=$unwatched, watch=${pct(watchPct)}",
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
