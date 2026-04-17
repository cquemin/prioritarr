"""Behavioral contract tests for POST /api/plex-event."""
from __future__ import annotations

import httpx


def test_unmatched_plex_key_returns_unmatched_shape(
    client: httpx.Client, fixtures
) -> None:
    payload = fixtures("plex_watched")
    # After reset, the in-memory mapping is empty → unmatched.
    resp = client.post("/api/plex-event", json=payload)
    assert resp.status_code == 200
    body = resp.json()
    assert body == {
        "status": "unmatched",
        "plex_key": "CONTRACT_TEST_PLEX_KEY",
    }


def test_matched_plex_key_returns_ok_with_series_id(
    client: httpx.Client, fixtures
) -> None:
    """Inject a mapping, then confirm the event is matched."""
    client.post(
        "/api/v1/_testing/inject-series-mapping",
        json={"plex_key": "CONTRACT_TEST_PLEX_KEY", "series_id": 42},
    )
    payload = fixtures("plex_watched")
    resp = client.post("/api/plex-event", json=payload)
    assert resp.status_code == 200
    body = resp.json()
    assert body == {"status": "ok", "series_id": 42}
