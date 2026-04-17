from __future__ import annotations

import json
import os
from pathlib import Path
from unittest.mock import patch

import httpx
import pytest
import respx
from fastapi.testclient import TestClient

FIXTURES = Path(__file__).parent / "fixtures"


def _load_fixture(name: str) -> object:
    return json.loads((FIXTURES / name).read_text())


# ---------------------------------------------------------------------------
# Environment + DB setup
# ---------------------------------------------------------------------------


@pytest.fixture(autouse=True)
def env_vars(tmp_path, monkeypatch):
    """Set required env vars so prioritarr can start up."""
    monkeypatch.setenv("PRIORITARR_SONARR_URL", "http://sonarr:8989")
    monkeypatch.setenv("PRIORITARR_SONARR_API_KEY", "testkey")
    monkeypatch.setenv("PRIORITARR_TAUTULLI_URL", "http://tautulli:8181")
    monkeypatch.setenv("PRIORITARR_TAUTULLI_API_KEY", "testkey")
    monkeypatch.setenv("PRIORITARR_QBIT_URL", "http://vpn:8080")
    monkeypatch.setenv("PRIORITARR_SAB_URL", "http://sabnzbd:8080")
    monkeypatch.setenv("PRIORITARR_SAB_API_KEY", "testkey")
    monkeypatch.setenv("PRIORITARR_DRY_RUN", "true")
    # Point at a non-existent path so load_yaml_config returns {}
    monkeypatch.setenv("PRIORITARR_CONFIG_PATH", str(tmp_path / "nonexistent.yaml"))


def _mock_startup_routes() -> None:
    """Register respx mocks for the HTTP calls made during lifespan startup.

    The lifespan calls _refresh_mappings(), which calls:
      1. sonarr.get_all_series()         -> GET /api/v3/series
      2. tautulli.get_show_libraries()   -> GET /api/v2 (returns empty list)
    """
    respx.get("http://sonarr:8989/api/v3/series").mock(
        return_value=httpx.Response(
            200, json=_load_fixture("sonarr_series.json")
        )
    )
    # Return an empty library list so no follow-up get_library_media_info calls
    # are made — keeps the mock setup minimal.
    respx.get("http://tautulli:8181/api/v2").mock(
        return_value=httpx.Response(
            200,
            json={"response": {"result": "success", "data": []}},
        )
    )


# ---------------------------------------------------------------------------
# Client fixture
# ---------------------------------------------------------------------------


@pytest.fixture
def client(tmp_path):
    """Create a TestClient with all external HTTP calls mocked.

    - respx intercepts every outgoing httpx request.
    - The Database constructor is patched so the app uses a temp SQLite file
      instead of the hardcoded /config/state.db path.
    - The heartbeat is pre-populated so /health returns 200.
    """
    from prioritarr.database import Database
    from prioritarr.main import app

    real_db = Database(str(tmp_path / "test.db"))

    with respx.mock:
        _mock_startup_routes()

        with patch("prioritarr.main.Database", return_value=real_db):
            # Pre-populate the heartbeat so liveness check succeeds immediately.
            real_db.update_heartbeat()

            with TestClient(app) as tc:
                yield tc, real_db


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


class TestHealthEndpoint:
    """GET /health integration tests."""

    def test_health_returns_200_when_heartbeat_fresh(self, client):
        """A fresh heartbeat should produce status=ok and HTTP 200."""
        tc, _ = client
        resp = tc.get("/health")

        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "ok"

    def test_health_returns_503_when_no_heartbeat(self, tmp_path, env_vars):
        """Without any heartbeat row the liveness check should return 503."""
        from prioritarr.database import Database
        from prioritarr.main import app

        # Fresh DB — no heartbeat written.
        fresh_db = Database(str(tmp_path / "no_hb.db"))

        with respx.mock:
            _mock_startup_routes()

            with patch("prioritarr.main.Database", return_value=fresh_db):
                with TestClient(app) as tc:
                    resp = tc.get("/health")

        assert resp.status_code == 503
        assert resp.json()["status"] == "unhealthy"


