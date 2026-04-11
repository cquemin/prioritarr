from __future__ import annotations

import pytest

from prioritarr.database import Database
from prioritarr.models import PriorityResult
from prioritarr.reconcile import ReconcileContext, reconcile_client


# ---------------------------------------------------------------------------
# Fake helpers
# ---------------------------------------------------------------------------


class FakeQBitClient:
    def __init__(self, torrents):
        self._torrents = torrents
        self.paused = []
        self.resumed = []
        self.topped = []

    def get_torrents(self, category=None):
        return self._torrents

    def pause(self, hashes):
        self.paused.extend(hashes)

    def resume(self, hashes):
        self.resumed.extend(hashes)

    def top_priority(self, hashes):
        self.topped.extend(hashes)

    def bottom_priority(self, hashes):
        pass


class FakeSABClient:
    def __init__(self, queue_items):
        self._queue = queue_items
        self.priority_calls = []  # list of (nzo_id, priority)

    def get_queue(self):
        return self._queue

    def set_priority(self, nzo_id, priority):
        self.priority_calls.append((nzo_id, priority))


class FakePriorityFn:
    def __init__(self, mapping):
        self._mapping = mapping

    def __call__(self, series_id):
        p = self._mapping.get(series_id, 5)
        return PriorityResult(priority=p, label=f"P{p}", reason="test")


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


class TestReconcileOrphanAdoption:
    def test_reconcile_detects_orphan_and_assigns_priority(self, db: Database):
        """A torrent present in the client but absent from managed_downloads
        should be adopted when it appears in the Sonarr queue lookup."""
        torrents = [{"hash": "abc123", "state": "downloading"}]
        client = FakeQBitClient(torrents)
        priority_fn = FakePriorityFn({10: 2})

        ctx = ReconcileContext(
            client_name="qbit",
            client=client,
            db=db,
            sonarr_queue_lookup={"abc123": {"seriesId": 10, "episodeId": 99}},
            priority_fn=priority_fn,
            dry_run=True,
        )
        reconcile_client(ctx)

        row = db.get_managed_download("qbit", "abc123")
        assert row is not None
        assert row["series_id"] == 10
        assert row["current_priority"] == 2

        # audit entry should record the orphan adoption
        audit_rows = db.execute("SELECT * FROM audit_log").fetchall()
        assert any(r["action"] == "priority_set" for r in audit_rows)

    def test_reconcile_ignores_orphan_without_sonarr_info(self, db: Database):
        """A torrent not in the Sonarr queue lookup should be silently skipped."""
        torrents = [{"hash": "unknown_hash", "state": "downloading"}]
        client = FakeQBitClient(torrents)
        priority_fn = FakePriorityFn({})

        ctx = ReconcileContext(
            client_name="qbit",
            client=client,
            db=db,
            sonarr_queue_lookup={},
            priority_fn=priority_fn,
            dry_run=True,
        )
        reconcile_client(ctx)

        row = db.get_managed_download("qbit", "unknown_hash")
        assert row is None


class TestReconcilePriorityUpdate:
    def test_reconcile_updates_changed_priority(self, db: Database):
        """When a tracked download's computed priority differs from stored,
        the DB row should be updated and a reorder audit entry appended."""
        from datetime import datetime, timezone

        now = datetime.now(timezone.utc).isoformat()
        db.upsert_managed_download(
            client="qbit",
            client_id="hash1",
            series_id=5,
            episode_ids=[1],
            initial_priority=3,
            current_priority=3,
            paused_by_us=False,
            first_seen_at=now,
            last_reconciled_at=now,
        )

        torrents = [{"hash": "hash1", "state": "downloading"}]
        client = FakeQBitClient(torrents)
        # Priority changed to 1
        priority_fn = FakePriorityFn({5: 1})

        ctx = ReconcileContext(
            client_name="qbit",
            client=client,
            db=db,
            sonarr_queue_lookup={},
            priority_fn=priority_fn,
            dry_run=True,
        )
        reconcile_client(ctx)

        row = db.get_managed_download("qbit", "hash1")
        assert row is not None
        assert row["current_priority"] == 1

        audit_rows = db.execute("SELECT * FROM audit_log").fetchall()
        reorder = [r for r in audit_rows if r["action"] == "reorder"]
        assert len(reorder) == 1
        import json
        details = json.loads(reorder[0]["details"])
        assert details["old"] == 3
        assert details["new"] == 1

    def test_reconcile_no_audit_when_priority_unchanged(self, db: Database):
        """When priority is unchanged, no reorder audit entry is created."""
        from datetime import datetime, timezone

        now = datetime.now(timezone.utc).isoformat()
        db.upsert_managed_download(
            client="qbit",
            client_id="hash2",
            series_id=7,
            episode_ids=[],
            initial_priority=4,
            current_priority=4,
            paused_by_us=False,
            first_seen_at=now,
            last_reconciled_at=now,
        )

        torrents = [{"hash": "hash2", "state": "downloading"}]
        client = FakeQBitClient(torrents)
        priority_fn = FakePriorityFn({7: 4})  # same as current

        ctx = ReconcileContext(
            client_name="qbit",
            client=client,
            db=db,
            sonarr_queue_lookup={},
            priority_fn=priority_fn,
            dry_run=True,
        )
        reconcile_client(ctx)

        audit_rows = db.execute("SELECT * FROM audit_log").fetchall()
        reorder = [r for r in audit_rows if r["action"] == "reorder"]
        assert reorder == []


