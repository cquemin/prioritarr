from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timedelta, timezone
from typing import Any


_SCHEMA = """
CREATE TABLE IF NOT EXISTS series_priority_cache (
    series_id INTEGER PRIMARY KEY,
    priority INTEGER NOT NULL,
    watch_pct REAL,
    days_since_watch INTEGER,
    unwatched_pending INTEGER,
    computed_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    reason TEXT
);

CREATE TABLE IF NOT EXISTS managed_downloads (
    client TEXT NOT NULL,
    client_id TEXT NOT NULL,
    series_id INTEGER NOT NULL,
    episode_ids TEXT,
    initial_priority INTEGER NOT NULL,
    current_priority INTEGER NOT NULL,
    paused_by_us INTEGER NOT NULL DEFAULT 0,
    first_seen_at TEXT NOT NULL,
    last_reconciled_at TEXT NOT NULL,
    PRIMARY KEY (client, client_id)
);

CREATE TABLE IF NOT EXISTS webhook_dedupe (
    event_key TEXT PRIMARY KEY,
    received_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ts TEXT NOT NULL,
    action TEXT NOT NULL,
    series_id INTEGER,
    client TEXT,
    client_id TEXT,
    details TEXT
);

CREATE TABLE IF NOT EXISTS heartbeat (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    ts TEXT NOT NULL
);
"""


