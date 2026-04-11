from __future__ import annotations

import json

import pytest

from prioritarr.webhooks.sonarr_grab import (
    OnGrabEvent,
    parse_ongrab_payload,
    handle_ongrab,
    _event_key,
    CLIENT_MAP,
)
from tests.conftest import FIXTURES


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _load_fixture() -> dict:
    return json.loads((FIXTURES / "sonarr_ongrab.json").read_text())


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


class TestParseOnGrabPayload:
    def test_parse_ongrab_payload(self) -> None:
        data = _load_fixture()
        event = parse_ongrab_payload(data)

        assert event.series_id == 1
        assert event.series_title == "Attack on Titan"
        assert event.tvdb_id == 267440
        assert event.episode_ids == [101]
        assert event.download_client == "qbit"  # normalized from "qBittorrent"
        assert event.download_id == "ABCDEF123456"
        assert event.air_date == "2026-04-01T00:00:00Z"

    def test_parse_ongrab_handles_missing_fields(self) -> None:
        """Minimal payload with missing optional fields should not raise."""
        data = {"eventType": "Grab"}
        event = parse_ongrab_payload(data)

        assert event.series_id == 0
        assert event.series_title == ""
        assert event.tvdb_id == 0
        assert event.episode_ids == []
        assert event.download_client == ""
        assert event.download_id == ""
        assert event.air_date is None

    def test_client_map_normalization(self) -> None:
        """sabnzbd and nzbget should both map to 'sab'."""
        for raw, expected in [("SABnzbd", "sab"), ("nzbget", "sab"), ("qBittorrent", "qbit")]:
            data = {"downloadClient": raw}
            event = parse_ongrab_payload(data)
            assert event.download_client == expected, f"Expected {expected!r} for {raw!r}"


class TestHandleOnGrab:
    def test_handle_ongrab_inserts_managed_download(self, db) -> None:
        data = _load_fixture()
        event = parse_ongrab_payload(data)

        result = handle_ongrab(event, db, priority=2, dry_run=False)

        assert result is True

        row = db.get_managed_download("qbit", "ABCDEF123456")
        assert row is not None
        assert row["series_id"] == 1
        assert row["initial_priority"] == 2
        assert row["current_priority"] == 2

    def test_handle_ongrab_dedupes(self, db) -> None:
        """Second call with identical event should return False (deduplicated)."""
        data = _load_fixture()
        event = parse_ongrab_payload(data)

        first = handle_ongrab(event, db, priority=2, dry_run=False)
        second = handle_ongrab(event, db, priority=2, dry_run=False)

        assert first is True
        assert second is False

    def test_handle_ongrab_dry_run_skips_managed_download(self, db) -> None:
        """In dry_run mode, no managed_download row should be inserted."""
        data = _load_fixture()
        event = parse_ongrab_payload(data)

        result = handle_ongrab(event, db, priority=2, dry_run=True)

        assert result is True
        row = db.get_managed_download("qbit", "ABCDEF123456")
        assert row is None

    def test_handle_ongrab_appends_audit(self, db) -> None:
        data = _load_fixture()
        event = parse_ongrab_payload(data)

        handle_ongrab(event, db, priority=3, dry_run=False)

        rows = db.execute("SELECT * FROM audit_log").fetchall()
        assert len(rows) == 1
        assert rows[0]["action"] == "ongrab"
        assert rows[0]["series_id"] == 1
