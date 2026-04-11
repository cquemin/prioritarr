from __future__ import annotations

import pytest

from prioritarr.models import PriorityResult
from prioritarr.sweep import (
    SweepContext,
    build_sweep_order,
    run_backfill_sweep,
    run_cutoff_sweep,
)


# ---------------------------------------------------------------------------
# Fake helpers
# ---------------------------------------------------------------------------


class FakeSonarr:
    def __init__(self, missing_records=None, cutoff_records=None):
        self._missing = missing_records or []
        self._cutoff = cutoff_records or []
        self.searched_series: list[int] = []
        self.cutoff_searched_series: list[int] = []

    def get_wanted_missing(self, page_size: int = 1000):
        return self._missing

    def get_wanted_cutoff(self, page_size: int = 1000):
        return self._cutoff

    def trigger_series_search(self, series_id: int):
        self.searched_series.append(series_id)
        return {"id": 1}

    def trigger_cutoff_search(self, series_id: int):
        self.cutoff_searched_series.append(series_id)
        return {"id": 1}


class FakePriorityFn:
    def __init__(self, mapping):
        self._mapping = mapping

    def __call__(self, series_id):
        p = self._mapping.get(series_id, 5)
        return PriorityResult(priority=p, label=f"P{p}", reason="test")


# ---------------------------------------------------------------------------
# build_sweep_order
# ---------------------------------------------------------------------------


class TestBuildSweepOrder:
    def test_build_sweep_order_sorts_by_priority(self):
        """Lower priority number (higher importance) should come first."""
        records = [
            {"seriesId": 10, "airDateUtc": "2024-01-05"},
            {"seriesId": 20, "airDateUtc": "2024-01-01"},
            {"seriesId": 30, "airDateUtc": "2024-01-03"},
        ]
        priority_fn = FakePriorityFn({10: 3, 20: 1, 30: 2})

        order = build_sweep_order(records, priority_fn)

        series_order = [e["series_id"] for e in order]
        assert series_order == [20, 30, 10]

    def test_build_sweep_order_deduplicates_series(self):
        """Multiple records for the same series should collapse to one entry."""
        records = [
            {"seriesId": 5, "airDateUtc": "2024-03-10"},
            {"seriesId": 5, "airDateUtc": "2024-01-01"},
            {"seriesId": 5, "airDateUtc": "2024-06-15"},
        ]
        priority_fn = FakePriorityFn({5: 2})

        order = build_sweep_order(records, priority_fn)

        assert len(order) == 1
        assert order[0]["series_id"] == 5

    def test_build_sweep_order_picks_oldest_air_date(self):
        """When a series has multiple records, oldest airDateUtc should be used."""
        records = [
            {"seriesId": 7, "airDateUtc": "2024-06-01"},
            {"seriesId": 7, "airDateUtc": "2023-01-15"},
            {"seriesId": 7, "airDateUtc": "2024-03-20"},
        ]
        priority_fn = FakePriorityFn({7: 3})

        order = build_sweep_order(records, priority_fn)

        assert order[0]["oldest_air_date"] == "2023-01-15"

    def test_build_sweep_order_same_priority_sorts_by_air_date(self):
        """For equal priorities, entries with older air dates should come first."""
        records = [
            {"seriesId": 1, "airDateUtc": "2024-06-01"},
            {"seriesId": 2, "airDateUtc": "2023-01-01"},
        ]
        priority_fn = FakePriorityFn({1: 2, 2: 2})

        order = build_sweep_order(records, priority_fn)

        assert order[0]["series_id"] == 2  # older air date wins

    def test_build_sweep_order_empty_returns_empty(self):
        """Empty input should return an empty list."""
        order = build_sweep_order([], FakePriorityFn({}))
        assert order == []


# ---------------------------------------------------------------------------
# run_backfill_sweep
# ---------------------------------------------------------------------------


