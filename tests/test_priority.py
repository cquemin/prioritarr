from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from prioritarr.config import PriorityThresholds
from prioritarr.models import SeriesSnapshot
from prioritarr.priority import compute_priority


# ---------------------------------------------------------------------------
# Defaults for PriorityThresholds (from Task 2):
#   p1_watch_pct_min=0.90, p1_days_since_watch_max=14,
#   p1_days_since_release_max=7, p1_hiatus_gap_days=14,
#   p1_hiatus_release_window_days=28, p2_days_since_watch_max=60,
#   p3_unwatched_max=3, p3_days_since_watch_max=60, p4_min_watched=1
# ---------------------------------------------------------------------------

T = PriorityThresholds()


def _snap(
    aired=20,
    watched=20,
    last_watch_days_ago=2,
    release_days_ago=1,
    prev_release_days_ago=8,
    series_id=1,
    title="Test",
) -> SeriesSnapshot:
    now = datetime.now(timezone.utc)
    return SeriesSnapshot(
        series_id=series_id,
        title=title,
        tvdb_id=12345,
        monitored_episodes_aired=aired,
        monitored_episodes_watched=watched,
        last_watched_at=now - timedelta(days=last_watch_days_ago)
        if last_watch_days_ago is not None
        else None,
        episode_release_date=now - timedelta(days=release_days_ago)
        if release_days_ago is not None
        else None,
        previous_episode_release_date=now - timedelta(days=prev_release_days_ago)
        if prev_release_days_ago is not None
        else None,
    )


# ---------------------------------------------------------------------------
# P1: Live-following
# ---------------------------------------------------------------------------


class TestP1LiveFollowing:
    def test_caught_up_fresh_episode(self):
        """100% watched, watched 2 days ago, episode released 1 day ago → P1."""
        snap = _snap(aired=20, watched=20, last_watch_days_ago=2, release_days_ago=1)
        result = compute_priority(snap, T)
        assert result.priority == 1

    def test_90pct_watched_qualifies(self):
        """Exactly 90% watched qualifies for P1."""
        snap = _snap(aired=10, watched=9, last_watch_days_ago=2, release_days_ago=1)
        result = compute_priority(snap, T)
        assert result.priority == 1

    def test_89pct_does_not_qualify(self):
        """89% watched (8/9 of 9 aired) does not qualify for P1."""
        # 8/9 ≈ 88.9%, just below the 90% threshold
        snap = _snap(aired=9, watched=8, last_watch_days_ago=2, release_days_ago=1)
        result = compute_priority(snap, T)
        assert result.priority != 1

    def test_post_hiatus_within_28_days(self):
        """Post-hiatus episode released 20 days ago still qualifies as P1."""
        # hiatus: gap between episodes >= 14 days; use prev_release 30 days ago,
        # current release 20 days ago → gap = 10 days... need gap >= 14
        # prev=35 days ago, release=20 days ago → gap=15 days >= 14 → hiatus
        snap = _snap(
            aired=20,
            watched=20,
            last_watch_days_ago=2,
            release_days_ago=20,
            prev_release_days_ago=35,
        )
        result = compute_priority(snap, T)
        assert result.priority == 1

    def test_post_hiatus_beyond_28_days(self):
        """Post-hiatus episode released 29 days ago does NOT qualify as P1."""
        # gap=15 days (hiatus), but release_days_ago=29 > p1_hiatus_release_window_days=28
        snap = _snap(
            aired=20,
            watched=20,
            last_watch_days_ago=2,
            release_days_ago=29,
            prev_release_days_ago=44,
        )
        result = compute_priority(snap, T)
        assert result.priority != 1

    def test_last_watch_15_days_ago(self):
        """Watched 15 days ago exceeds p1_days_since_watch_max=14 → not P1."""
        snap = _snap(aired=20, watched=20, last_watch_days_ago=15, release_days_ago=1)
        result = compute_priority(snap, T)
        assert result.priority != 1


# ---------------------------------------------------------------------------
# P2: Caught-up but lapsed
# ---------------------------------------------------------------------------


class TestP2CaughtUpLapsed:
    def test_caught_up_lapsed_30_days(self):
        """100% watched, last watched 30 days ago → P2."""
        snap = _snap(aired=20, watched=20, last_watch_days_ago=30, release_days_ago=30)
        result = compute_priority(snap, T)
        assert result.priority == 2

    def test_exactly_60_days(self):
        """100% watched, last watched exactly 60 days ago → P2 (boundary)."""
        snap = _snap(aired=20, watched=20, last_watch_days_ago=60, release_days_ago=60)
        result = compute_priority(snap, T)
        assert result.priority == 2

    def test_61_days_falls_through(self):
        """100% watched but 61 days since last watch → falls through P2 to P3+."""
        snap = _snap(aired=20, watched=20, last_watch_days_ago=61, release_days_ago=61)
        result = compute_priority(snap, T)
        assert result.priority != 2


# ---------------------------------------------------------------------------
# P3: A few unwatched
# ---------------------------------------------------------------------------


