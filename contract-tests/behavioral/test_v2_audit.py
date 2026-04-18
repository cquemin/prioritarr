"""GET /api/v2/audit with filters + pagination (Spec C §7.1)."""
from __future__ import annotations

import os

import httpx
import pytest


pytestmark = pytest.mark.skipif(
    os.environ.get("CONTRACT_TEST_API_KEY") is None,
    reason="CONTRACT_TEST_API_KEY not set",
)


def _seed_audit(auth_client: httpx.Client, fixtures) -> None:
    """Push two webhooks so the audit log has two distinct 'ongrab' rows."""
    for i in range(2):
        p = fixtures("sonarr_ongrab")
        p["downloadId"] = f"AUDIT-TEST-{i}"
        auth_client.post("/api/sonarr/on-grab", json=p)


def test_audit_returns_paginated_envelope(auth_client: httpx.Client, fixtures) -> None:
    _seed_audit(auth_client, fixtures)
    body = auth_client.get("/api/v2/audit").json()
    assert set(body.keys()) >= {"records", "totalRecords", "offset", "limit"}
    assert body["totalRecords"] >= 2


def test_filter_by_action(auth_client: httpx.Client, fixtures) -> None:
    _seed_audit(auth_client, fixtures)
    body = auth_client.get("/api/v2/audit?action=ongrab").json()
    assert body["totalRecords"] >= 2
    assert all(r["action"] == "ongrab" for r in body["records"])


def test_filter_by_unknown_action_is_empty(auth_client: httpx.Client, fixtures) -> None:
    _seed_audit(auth_client, fixtures)
    body = auth_client.get("/api/v2/audit?action=not_a_real_action").json()
    assert body["totalRecords"] == 0
    assert body["records"] == []