class TestSonarrOnGrabEndpoint:
    """POST /api/sonarr/on-grab integration tests."""

    def _mock_priority_routes(self):
        """Mock the Sonarr API calls that happen during priority computation."""
        respx.get("http://sonarr:8989/api/v3/series/1").mock(
            return_value=httpx.Response(
                200,
                json={"id": 1, "title": "Attack on Titan", "tvdbId": 267440},
            )
        )
        respx.get("http://sonarr:8989/api/v3/episode").mock(
            return_value=httpx.Response(
                200, json=_load_fixture("sonarr_episodes.json")
            )
        )

    def test_grab_event_returns_200_with_priority(self, client):
        """A valid Grab payload should return 200, status=processed, and a priority.

        Even in dry_run mode the audit log entry is written.  We check:
          - HTTP 200
          - response body has status=processed
          - response body contains a numeric priority field
          - an audit entry was appended to the DB
        """
        tc, real_db = client
        payload = _load_fixture("sonarr_ongrab.json")

        with respx.mock(assert_all_called=False):
            _mock_startup_routes()
            self._mock_priority_routes()

            resp = tc.post("/api/sonarr/on-grab", json=payload)

        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "processed"
        assert isinstance(body["priority"], int)
        assert 1 <= body["priority"] <= 5

        # Audit log should have one 'ongrab' entry.
        rows = real_db._con.execute(
            "SELECT * FROM audit_log WHERE action = 'ongrab'"
        ).fetchall()
        assert len(rows) == 1
        details = json.loads(rows[0]["details"])
        assert details["series_title"] == "Attack on Titan"
        assert details["priority"] == body["priority"]

    def test_grab_event_duplicate_is_deduplicated(self, client):
        """Sending the same Grab payload twice should deduplicate the second call."""
        tc, real_db = client
        payload = _load_fixture("sonarr_ongrab.json")

        with respx.mock(assert_all_called=False):
            _mock_startup_routes()
            self._mock_priority_routes()

            resp1 = tc.post("/api/sonarr/on-grab", json=payload)
            resp2 = tc.post("/api/sonarr/on-grab", json=payload)

        assert resp1.status_code == 200
        assert resp1.json()["status"] == "processed"

        assert resp2.status_code == 200
        assert resp2.json()["status"] == "duplicate"

        # Only one audit entry despite two requests.
        rows = real_db._con.execute(
            "SELECT * FROM audit_log WHERE action = 'ongrab'"
        ).fetchall()
        assert len(rows) == 1

    def test_non_grab_event_is_ignored(self, client):
        """A payload with eventType=Test should return status=ignored without
        triggering any priority computation (no Sonarr series/episode calls)."""
        tc, _ = client

        resp = tc.post(
            "/api/sonarr/on-grab",
            json={"eventType": "Test", "series": {}, "episodes": []},
        )

        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "ignored"
        assert body["eventType"] == "Test"


def test_sonarr_on_grab_openapi_declares_union_response(client):
    tc, _ = client
    spec = tc.get("/openapi.json").json()
    post_grab = spec["paths"]["/api/sonarr/on-grab"]["post"]
    ok_schema = post_grab["responses"]["200"]["content"]["application/json"]["schema"]
    as_str = str(ok_schema)
    assert any(
        name in as_str for name in ("OnGrabIgnored", "OnGrabProcessed", "OnGrabDuplicate")
    )


def test_plex_event_openapi_declares_union_response(client):
    tc, _ = client
    spec = tc.get("/openapi.json").json()
    post_plex = spec["paths"]["/api/plex-event"]["post"]
    ok_schema = post_plex["responses"]["200"]["content"]["application/json"]["schema"]
    as_str = str(ok_schema)
    assert "PlexEventUnmatched" in as_str or "PlexEventOk" in as_str
