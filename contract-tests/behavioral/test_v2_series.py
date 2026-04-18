"""GET /api/v2/series + recompute (Spec C §7.1, §7.2)."""
from __future__ import annotations

import os

import httpx
import pytest


pytestmark = pytest.mark.skipif(
    os.environ.get("CONTRACT_TEST_API_KEY") is None,
    reason="CONTRACT_TEST_API_KEY not set",
)


def test_list_returns_paginated_envelope(auth_client: httpx.Client) -> None:
    resp = auth_client.get("/api/v2/series")
    assert resp.status_code == 200
    body = resp.json()
    assert set(body.keys()) >= {"records", "totalRecords", "offset", "limit"}
    assert isinstance(body["records"], list)
    assert body["limit"] == 50
    assert body["offset"] == 0


def test_pagination_respects_limit(auth_client: httpx.Client) -> None:
    full = auth_client.get("/api/v2/series").json()
    if full["totalRecords"] < 2:
        pytest.skip("need ≥2 series to test pagination; Sonarr mock only has 1")
    page = auth_client.get("/api/v2/series?limit=1&offset=0").json()
    assert len(page["records"]) == 1
    assert page["totalRecords"] == full["totalRecords"]


def test_detail_404_for_unknown_id(auth_client: httpx.Client) -> None:
    resp = auth_client.get("/api/v2/series/999999999")
    assert resp.status_code == 404


def test_recompute_returns_action_result(auth_client: httpx.Client) -> None:
    # Seed: Sonarr mock returns id=1 for everything; use that.
    resp = auth_client.post("/api/v2/series/1/recompute")
    assert resp.status_code == 200
    body = resp.json()
    assert body["ok"] is True
    # priority field present, either from cache or computed.
    assert "priority" in body
