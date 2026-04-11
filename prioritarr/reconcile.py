from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Callable

from prioritarr.database import Database
from prioritarr.models import PriorityResult
from prioritarr.enforcement import compute_qbit_pause_actions, compute_sab_priority

logger = logging.getLogger(__name__)


@dataclass
class ReconcileContext:
    client_name: str  # 'qbit' | 'sab'
    client: Any  # QBitClient | SABClient (duck-typed for testing)
    db: Database
    sonarr_queue_lookup: dict[str, dict]  # download_id -> {seriesId, episodeId}
    priority_fn: Callable[[int], PriorityResult]
    dry_run: bool = False


def reconcile_client(ctx: ReconcileContext) -> None:
    """Reconcile a single download client's queue against managed_downloads.

    1. Adopt orphans (downloads in client queue but not in managed_downloads)
    2. Recompute priorities for tracked downloads
    3. Apply enforcement actions (qBit pause/resume, SAB priority)
    4. Clean up managed_downloads for items no longer in client queue
    """
    now = datetime.now(timezone.utc).isoformat()

    # Get current queue from client
    if ctx.client_name == "qbit":
        queue_items = ctx.client.get_torrents()
        id_field = "hash"
    else:
        queue_items = ctx.client.get_queue()
        id_field = "nzo_id"

    current_ids: set[str] = set()

    for item in queue_items:
        client_id = item[id_field]
        current_ids.add(client_id)
        row = ctx.db.get_managed_download(ctx.client_name, client_id)

        if row is None:
            # Orphan — try to identify via Sonarr queue
            sonarr_info = ctx.sonarr_queue_lookup.get(client_id)
            if sonarr_info is None:
                continue
            series_id = sonarr_info["seriesId"]
            episode_id = sonarr_info.get("episodeId", 0)
            result = ctx.priority_fn(series_id)
            ctx.db.upsert_managed_download(
                client=ctx.client_name,
                client_id=client_id,
                series_id=series_id,
                episode_ids=[episode_id],
                initial_priority=result.priority,
                current_priority=result.priority,
                paused_by_us=False,
                first_seen_at=now,
                last_reconciled_at=now,
            )
            ctx.db.append_audit(
                action="priority_set",
                series_id=series_id,
                client=ctx.client_name,
                client_id=client_id,
                details={"priority": result.priority, "source": "orphan_adopt"},
            )
        else:
            # Already tracked — recompute priority
            series_id = row["series_id"]
            result = ctx.priority_fn(series_id)
            old_priority = row["current_priority"]
            if result.priority != old_priority:
                ctx.db.upsert_managed_download(
                    client=ctx.client_name,
                    client_id=client_id,
                    series_id=series_id,
                    episode_ids=[],
                    initial_priority=row["initial_priority"],
                    current_priority=result.priority,
                    paused_by_us=bool(row["paused_by_us"]),
                    first_seen_at=row["first_seen_at"],
                    last_reconciled_at=now,
                )
                ctx.db.append_audit(
                    action="reorder",
                    series_id=series_id,
                    client=ctx.client_name,
                    client_id=client_id,
                    details={"old": old_priority, "new": result.priority},
                )

    # Apply enforcement (not in dry-run)
    if ctx.client_name == "qbit" and not ctx.dry_run:
        _apply_qbit_enforcement(ctx, queue_items)
    elif ctx.client_name == "sab" and not ctx.dry_run:
        _apply_sab_enforcement(ctx)

    # Clean up finished downloads
    managed = ctx.db.list_managed_downloads(client=ctx.client_name)
    for row in managed:
        if row["client_id"] not in current_ids:
            ctx.db.delete_managed_download(ctx.client_name, row["client_id"])


def _apply_qbit_enforcement(ctx: ReconcileContext, queue_items: list) -> None:
    managed = ctx.db.list_managed_downloads(client="qbit")
    downloads: dict[str, dict] = {}
    for row in managed:
        state = "downloading"
        for qi in queue_items:
            if qi["hash"] == row["client_id"]:
                state = qi.get("state", "downloading")
                break
        downloads[row["client_id"]] = {
            "hash": row["client_id"],
            "priority": row["current_priority"],
            "state": state,
            "paused_by_us": bool(row["paused_by_us"]),
        }
    actions = compute_qbit_pause_actions(downloads)
    for action in actions:
        if action.action == "pause":
            ctx.client.pause([action.hash])
            ctx.db.execute(
                "UPDATE managed_downloads SET paused_by_us = 1 WHERE client = 'qbit' AND client_id = ?",
                (action.hash,),
            )
        elif action.action == "resume":
            ctx.client.resume([action.hash])
            ctx.db.execute(
                "UPDATE managed_downloads SET paused_by_us = 0 WHERE client = 'qbit' AND client_id = ?",
                (action.hash,),
            )
        elif action.action == "top_priority":
            ctx.client.top_priority([action.hash])
        ctx.db.append_audit(
            action=action.action,
            client="qbit",
            client_id=action.hash,
            details={"source": "reconcile"},
        )


def _apply_sab_enforcement(ctx: ReconcileContext) -> None:
    managed = ctx.db.list_managed_downloads(client="sab")
    for row in managed:
        sab_priority = compute_sab_priority(row["current_priority"])
        ctx.client.set_priority(row["client_id"], sab_priority)
        ctx.db.append_audit(
            action="priority_set",
            client="sab",
            client_id=row["client_id"],
            series_id=row["series_id"],
            details={"sab_priority": sab_priority, "source": "reconcile"},
        )
