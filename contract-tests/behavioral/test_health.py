"""Behavioral contract tests for GET /health.

Preserves legacy quirks from the spec §4.1:
- 200 when heartbeat fresh, 503 when stale
- 503 body has 'reason' as free-form human-readable text
"""
from __future__ import annotations

import httpx


def test_health_returns_503_after_reset(client: httpx.Client) -> None:
    """After reset the heartbeat is cleared, so /health should be 503."""
    resp = client.get("/health")
    assert resp.status_code == 503, "No heartbeat after reset — expected 503"
    body = resp.json()
    assert body["status"] == "unhealthy"
    assert "reason" in body and isinstance(body["reason"], str)


def test_health_503_when_heartbeat_stale(client: httpx.Client) -> None:
    """Explicitly stale the heartbeat, confirm 503."""
    client.post("/api/v1/_testing/stale-heartbeat")
    resp = client.get("/health")
    assert resp.status_code == 503
    body = resp.json()
    assert body["status"] == "unhealthy"
    assert "heartbeat" in body["reason"].lower()


def test_health_503_body_shape(client: httpx.Client) -> None:
    """503 body has exactly two keys: status and reason."""
    client.post("/api/v1/_testing/stale-heartbeat")
    body = client.get("/health").json()
    assert set(body.keys()) == {"status", "reason"}


def test_health_content_type_is_json(client: httpx.Client) -> None:
    resp = client.get("/health")
    assert resp.headers["content-type"].startswith("application/json")
