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
        logger.info("[backfill] no missing episodes found")
        return 0

    order = build_sweep_order(missing, ctx.priority_fn)
    logger.info("[backfill] %d missing episodes across %d series (max %d searches this sweep)", len(missing), len(order), ctx.max_searches)

    # Log the priority breakdown
    by_priority: dict[int, int] = {}
    for entry in order:
        by_priority[entry["priority"]] = by_priority.get(entry["priority"], 0) + 1
    logger.info("[backfill] priority breakdown: %s", ", ".join(f"P{p}={c}" for p, c in sorted(by_priority.items())))

    searched = 0
    for entry in order:
        if searched >= ctx.max_searches:
            logger.info("[backfill] hit max searches (%d), remaining deferred to next sweep", ctx.max_searches)
            break
        if ctx.interrupted:
            logger.info("[backfill] interrupted by webhook event, pausing sweep")
            break
        sid = entry["series_id"]
        if ctx.dry_run:
            logger.info("[backfill] DRY RUN: would search series %d (%s)", sid, entry["label"])
        else:
            try:
                ctx.sonarr.trigger_series_search(sid)
                logger.info("[backfill] triggered search: series %d (%s)", sid, entry["label"])
            except Exception:
                logger.exception("[backfill] search failed for series %d, backing off", sid)
                break
            if ctx.delay_seconds > 0:
                time.sleep(ctx.delay_seconds)
        searched += 1

    logger.info("[backfill] sweep complete: %d/%d series searched", searched, len(order))
    return searched


def run_cutoff_sweep(ctx: SweepContext) -> int:
    """Search for cutoff-unmet episodes in priority order.

    Returns the number of series searched (or iterated in dry_run mode).
    """
    cutoff = ctx.sonarr.get_wanted_cutoff()
    if not cutoff:
        logger.info("[cutoff] no cutoff-unmet episodes found")
        return 0

    order = build_sweep_order(cutoff, ctx.priority_fn)
    logger.info("[cutoff] %d cutoff-unmet episodes across %d series (max %d)", len(cutoff), len(order), ctx.max_searches)
    searched = 0

    for entry in order:
        if searched >= ctx.max_searches or ctx.interrupted:
            break
        sid = entry["series_id"]
        if ctx.dry_run:
            logger.info("[cutoff] DRY RUN: would cutoff-search series %d (%s)", sid, entry["label"])
        else:
            try:
                ctx.sonarr.trigger_cutoff_search(sid)
                logger.info("[cutoff] triggered search: series %d (%s)", sid, entry["label"])
            except Exception:
                logger.exception("[cutoff] search failed for series %d, backing off", sid)
                break
            if ctx.delay_seconds > 0:
                time.sleep(ctx.delay_seconds)
        searched += 1

    logger.info("[cutoff] sweep complete: %d/%d series searched", searched, len(order))
    return searched