class Database:
    """SQLite persistence layer for prioritarr."""

    def __init__(self, db_path: str) -> None:
        self._con = sqlite3.connect(db_path, check_same_thread=False)
        self._con.row_factory = sqlite3.Row
        self._con.executescript(_SCHEMA)
        self._con.commit()

    # ------------------------------------------------------------------
    # generic pass-through
    # ------------------------------------------------------------------

    def execute(self, sql: str, params: Any = ()) -> sqlite3.Cursor:
        """Execute *sql* with optional *params* and return the cursor."""
        cur = self._con.execute(sql, params)
        self._con.commit()
        return cur

    # ------------------------------------------------------------------
    # series_priority_cache
    # ------------------------------------------------------------------

    def upsert_priority_cache(
        self,
        *,
        series_id: int,
        priority: int,
        watch_pct: float | None,
        days_since_watch: int | None,
        unwatched_pending: int | None,
        computed_at: str,
        expires_at: str,
        reason: str | None,
    ) -> None:
        """Insert or replace a priority cache row."""
        self.execute(
            """
            INSERT INTO series_priority_cache
                (series_id, priority, watch_pct, days_since_watch,
                 unwatched_pending, computed_at, expires_at, reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(series_id) DO UPDATE SET
                priority          = excluded.priority,
                watch_pct         = excluded.watch_pct,
                days_since_watch  = excluded.days_since_watch,
                unwatched_pending = excluded.unwatched_pending,
                computed_at       = excluded.computed_at,
                expires_at        = excluded.expires_at,
                reason            = excluded.reason
            """,
            (
                series_id,
                priority,
                watch_pct,
                days_since_watch,
                unwatched_pending,
                computed_at,
                expires_at,
                reason,
            ),
        )

    def get_priority_cache(self, series_id: int) -> sqlite3.Row | None:
        """Return the cache row for *series_id*, or None if absent."""
        return self._con.execute(
            "SELECT * FROM series_priority_cache WHERE series_id = ?",
            (series_id,),
        ).fetchone()

    def invalidate_priority_cache(self, series_id: int) -> None:
        """Delete the cache row for *series_id*."""
        self.execute(
            "DELETE FROM series_priority_cache WHERE series_id = ?",
            (series_id,),
        )

    # ------------------------------------------------------------------
    # managed_downloads
    # ------------------------------------------------------------------

    def upsert_managed_download(
        self,
        *,
        client: str,
        client_id: str,
        series_id: int,
        episode_ids: list[int],
        initial_priority: int,
        current_priority: int,
        paused_by_us: bool,
        first_seen_at: str,
        last_reconciled_at: str,
    ) -> None:
        """Insert or replace a managed download row.

        *episode_ids* is serialised as a JSON string.
        """
        self.execute(
            """
            INSERT INTO managed_downloads
                (client, client_id, series_id, episode_ids,
                 initial_priority, current_priority, paused_by_us,
                 first_seen_at, last_reconciled_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(client, client_id) DO UPDATE SET
                series_id           = excluded.series_id,
                episode_ids         = excluded.episode_ids,
                initial_priority    = excluded.initial_priority,
                current_priority    = excluded.current_priority,
                paused_by_us        = excluded.paused_by_us,
                first_seen_at       = excluded.first_seen_at,
                last_reconciled_at  = excluded.last_reconciled_at
            """,
            (
                client,
                client_id,
                series_id,
                json.dumps(episode_ids),
                initial_priority,
                current_priority,
                int(paused_by_us),
                first_seen_at,
                last_reconciled_at,
            ),
        )

    def get_managed_download(
        self, client: str, client_id: str
    ) -> sqlite3.Row | None:
        """Return the managed download row, or None if absent."""
        return self._con.execute(
            "SELECT * FROM managed_downloads WHERE client = ? AND client_id = ?",
            (client, client_id),
        ).fetchone()

    def list_managed_downloads(
        self, client: str | None = None
    ) -> list[sqlite3.Row]:
        """Return all managed download rows, optionally filtered by *client*."""
        if client is None:
            return self._con.execute(
                "SELECT * FROM managed_downloads"
            ).fetchall()
        return self._con.execute(
            "SELECT * FROM managed_downloads WHERE client = ?",
            (client,),
        ).fetchall()

    def delete_managed_download(self, client: str, client_id: str) -> None:
        """Delete the managed download row identified by (*client*, *client_id*)."""
        self.execute(
            "DELETE FROM managed_downloads WHERE client = ? AND client_id = ?",
            (client, client_id),
        )

    # ------------------------------------------------------------------
    # webhook_dedupe
    # ------------------------------------------------------------------

    def try_insert_dedupe(self, event_key: str, received_at: str) -> bool:
        """Attempt to insert *event_key*.

        Returns True if inserted (new event), False if the key already exists
        (duplicate).
        """
        try:
            self._con.execute(
                "INSERT INTO webhook_dedupe (event_key, received_at) VALUES (?, ?)",
                (event_key, received_at),
            )
            self._con.commit()
            return True
        except sqlite3.IntegrityError:
            return False

    # ------------------------------------------------------------------
    # audit_log
    # ------------------------------------------------------------------

    def append_audit(
        self,
        *,
        action: str,
        series_id: int | None = None,
        client: str | None = None,
        client_id: str | None = None,
        details: Any = None,
    ) -> None:
        """Append an audit log entry with the current UTC timestamp."""
        ts = datetime.now(timezone.utc).isoformat()
        details_json = json.dumps(details) if details is not None else None
        self.execute(
            """
            INSERT INTO audit_log (ts, action, series_id, client, client_id, details)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            (ts, action, series_id, client, client_id, details_json),
        )

    # ------------------------------------------------------------------
    # heartbeat
    # ------------------------------------------------------------------

    def update_heartbeat(self) -> None:
        """Upsert the single heartbeat row (id=1) with the current UTC time."""
        ts = datetime.now(timezone.utc).isoformat()
        self.execute(
            """
            INSERT INTO heartbeat (id, ts) VALUES (1, ?)
            ON CONFLICT(id) DO UPDATE SET ts = excluded.ts
            """,
            (ts,),
        )

    def get_heartbeat(self) -> str | None:
        """Return the last heartbeat timestamp, or None if not yet set."""
        row = self._con.execute(
            "SELECT ts FROM heartbeat WHERE id = 1"
        ).fetchone()
        return row["ts"] if row else None

    # ------------------------------------------------------------------
    # prune
    # ------------------------------------------------------------------

    def prune(
        self,
        dedupe_hours: int = 24,
        retention_days: int = 90,
    ) -> None:
        """Delete old dedupe and audit rows beyond their retention windows."""
        now = datetime.now(timezone.utc)

        dedupe_cutoff = (now - timedelta(hours=dedupe_hours)).isoformat()
        self.execute(
            "DELETE FROM webhook_dedupe WHERE received_at < ?",
            (dedupe_cutoff,),
        )

        audit_cutoff = (now - timedelta(days=retention_days)).isoformat()
        self.execute(
            "DELETE FROM audit_log WHERE ts < ?",
            (audit_cutoff,),
        )
