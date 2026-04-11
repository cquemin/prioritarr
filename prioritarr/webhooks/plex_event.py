from __future__ import annotations

from dataclasses import dataclass

from prioritarr.database import Database


@dataclass
class WatchedEvent:
    show_title: str
    plex_show_key: str
    season: int
    episode: int
    rating_key: str


def parse_tautulli_watched(data: dict) -> WatchedEvent:
    """Extract fields from a Tautulli webhook payload for a watched episode."""
    return WatchedEvent(
        show_title=data.get("grandparent_title", ""),
        plex_show_key=data.get("grandparent_rating_key", ""),
        season=int(data.get("parent_media_index", 0) or 0),
        episode=int(data.get("media_index", 0) or 0),
        rating_key=data.get("rating_key", ""),
    )


def handle_watched(sonarr_series_id: int, db: Database) -> None:
    """Invalidate the priority cache for the given series and log the action."""
    db.invalidate_priority_cache(sonarr_series_id)
    db.append_audit(
        action="cache_invalidated",
        series_id=sonarr_series_id,
        details={"reason": "tautulli_watched"},
    )
