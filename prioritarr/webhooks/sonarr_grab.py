from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from datetime import datetime, timezone

from prioritarr.database import Database

CLIENT_MAP: dict[str, str] = {
    "qbittorrent": "qbit",
    "sabnzbd": "sab",
    "nzbget": "sab",
}


@dataclass
class OnGrabEvent:
    series_id: int
    series_title: str
    tvdb_id: int
    episode_ids: list[int]
    download_client: str  # normalized via CLIENT_MAP
    download_id: str
    air_date: str | None


def parse_ongrab_payload(data: dict) -> OnGrabEvent:
    """Extract fields from a Sonarr OnGrab webhook payload.

    Normalises the download client name via CLIENT_MAP (falls back to
    the raw value lowercased if unknown).
    """
    series = data.get("series", {})
    episodes = data.get("episodes", [])

    raw_client = data.get("downloadClient", "")
    normalized_client = CLIENT_MAP.get(raw_client.lower(), raw_client.lower())

    episode_ids = [ep["id"] for ep in episodes if "id" in ep]

    # Use the airDateUtc of the first episode (if present)
    air_date: str | None = None
    if episodes:
        air_date = episodes[0].get("airDateUtc")

    return OnGrabEvent(
        series_id=series.get("id", 0),
        series_title=series.get("title", ""),
        tvdb_id=series.get("tvdbId", 0),
        episode_ids=episode_ids,
        download_client=normalized_client,
        download_id=data.get("downloadId", ""),
        air_date=air_date,
    )


def _event_key(event: OnGrabEvent) -> str:
    """Compute a SHA-1 deduplication key from the event's identifying fields."""
    raw = f"Grab:{event.series_id}:{sorted(event.episode_ids)}:{event.download_id}"
    return hashlib.sha1(raw.encode()).hexdigest()


def handle_ongrab(
    event: OnGrabEvent,
    db: Database,
    priority: int,
    dry_run: bool = False,
) -> bool:
    """Process an OnGrab event.

    Deduplicates via db.try_insert_dedupe, inserts a managed_download row,
    and appends an audit entry.

    Returns True if the event was processed, False if it was a duplicate.
    """
    now = datetime.now(timezone.utc).isoformat()
    key = _event_key(event)

    if not db.try_insert_dedupe(key, now):
        return False

    if not dry_run:
        db.upsert_managed_download(
            client=event.download_client,
            client_id=event.download_id,
            series_id=event.series_id,
            episode_ids=event.episode_ids,
            initial_priority=priority,
            current_priority=priority,
            paused_by_us=False,
            first_seen_at=now,
            last_reconciled_at=now,
        )

    db.append_audit(
        action="ongrab",
        series_id=event.series_id,
        client=event.download_client,
        client_id=event.download_id,
        details={
            "series_title": event.series_title,
            "episode_ids": event.episode_ids,
            "priority": priority,
            "dry_run": dry_run,
        },
    )

    return True
