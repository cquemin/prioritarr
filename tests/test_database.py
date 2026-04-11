from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

from prioritarr.database import Database


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------

def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _utc_offset(days: int = 0, hours: int = 0) -> str:
    return (datetime.now(timezone.utc) + timedelta(days=days, hours=hours)).isoformat()


# ---------------------------------------------------------------------------
# tests
# ---------------------------------------------------------------------------

class TestInitCreatesTables:
    def test_init_creates_tables(self, tmp_path: Path) -> None:
        """All 5 tables should exist after Database() construction."""
        db = Database(str(tmp_path / "test.db"))

        con = sqlite3.connect(str(tmp_path / "test.db"))
        rows = con.execute(
            "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"
        ).fetchall()
        con.close()

        table_names = {r[0] for r in rows}
        assert "series_priority_cache" in table_names
        assert "managed_downloads" in table_names
        assert "webhook_dedupe" in table_names
        assert "audit_log" in table_names
        assert "heartbeat" in table_names


class TestPriorityCache:
    def test_upsert_and_get_priority_cache(self, tmp_path: Path) -> None:
        """Insert a cache row and retrieve it; all fields should match."""
        db = Database(str(tmp_path / "test.db"))
        now = _utc_now()
        expires = _utc_offset(hours=1)

        db.upsert_priority_cache(
            series_id=42,
            priority=1,
            watch_pct=0.95,
            days_since_watch=3,
            unwatched_pending=2,
            computed_at=now,
            expires_at=expires,
            reason="recently watched",
        )

        row = db.get_priority_cache(42)
        assert row is not None
        assert row["series_id"] == 42
        assert row["priority"] == 1
        assert abs(row["watch_pct"] - 0.95) < 1e-9
        assert row["days_since_watch"] == 3
        assert row["unwatched_pending"] == 2
        assert row["computed_at"] == now
        assert row["expires_at"] == expires
        assert row["reason"] == "recently watched"

    def test_get_priority_cache_miss(self, tmp_path: Path) -> None:
        """get_priority_cache returns None for an unknown series_id."""
        db = Database(str(tmp_path / "test.db"))
        assert db.get_priority_cache(9999) is None

    def test_upsert_priority_cache_updates_existing(self, tmp_path: Path) -> None:
        """Second upsert with same series_id overwrites the first row."""
        db = Database(str(tmp_path / "test.db"))
        now = _utc_now()
        expires = _utc_offset(hours=1)

        db.upsert_priority_cache(
            series_id=1,
            priority=3,
            watch_pct=0.5,
            days_since_watch=30,
            unwatched_pending=5,
            computed_at=now,
            expires_at=expires,
            reason="old",
        )
        db.upsert_priority_cache(
            series_id=1,
            priority=1,
            watch_pct=0.99,
            days_since_watch=1,
            unwatched_pending=0,
            computed_at=now,
            expires_at=expires,
            reason="updated",
        )

        row = db.get_priority_cache(1)
        assert row is not None
        assert row["priority"] == 1
        assert row["reason"] == "updated"

    def test_invalidate_priority_cache(self, tmp_path: Path) -> None:
        """invalidate_priority_cache removes the row for that series."""
        db = Database(str(tmp_path / "test.db"))
        now = _utc_now()
        expires = _utc_offset(hours=1)

        db.upsert_priority_cache(
            series_id=7,
            priority=2,
            watch_pct=0.7,
            days_since_watch=10,
            unwatched_pending=1,
            computed_at=now,
            expires_at=expires,
            reason="test",
        )
        assert db.get_priority_cache(7) is not None

        db.invalidate_priority_cache(7)
        assert db.get_priority_cache(7) is None


class TestManagedDownloads:
    def test_upsert_managed_download(self, tmp_path: Path) -> None:
        """Insert a managed download and retrieve it; episode_ids stored as JSON."""
        db = Database(str(tmp_path / "test.db"))
        now = _utc_now()

        db.upsert_managed_download(
            client="qbittorrent",
            client_id="abc123",
            series_id=10,
            episode_ids=[1, 2, 3],
            initial_priority=2,
            current_priority=2,
            paused_by_us=False,
            first_seen_at=now,
            last_reconciled_at=now,
        )

        row = db.get_managed_download("qbittorrent", "abc123")
        assert row is not None
        assert row["client"] == "qbittorrent"
        assert row["client_id"] == "abc123"
        assert row["series_id"] == 10
        # episode_ids is stored as JSON string in the DB
        assert json.loads(row["episode_ids"]) == [1, 2, 3]
        assert row["initial_priority"] == 2
        assert row["current_priority"] == 2
        assert row["paused_by_us"] == 0  # SQLite stores bool as int
        assert row["first_seen_at"] == now
        assert row["last_reconciled_at"] == now

    def test_delete_managed_download(self, tmp_path: Path) -> None:
        """delete_managed_download removes the row; subsequent get returns None."""
        db = Database(str(tmp_path / "test.db"))
        now = _utc_now()

        db.upsert_managed_download(
            client="sabnzbd",
            client_id="nzb999",
            series_id=5,
            episode_ids=[],
            initial_priority=3,
            current_priority=3,
            paused_by_us=False,
            first_seen_at=now,
            last_reconciled_at=now,
        )
        assert db.get_managed_download("sabnzbd", "nzb999") is not None

        db.delete_managed_download("sabnzbd", "nzb999")
        assert db.get_managed_download("sabnzbd", "nzb999") is None

    def test_list_managed_downloads(self, tmp_path: Path) -> None:
        """Insert 2 downloads on different clients; list_managed_downloads returns both."""
        db = Database(str(tmp_path / "test.db"))
        now = _utc_now()

        db.upsert_managed_download(
            client="qbittorrent",
            client_id="t1",
            series_id=1,
            episode_ids=[1],
            initial_priority=1,
            current_priority=1,
            paused_by_us=False,
            first_seen_at=now,
            last_reconciled_at=now,
        )
        db.upsert_managed_download(
            client="sabnzbd",
            client_id="n1",
            series_id=2,
            episode_ids=[2],
            initial_priority=2,
            current_priority=2,
            paused_by_us=True,
            first_seen_at=now,
            last_reconciled_at=now,
        )

        all_rows = db.list_managed_downloads()
        assert len(all_rows) == 2

        qbit_rows = db.list_managed_downloads(client="qbittorrent")
        assert len(qbit_rows) == 1
        assert qbit_rows[0]["client"] == "qbittorrent"

        sab_rows = db.list_managed_downloads(client="sabnzbd")
        assert len(sab_rows) == 1
        assert sab_rows[0]["client"] == "sabnzbd"