class TestRunBackfillSweep:
    def test_run_backfill_sweep_respects_max_searches(self):
        """Should stop after max_searches even if more series are pending."""
        records = [
            {"seriesId": 1, "airDateUtc": "2023-01-01"},
            {"seriesId": 2, "airDateUtc": "2023-01-02"},
            {"seriesId": 3, "airDateUtc": "2023-01-03"},
        ]
        sonarr = FakeSonarr(missing_records=records)
        priority_fn = FakePriorityFn({1: 1, 2: 2, 3: 3})

        ctx = SweepContext(
            sonarr=sonarr,
            priority_fn=priority_fn,
            max_searches=2,
            delay_seconds=0,
            dry_run=False,
        )
        count = run_backfill_sweep(ctx)

        assert count == 2
        assert len(sonarr.searched_series) == 2

    def test_run_backfill_sweep_dry_run_no_searches(self):
        """In dry_run mode, trigger_series_search should never be called."""
        records = [
            {"seriesId": 10, "airDateUtc": "2024-01-01"},
            {"seriesId": 20, "airDateUtc": "2024-01-02"},
        ]
        sonarr = FakeSonarr(missing_records=records)
        priority_fn = FakePriorityFn({10: 1, 20: 2})

        ctx = SweepContext(
            sonarr=sonarr,
            priority_fn=priority_fn,
            max_searches=10,
            delay_seconds=0,
            dry_run=True,
        )
        count = run_backfill_sweep(ctx)

        assert count == 2  # dry_run still counts iterations
        assert sonarr.searched_series == []

    def test_run_backfill_sweep_empty_missing_returns_zero(self):
        """When there are no missing episodes, return 0."""
        sonarr = FakeSonarr(missing_records=[])
        ctx = SweepContext(
            sonarr=sonarr,
            priority_fn=FakePriorityFn({}),
            max_searches=10,
            delay_seconds=0,
        )
        count = run_backfill_sweep(ctx)
        assert count == 0

    def test_run_backfill_sweep_searches_in_priority_order(self):
        """Searches should occur in priority order (P1 first)."""
        records = [
            {"seriesId": 100, "airDateUtc": "2023-05-01"},
            {"seriesId": 200, "airDateUtc": "2023-05-02"},
            {"seriesId": 300, "airDateUtc": "2023-05-03"},
        ]
        sonarr = FakeSonarr(missing_records=records)
        priority_fn = FakePriorityFn({100: 3, 200: 1, 300: 2})

        ctx = SweepContext(
            sonarr=sonarr,
            priority_fn=priority_fn,
            max_searches=3,
            delay_seconds=0,
            dry_run=False,
        )
        run_backfill_sweep(ctx)

        assert sonarr.searched_series == [200, 300, 100]

    def test_run_backfill_sweep_interrupted_stops_early(self):
        """When interrupted=True, the sweep should stop immediately."""
        records = [
            {"seriesId": 1, "airDateUtc": "2023-01-01"},
            {"seriesId": 2, "airDateUtc": "2023-01-02"},
        ]
        sonarr = FakeSonarr(missing_records=records)
        priority_fn = FakePriorityFn({1: 1, 2: 2})

        ctx = SweepContext(
            sonarr=sonarr,
            priority_fn=priority_fn,
            max_searches=10,
            delay_seconds=0,
            dry_run=False,
            interrupted=True,
        )
        count = run_backfill_sweep(ctx)

        assert count == 0
        assert sonarr.searched_series == []


# ---------------------------------------------------------------------------
# run_cutoff_sweep
# ---------------------------------------------------------------------------


class TestRunCutoffSweep:
    def test_run_cutoff_sweep_calls_cutoff_search(self):
        """Should call trigger_cutoff_search (not trigger_series_search)."""
        cutoff_records = [
            {"seriesId": 55, "airDateUtc": "2024-02-01"},
        ]
        sonarr = FakeSonarr(cutoff_records=cutoff_records)
        priority_fn = FakePriorityFn({55: 2})

        ctx = SweepContext(
            sonarr=sonarr,
            priority_fn=priority_fn,
            max_searches=5,
            delay_seconds=0,
            dry_run=False,
        )
        count = run_cutoff_sweep(ctx)

        assert count == 1
        assert sonarr.cutoff_searched_series == [55]
        assert sonarr.searched_series == []

    def test_run_cutoff_sweep_empty_cutoff_returns_zero(self):
        """When there are no cutoff-unmet episodes, return 0."""
        sonarr = FakeSonarr(cutoff_records=[])
        ctx = SweepContext(
            sonarr=sonarr,
            priority_fn=FakePriorityFn({}),
            max_searches=10,
            delay_seconds=0,
        )
        count = run_cutoff_sweep(ctx)
        assert count == 0

    def test_run_cutoff_sweep_respects_max_searches(self):
        """Cutoff sweep should also honour max_searches."""
        cutoff_records = [
            {"seriesId": i, "airDateUtc": f"2024-0{i}-01"} for i in range(1, 6)
        ]
        sonarr = FakeSonarr(cutoff_records=cutoff_records)
        priority_fn = FakePriorityFn({i: i for i in range(1, 6)})

        ctx = SweepContext(
            sonarr=sonarr,
            priority_fn=priority_fn,
            max_searches=3,
            delay_seconds=0,
            dry_run=False,
        )
        count = run_cutoff_sweep(ctx)

        assert count == 3
        assert len(sonarr.cutoff_searched_series) == 3
