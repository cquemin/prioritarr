"""Auth contract tests for /api/v2/* (Spec C §5)."""
from __future__ import annotations

import os

import httpx
import pytest


pytestmark = pytest.mark.skipif(
    os.environ.get("CONTRACT_TEST_API_KEY") is None,
    reason="CONTRACT_TEST_API_KEY not set",
)


def test_v2_requires_api_key(client: httpx.Client) -> None:
    """Bare request (no auth) → 401 RFC 7807 body."""
    resp = client.get("/api/v2/settings")
    assert resp.status_code == 401
    assert resp.headers["content-type"].startswith("application/problem+json")
    body = resp.json()
    assert body["type"] == "/errors/unauthorized"
    assert body["status"] == 401
    assert "detail" in body


def test_v2_wrong_key_is_401(client: httpx.Client) -> None:
    resp = client.get("/api/v2/settings", headers={"X-Api-Key": "definitely-wrong"})
    assert resp.status_code == 401
    assert resp.json()["type"] == "/errors/unauthorized"


def test_v2_accepts_x_api_key(auth_client: httpx.Client) -> None:
    resp = auth_client.get("/api/v2/settings")
    assert resp.status_code == 200


def test_v2_accepts_bearer(client: httpx.Client, api_key: str) -> None:
    resp = client.get("/api/v2/settings", headers={"Authorization": f"Bearer {api_key}"})
    assert resp.status_code == 200


def test_v1_surface_does_not_require_auth(client: httpx.Client) -> None:
    """/health and the webhook paths stay unauthenticated."""
    assert client.get("/health").status_code in (200, 503)
    assert client.post(
        "/api/sonarr/on-grab", json={"eventType": "Test"}
    ).status_code == 200