class TestWebhookDedupe:
    def test_webhook_dedupe(self, tmp_path: Path) -> None:
        """First insert returns True; duplicate insert returns False."""
        db = Database(str(tmp_path / "test.db"))
        now = _utc_now()

        result1 = db.try_insert_dedupe("sonarr:grab:ep42", now)
        assert result1 is True

        result2 = db.try_insert_dedupe("sonarr:grab:ep42", now)
        assert result2 is False

    def test_webhook_dedupe_different_keys(self, tmp_path: Path) -> None:
        """Different event keys are independent."""
        db = Database(str(tmp_path / "test.db"))
        now = _utc_now()

        assert db.try_insert_dedupe("key:A", now) is True
        assert db.try_insert_dedupe("key:B", now) is True


class TestAuditLog:
    def test_append_audit_log(self, tmp_path: Path) -> None:
        """append_audit inserts a row; query verifies it exists."""
        db = Database(str(tmp_path / "test.db"))

        db.append_audit(
            action="PAUSE",
            series_id=10,
            client="qbittorrent",
            client_id="abc",
            details={"reason": "low priority"},
        )

        rows = db.execute("SELECT * FROM audit_log").fetchall()
        assert len(rows) == 1
        row = rows[0]
        assert row["action"] == "PAUSE"
        assert row["series_id"] == 10
        assert row["client"] == "qbittorrent"
        assert row["client_id"] == "abc"
        assert json.loads(row["details"]) == {"reason": "low priority"}
        assert row["ts"] is not None

    def test_append_audit_log_minimal(self, tmp_path: Path) -> None:
        """append_audit works with only the required action argument."""
        db = Database(str(tmp_path / "test.db"))

        db.append_audit(action="RECONCILE")

        rows = db.execute("SELECT * FROM audit_log").fetchall()
        assert len(rows) == 1
        assert rows[0]["action"] == "RECONCILE"
        assert rows[0]["series_id"] is None
        assert rows[0]["details"] is None


class TestHeartbeat:
    def test_update_heartbeat(self, tmp_path: Path) -> None:
        """update_heartbeat upserts row id=1; get_heartbeat returns an ISO timestamp."""
        db = Database(str(tmp_path / "test.db"))

        db.update_heartbeat()
        ts = db.get_heartbeat()

        assert ts is not None
        # Should parse as ISO datetime without error
        datetime.fromisoformat(ts)

    def test_get_heartbeat_before_update(self, tmp_path: Path) -> None:
        """get_heartbeat returns None when no heartbeat row exists."""
        db = Database(str(tmp_path / "test.db"))
        assert db.get_heartbeat() is None

    def test_update_heartbeat_overwrites(self, tmp_path: Path) -> None:
        """Calling update_heartbeat twice keeps exactly one row."""
        db = Database(str(tmp_path / "test.db"))

        db.update_heartbeat()
        db.update_heartbeat()

        rows = db.execute("SELECT * FROM heartbeat").fetchall()
        assert len(rows) == 1


class TestPrune:
    def test_prune_old_records(self, tmp_path: Path) -> None:
        """Rows older than retention windows should be deleted by prune()."""
        db = Database(str(tmp_path / "test.db"))

        # Insert a dedupe record that is 48 hours old (beyond default 24h window)
        old_ts = _utc_offset(hours=-48)
        db.try_insert_dedupe("old:event", old_ts)

        # Insert a recent dedupe record (should survive)
        new_ts = _utc_now()
        db.try_insert_dedupe("new:event", new_ts)

        # Insert an audit row that is 100 days old (beyond default 90-day window)
        db.execute(
            "INSERT INTO audit_log (ts, action) VALUES (?, ?)",
            (_utc_offset(days=-100), "OLD_ACTION"),
        )
        # Insert a recent audit row (should survive)
        db.append_audit(action="RECENT_ACTION")

        db.prune(dedupe_hours=24, retention_days=90)

        dedupe_rows = db.execute("SELECT * FROM webhook_dedupe").fetchall()
        dedupe_keys = {r["event_key"] for r in dedupe_rows}
        assert "old:event" not in dedupe_keys
        assert "new:event" in dedupe_keys

        audit_rows = db.execute("SELECT * FROM audit_log").fetchall()
        audit_actions = {r["action"] for r in audit_rows}
        assert "OLD_ACTION" not in audit_actions
        assert "RECENT_ACTION" in audit_actions
