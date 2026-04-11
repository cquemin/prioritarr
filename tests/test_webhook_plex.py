from __future__ import annotations

import json
from datetime import datetime, timezone

import pytest

from prioritarr.webhooks.plex_event import WatchedEvent, parse_tautulli_watched, handle_watched


# ---------------------------------------------------------------------------
# TestParseTautulliWatched
# ---------------------------------------------------------------------------


class TestParseTautulliWatched:
    def test_parse_tautulli_watched(self) -> None:
        data = {
            "grandparent_title": "Attack on Titan",
            "grandparent_rating_key": "42",
            "parent_media_index": "4",
            "media_index": "25",
            "rating_key": "9999",
        }
        event = parse_tautulli_watched(data)

        assert event.show_title == "Attack on Titan"
        assert event.plex_show_key == "42"
        assert event.season == 4
        assert event.episode == 25
        assert event.rating_key == "9999"

    def test_parse_tautulli_watched_missing_fields(self) -> None:
        """Missing fields should not raise; fall back to safe defaults."""
        event = parse_tautulli_watched({})

        assert event.show_title == ""
        assert event.plex_show_key == ""
        assert event.season == 0
        assert event.episode == 0
        assert event.rating_key == ""


# ---------------------------------------------------------------------------
# TestHandleWatched
# ---------------------------------------------------------------------------


class TestHandleWatched:
    def test_handle_watched_invalidates_cache(self, db) -> None:
        """handle_watched should remove the priority cache row for the series."""
        now = datetime.now(timezone.utc).isoformat()
        db.upsert_priority_cache(
            series_id=1,
            priority=2,
            watch_pct=0.9,
            days_since_watch=1,
            unwatched_pending=0,
            computed_at=now,
            expires_at=now,
            reason="test",
        )
        assert db.get_priority_cache(1) is not None

        handle_watched(1, db)

        assert db.get_priority_cache(1) is None

    def test_handle_watched_logs_audit(self, db) -> None:
        """handle_watched should append an audit entry with action='cache_invalidated'."""
        handle_watched(7, db)

        rows = db.execute("SELECT * FROM audit_log").fetchall()
        assert len(rows) == 1
        row = rows[0]
        assert row["action"] == "cache_invalidated"
        assert row["series_id"] == 7
        details = json.loads(row["details"])
        assert details["reason"] == "tautulli_watched"