class TestReconcileCleanup:
    def test_reconcile_cleans_finished_downloads(self, db: Database):
        """Downloads no longer present in the client queue should be removed
        from managed_downloads."""
        from datetime import datetime, timezone

        now = datetime.now(timezone.utc).isoformat()
        # Insert a tracked download that is NOT in the current queue
        db.upsert_managed_download(
            client="qbit",
            client_id="gone_hash",
            series_id=3,
            episode_ids=[],
            initial_priority=2,
            current_priority=2,
            paused_by_us=False,
            first_seen_at=now,
            last_reconciled_at=now,
        )

        # Queue is empty — the download finished
        client = FakeQBitClient([])
        priority_fn = FakePriorityFn({})

        ctx = ReconcileContext(
            client_name="qbit",
            client=client,
            db=db,
            sonarr_queue_lookup={},
            priority_fn=priority_fn,
            dry_run=True,
        )
        reconcile_client(ctx)

        row = db.get_managed_download("qbit", "gone_hash")
        assert row is None

    def test_reconcile_only_cleans_own_client(self, db: Database):
        """Cleanup should only remove rows for the client being reconciled,
        not rows belonging to other clients."""
        from datetime import datetime, timezone

        now = datetime.now(timezone.utc).isoformat()
        db.upsert_managed_download(
            client="sab",
            client_id="nzb1",
            series_id=9,
            episode_ids=[],
            initial_priority=3,
            current_priority=3,
            paused_by_us=False,
            first_seen_at=now,
            last_reconciled_at=now,
        )

        # Reconcile qbit with empty queue
        client = FakeQBitClient([])
        priority_fn = FakePriorityFn({})

        ctx = ReconcileContext(
            client_name="qbit",
            client=client,
            db=db,
            sonarr_queue_lookup={},
            priority_fn=priority_fn,
            dry_run=True,
        )
        reconcile_client(ctx)

        # The SAB row should still be there
        row = db.get_managed_download("sab", "nzb1")
        assert row is not None


class TestReconcileEnforcement:
    def test_reconcile_qbit_enforcement_not_called_in_dry_run(self, db: Database):
        """In dry_run mode, no pause/resume/top_priority calls should be made."""
        torrents = [{"hash": "h1", "state": "downloading"}]
        client = FakeQBitClient(torrents)
        # Orphan with P1 — would normally trigger top_priority
        priority_fn = FakePriorityFn({10: 1})

        ctx = ReconcileContext(
            client_name="qbit",
            client=client,
            db=db,
            sonarr_queue_lookup={"h1": {"seriesId": 10, "episodeId": 1}},
            priority_fn=priority_fn,
            dry_run=True,
        )
        reconcile_client(ctx)

        assert client.paused == []
        assert client.resumed == []
        assert client.topped == []

    def test_reconcile_sab_sets_priority_for_tracked(self, db: Database):
        """For SAB client (not dry_run), set_priority should be called for
        each tracked download using the mapped SAB priority value."""
        from datetime import datetime, timezone
        from prioritarr.enforcement import compute_sab_priority

        now = datetime.now(timezone.utc).isoformat()
        db.upsert_managed_download(
            client="sab",
            client_id="nzb42",
            series_id=20,
            episode_ids=[],
            initial_priority=2,
            current_priority=2,
            paused_by_us=False,
            first_seen_at=now,
            last_reconciled_at=now,
        )

        queue = [{"nzo_id": "nzb42"}]
        client = FakeSABClient(queue)
        priority_fn = FakePriorityFn({20: 2})

        ctx = ReconcileContext(
            client_name="sab",
            client=client,
            db=db,
            sonarr_queue_lookup={},
            priority_fn=priority_fn,
            dry_run=False,
        )
        reconcile_client(ctx)

        expected_sab_priority = compute_sab_priority(2)
        assert ("nzb42", expected_sab_priority) in client.priority_calls
