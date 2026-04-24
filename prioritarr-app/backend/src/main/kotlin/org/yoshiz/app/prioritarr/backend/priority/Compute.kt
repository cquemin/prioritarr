package org.yoshiz.app.prioritarr.backend.priority

import org.yoshiz.app.prioritarr.backend.config.PriorityThresholds
import java.time.Duration
import java.time.Instant

/**
 * Compute P1..P5 for a series. First matching branch wins.
 *
 * Two "operational" gates wrap the engagement evaluation so the bands
 * reflect what prioritarr can actually *do* about a series, not just
 * how engaged the user is:
 *
 *   1. **Nothing-to-download short-circuit** — if every aired monitored
 *      episode has a file and [PriorityThresholds.p5WhenNothingToDownload]
 *      is on, collapse to P5. A fully-caught-up, fully-downloaded show
 *      shouldn't hold a queue slot that P1/P2/P3 implies; there's
 *      nothing for Sonarr to grab until the next episode drops.
 *   2. **Returning-from-dormancy rescue** — when the user has been away
 *      past the P2 lapse window but a fresh episode landed recently,
 *      the show was previously a P5 "dormant" case. New content +
 *      prior engagement = P3 "returning from dormancy" so the download
 *      queues up rather than sitting forever.
 *
 * P1/P2/P3 all use OR-combined engagement gates on watch-pct OR
 * absolute unwatched count so neither short shows (high absolute %
 * per episode) nor long shows (low pct per missing episode) get
 * stuck in the wrong band.
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
    val missing = snap.monitoredMissingEpisodes

    val watchPct = if (aired > 0) watched.toDouble() / aired.toDouble() else 0.0
    val unwatched = aired - watched
    val daysSinceWatch = snap.lastWatchedAt?.let { daysBetween(it, now) }
    val daysSinceRelease = snap.episodeReleaseDate?.let { daysBetween(it, now) }
    val isPostHiatus = isHiatus(snap, t.p1HiatusGapDays)

    // ---- Gate 0: nothing to download ----
    // "No missing episodes" = every monitored-aired episode has a
    // file. With the toggle on (default), that alone puts the series
    // in P5 — engagement doesn't matter, there's nothing Sonarr can
    // usefully grab until a new episode airs.
    if (t.p5WhenNothingToDownload && aired > 0 && missing == 0) {
        return PriorityResult(
            priority = 5,
            label = "P5 Nothing to download",
            reason = "watched=$watched, aired=$aired, missing=0, status=fully_downloaded",
        )
    }

    // Engagement gates — OR-combined across watch-pct and absolute count.
    val engagedP1 = watchPct >= t.p1WatchPctMin || (unwatched in 1..t.p3UnwatchedMax)
    val engagedP2 = watchPct >= t.p2WatchPctMin || (unwatched in 1..t.p3UnwatchedMax)
    val engagedP3 = watchPct >= t.p3WatchPctMin || (unwatched in 1..t.p3UnwatchedMax)

    val releaseOpen = daysSinceRelease != null && (
        daysSinceRelease <= t.p1DaysSinceReleaseMax ||
            (isPostHiatus && daysSinceRelease <= t.p1HiatusReleaseWindowDays)
        )
    val p1NotIdle = watched > 0

    // P1: Live-following — new content + actively watching.
    if (engagedP1 && p1NotIdle &&
        daysSinceWatch != null && daysSinceWatch <= t.p1DaysSinceWatchMax &&
        releaseOpen
    ) {
        return PriorityResult(
            priority = 1,
            label = "P1 Live-following",
            reason = "watch=${pct(watchPct)}, unwatched=$unwatched, missing=$missing, " +
                "last_watch=${daysSinceWatch}d, release=${daysSinceRelease}d, hiatus=$isPostHiatus",
        )
    }

    // P2: Caught-up (or nearly so) but lapsed.
    if (engagedP2 &&
        daysSinceWatch != null &&
        daysSinceWatch > t.p1DaysSinceWatchMax &&
        daysSinceWatch <= t.p2DaysSinceWatchMax
    ) {
        return PriorityResult(
            priority = 2,
            label = "P2 Caught-up but lapsed",
            reason = "watch=${pct(watchPct)}, unwatched=$unwatched, missing=$missing, last_watch=${daysSinceWatch}d",
        )
    }

    // P3: A few unwatched or high pct — actively watching.
    if (engagedP3 &&
        daysSinceWatch != null && daysSinceWatch <= t.p3DaysSinceWatchMax
    ) {
        return PriorityResult(
            priority = 3,
            label = "P3 A few unwatched",
            reason = "watch=${pct(watchPct)}, unwatched=$unwatched, missing=$missing, last_watch=${daysSinceWatch}d",
        )
    }

    // P3b: Dormant show but fresh episode landed. Requires the user
    // to have been historically engaged (watchPct >= p2 floor) AND
    // something actionable (missing > 0) AND a recent-ish release.
    // Without this branch, a show you finished last year pops a new
    // episode and stays in P5 forever — this is the "welcome back"
    // rescue band.
    if (t.p3DormantReleaseWindowDays > 0 &&
        missing > 0 &&
        watchPct >= t.p2WatchPctMin &&
        daysSinceRelease != null &&
        daysSinceRelease <= t.p3DormantReleaseWindowDays &&
        (daysSinceWatch == null || daysSinceWatch > t.p3DaysSinceWatchMax)
    ) {
        return PriorityResult(
            priority = 3,
            label = "P3 Returning from dormancy",
            reason = "watch=${pct(watchPct)}, unwatched=$unwatched, missing=$missing, " +
                "release=${daysSinceRelease}d, last_watch=${daysSinceWatch ?: "never"}d, status=returning",
        )
    }

    // P4: Partial backfill — some progress, neither engagement gate
    // opens, and there's a file to chase. "Mostly caught up but
    // dormant for months" stays in P5 via the everEngaged guard.
    val everEngaged = watchPct >= t.p3WatchPctMin || (unwatched in 1..t.p3UnwatchedMax)
    if (watched >= t.p4MinWatched && !everEngaged) {
        return PriorityResult(
            priority = 4,
            label = "P4 Partial backfill",
            reason = "watched=$watched, unwatched=$unwatched, missing=$missing, watch=${pct(watchPct)}",
        )
    }

    // P5: Catch-all. Pure k=v reason so the humaniser can parse it.
    return PriorityResult(
        priority = 5,
        label = "P5 Full backfill / dormant",
        reason = "watched=$watched, aired=$aired, missing=$missing, seasons=${snap.monitoredSeasons}, " +
            "last_watch=${daysSinceWatch ?: "never"}d",
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
