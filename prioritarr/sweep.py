from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from typing import Any, Callable

from prioritarr.models import PriorityResult

logger = logging.getLogger(__name__)


@dataclass
class SweepContext:
    sonarr: Any
    priority_fn: Callable[[int], PriorityResult]
    max_searches: int
    delay_seconds: int
    dry_run: bool = False
    interrupted: bool = False


def build_sweep_order(missing_records: list[dict], priority_fn: Callable[[int], PriorityResult]) -> list[dict]:
    """Group by series, compute priority, sort by priority then oldest air date.

    Deduplicates records so each series appears at most once. The oldest
    airDateUtc across all records for a series is used for tie-breaking.
    """
    series_map: dict[int, dict] = {}
    for record in missing_records:
        sid = record["seriesId"]
        air_date = record.get("airDateUtc", "9999")
        if sid not in series_map:
            series_map[sid] = {"series_id": sid, "oldest_air_date": air_date}
        else:
            if air_date < series_map[sid]["oldest_air_date"]:
                series_map[sid]["oldest_air_date"] = air_date

    for sid, entry in series_map.items():
        result = priority_fn(sid)
        entry["priority"] = result.priority
        entry["label"] = result.label

    return sorted(series_map.values(), key=lambda e: (e["priority"], e["oldest_air_date"]))


def run_backfill_sweep(ctx: SweepContext) -> int:
    """Search for missing episodes in priority order.

    Returns the number of series searched (or iterated in dry_run mode).
    """
    missing = ctx.sonarr.get_wanted_missing()
    if not missing:
        return 0

    order = build_sweep_order(missing, ctx.priority_fn)
    searched = 0

    for entry in order:
        if searched >= ctx.max_searches or ctx.interrupted:
            break
        sid = entry["series_id"]
        if ctx.dry_run:
            logger.info("DRY RUN: would search series %d (%s)", sid, entry["label"])
        else:
            try:
                ctx.sonarr.trigger_series_search(sid)
            except Exception:
                logger.exception("Failed search for series %d", sid)
                break
            if ctx.delay_seconds > 0:
                time.sleep(ctx.delay_seconds)
        searched += 1

    return searched


def run_cutoff_sweep(ctx: SweepContext) -> int:
    """Search for cutoff-unmet episodes in priority order.

    Returns the number of series searched (or iterated in dry_run mode).
    """
    cutoff = ctx.sonarr.get_wanted_cutoff()
    if not cutoff:
        return 0

    order = build_sweep_order(cutoff, ctx.priority_fn)
    searched = 0

    for entry in order:
        if searched >= ctx.max_searches or ctx.interrupted:
            break
        sid = entry["series_id"]
        if ctx.dry_run:
            logger.info("DRY RUN: would cutoff-search series %d", sid)
        else:
            try:
                ctx.sonarr.trigger_cutoff_search(sid)
            except Exception:
                break
            if ctx.delay_seconds > 0:
                time.sleep(ctx.delay_seconds)
        searched += 1

    return searched
