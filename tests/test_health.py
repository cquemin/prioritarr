from __future__ import annotations

import time
from datetime import datetime, timedelta, timezone

import pytest

from prioritarr.health import check_liveness, check_readiness


# ---------------------------------------------------------------------------
# TestLiveness
# ---------------------------------------------------------------------------


class TestLiveness:
    def test_liveness_healthy(self, db) -> None:
        """Fresh heartbeat → status ok."""
        db.update_heartbeat()
        result = check_liveness(db, max_heartbeat_age_seconds=300)
        assert result == {"status": "ok"}

    def test_liveness_stale_heartbeat(self, db) -> None:
        """Heartbeat older than threshold → unhealthy with stale reason."""
        # Manually insert a heartbeat that is 600 seconds old
        stale_ts = (datetime.now(timezone.utc) - timedelta(seconds=600)).isoformat()
        db.execute(
            "INSERT INTO heartbeat (id, ts) VALUES (1, ?) ON CONFLICT(id) DO UPDATE SET ts = excluded.ts",
            (stale_ts,),
        )
        result = check_liveness(db, max_heartbeat_age_seconds=300)
        assert result["status"] == "unhealthy"
        assert "heartbeat_stale" in result["reason"]

    def test_liveness_no_heartbeat(self, db) -> None:
        """No heartbeat row at all → unhealthy with no_heartbeat reason."""
        result = check_liveness(db, max_heartbeat_age_seconds=300)
        assert result["status"] == "unhealthy"
        assert result["reason"] == "no_heartbeat"


# ---------------------------------------------------------------------------
# TestReadiness
# ---------------------------------------------------------------------------


class TestReadiness:
    def test_readiness_all_ok(self, db) -> None:
        """All deps ok → status ok with last_heartbeat populated."""
        db.update_heartbeat()
        deps = {"sonarr": "ok", "qbit": "ok", "sabnzbd": "ok"}
        result = check_readiness(db, deps)

        assert result["status"] == "ok"
        assert result["dependencies"] == deps
        assert result["last_heartbeat"] is not None

    def test_readiness_with_failure(self, db) -> None:
        """Any dep not 'ok' → status degraded."""
        deps = {"sonarr": "ok", "qbit": "unreachable"}
        result = check_readiness(db, deps)

        assert result["status"] == "degraded"
        assert result["dependencies"]["qbit"] == "unreachable"
