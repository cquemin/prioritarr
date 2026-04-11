from __future__ import annotations

from datetime import datetime, timezone

from prioritarr.config import PriorityThresholds
from prioritarr.models import SeriesSnapshot, PriorityResult


def compute_priority(snap: SeriesSnapshot, t: PriorityThresholds) -> PriorityResult:
    """Compute P1-P5 priority for a series. First match wins."""
    aired = snap.monitored_episodes_aired
    watched = snap.monitored_episodes_watched
    now = datetime.now(timezone.utc)

    # Derived values
    watch_pct = watched / aired if aired > 0 else 0.0
    unwatched = aired - watched
    days_since_watch = (now - snap.last_watched_at).days if snap.last_watched_at else None
    days_since_release = (now - snap.episode_release_date).days if snap.episode_release_date else None
    is_post_hiatus = _is_hiatus(snap, t.p1_hiatus_gap_days)

    # P1: Live-following
    if (watch_pct >= t.p1_watch_pct_min
        and days_since_watch is not None and days_since_watch <= t.p1_days_since_watch_max
        and days_since_release is not None
        and (days_since_release <= t.p1_days_since_release_max
             or (is_post_hiatus and days_since_release <= t.p1_hiatus_release_window_days))):
        return PriorityResult(priority=1, label="P1 Live-following",
            reason=f"watch={watch_pct:.0%}, last_watch={days_since_watch}d, release={days_since_release}d, hiatus={is_post_hiatus}")

    # P2: Caught-up but lapsed
    if (watch_pct >= t.p1_watch_pct_min
        and days_since_watch is not None
        and t.p1_days_since_watch_max < days_since_watch <= t.p2_days_since_watch_max):
        return PriorityResult(priority=2, label="P2 Caught-up but lapsed",
            reason=f"watch={watch_pct:.0%}, last_watch={days_since_watch}d")

    # P3: A few unwatched
    if (1 <= unwatched <= t.p3_unwatched_max
        and days_since_watch is not None
        and days_since_watch <= t.p3_days_since_watch_max):
        return PriorityResult(priority=3, label="P3 A few unwatched",
            reason=f"unwatched={unwatched}, last_watch={days_since_watch}d")

    # P4: Partial backfill
    if watched >= t.p4_min_watched and unwatched > t.p3_unwatched_max:
        return PriorityResult(priority=4, label="P4 Partial backfill",
            reason=f"watched={watched}, unwatched={unwatched}")

    # P5: Catch-all default
    return PriorityResult(priority=5, label="P5 Full backfill / dormant",
        reason=f"watched={watched}, aired={aired}, last_watch={days_since_watch}")


def _is_hiatus(snap: SeriesSnapshot, gap_days: int) -> bool:
    if snap.episode_release_date is None or snap.previous_episode_release_date is None:
        return False
    gap = (snap.episode_release_date - snap.previous_episode_release_date).days
    return gap >= gap_days