class TestP3FewUnwatched:
    def test_3_unwatched_recent(self):
        """3 unwatched with watch_pct < 90% (to skip P1), watched recently → P3."""
        # aired=30, watched=27: pct = 90%, last_watch=2d, release=1d → would be P1
        # To get P3, need watch_pct < 90% OR last_watch > 14 days
        # Use aired=40, watched=37 → pct=92.5% > 90% AND release > 7 days
        # So P1 fails on release check; also not P2 (not 100%? wait, unwatched=3)
        # pct = 37/40 = 92.5%, unwatched=3, last_watch=5d, release=30d (>7, not hiatus)
        # P1: pct>=0.9 ✓, days_since_watch=5 <=14 ✓, release=30 >7 ✗, hiatus? gap=7 <14 → no
        # → P1 fails → P2: pct>=0.9 ✓, days_since_watch=5 not > 14 → P2 fails
        # → P3: unwatched=3 <=3 ✓, days_since_watch=5 <=60 ✓ → P3
        snap = _snap(
            aired=40,
            watched=37,
            last_watch_days_ago=5,
            release_days_ago=30,
            prev_release_days_ago=37,
        )
        result = compute_priority(snap, T)
        assert result.priority == 3

    def test_4_unwatched_falls_to_p4(self):
        """4 unwatched exceeds p3_unwatched_max=3 → falls to P4."""
        # aired=44, watched=40: pct=90.9%, unwatched=4
        # P1: pct>=0.9 ✓, last_watch=5d <=14 ✓, release=30 >7 ✗, no hiatus → fails
        # P2: pct>=0.9 ✓, last_watch=5 not > 14 → fails
        # P3: unwatched=4 > 3 → fails
        # P4: watched=40 >= 1 ✓, unwatched=4 > 3 ✓ → P4
        snap = _snap(
            aired=44,
            watched=40,
            last_watch_days_ago=5,
            release_days_ago=30,
            prev_release_days_ago=37,
        )
        result = compute_priority(snap, T)
        assert result.priority == 4


# ---------------------------------------------------------------------------
# P4: Partial backfill
# ---------------------------------------------------------------------------


class TestP4PartialBackfill:
    def test_many_unwatched_some_watched(self):
        """Many unwatched episodes with some watched → P4."""
        # aired=20, watched=10: pct=50%, unwatched=10
        # P1 fails (pct<0.9), P2 fails (pct<0.9), P3 fails (unwatched>3)
        # P4: watched=10 >=1, unwatched=10 >3 → P4
        snap = _snap(aired=20, watched=10, last_watch_days_ago=5, release_days_ago=5)
        result = compute_priority(snap, T)
        assert result.priority == 4

    def test_1_watched_rest_unwatched(self):
        """1 watched out of 20 aired → P4."""
        # pct=5%, unwatched=19
        # P1/P2 fail (pct<0.9), P3 fails (unwatched>3)
        # P4: watched=1 >=1 ✓, unwatched=19 >3 ✓ → P4
        snap = _snap(aired=20, watched=1, last_watch_days_ago=5, release_days_ago=5)
        result = compute_priority(snap, T)
        assert result.priority == 4


# ---------------------------------------------------------------------------
# P5: Catch-all
# ---------------------------------------------------------------------------


class TestP5Catchall:
    def test_zero_watched(self):
        """Nothing watched at all → P5."""
        # aired=20, watched=0: pct=0%, unwatched=20
        # P1/P2 fail, P3 fails (unwatched>3)
        # P4: watched=0 < p4_min_watched=1 → fails
        # P5: catch-all
        snap = _snap(
            aired=20,
            watched=0,
            last_watch_days_ago=None,
            release_days_ago=5,
        )
        result = compute_priority(snap, T)
        assert result.priority == 5

    def test_long_dormant_caught_up(self):
        """90% watched but 90 days dormant → P5."""
        # pct=18/20=90%, last_watch=90d
        # P1: pct>=0.9 ✓, days_since_watch=90 > 14 → fails
        # P2: pct>=0.9 ✓, days_since_watch=90 > 60 → fails
        # P3: unwatched=2 <=3 ✓, days_since_watch=90 > 60 → fails
        # P4: watched=18 >=1, unwatched=2; unwatched=2 NOT > p3_unwatched_max=3 → fails
        # P5: catch-all
        snap = _snap(
            aired=20,
            watched=18,
            last_watch_days_ago=90,
            release_days_ago=90,
        )
        result = compute_priority(snap, T)
        assert result.priority == 5

    def test_zero_aired(self):
        """Zero aired episodes → P5 (no aired means no watch_pct, watch is 0)."""
        snap = _snap(
            aired=0,
            watched=0,
            last_watch_days_ago=None,
            release_days_ago=None,
        )
        result = compute_priority(snap, T)
        assert result.priority == 5


# ---------------------------------------------------------------------------
# Edge cases
# ---------------------------------------------------------------------------


class TestEdgeCases:
    def test_no_release_date_not_p1(self):
        """No episode_release_date means days_since_release is None → P1 fails."""
        snap = _snap(
            aired=20,
            watched=20,
            last_watch_days_ago=2,
            release_days_ago=None,
        )
        result = compute_priority(snap, T)
        assert result.priority != 1

    def test_no_previous_release_hiatus_false(self):
        """No previous_episode_release_date → _is_hiatus returns False."""
        # Without a previous release date, hiatus=False; release_days_ago=20 > 7
        # and no hiatus → P1 release check fails
        snap = _snap(
            aired=20,
            watched=20,
            last_watch_days_ago=2,
            release_days_ago=20,
            prev_release_days_ago=None,
        )
        result = compute_priority(snap, T)
        assert result.priority != 1
