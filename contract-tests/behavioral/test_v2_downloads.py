"""GET/POST/DELETE /api/v2/downloads (Spec C §7.1–§7.2)."""
from __future__ import annotations

import os

import httpx
import pytest


pytestmark = pytest.mark.skipif(
    os.environ.get("CONTRACT_TEST_API_KEY") is None,
    reason="CONTRACT_TEST_API_KEY not set",
)


def _seed_download(auth_client: httpx.Client, fixtures) -> None:
    """Push a Grab through the v1 webhook to create a managed_downloads row."""
    payload = fixtures("sonarr_ongrab")
    # Use a unique downloadId so reset_between_tests gives us a clean slate.
    payload["downloadId"] = "TEST_DOWNLOAD_V2"
    auth_client.post("/api/sonarr/on-grab", json=payload)


def test_list_returns_paginated_envelope(auth_client: httpx.Client, fixtures) -> None:
    _seed_download(auth_client, fixtures)
    body = auth_client.get("/api/v2/downloads").json()
    assert set(body.keys()) >= {"records", "totalRecords", "offset", "limit"}
    assert body["totalRecords"] >= 1


def test_detail_shape(auth_client: httpx.Client, fixtures) -> None:
    _seed_download(auth_client, fixtures)
    resp = auth_client.get("/api/v2/downloads/qbit/TEST_DOWNLOAD_V2")
    assert resp.status_code == 200
    body = resp.json()
    assert body["client"] == "qbit"
    assert body["clientId"] == "TEST_DOWNLOAD_V2"
    assert "episodeIds" in body


def test_delete_untracks(auth_client: httpx.Client, fixtures) -> None:
    _seed_download(auth_client, fixtures)
    delete_resp = auth_client.delete("/api/v2/downloads/qbit/TEST_DOWNLOAD_V2")
    assert delete_resp.status_code == 200
    assert delete_resp.json()["ok"] is True
    # Second delete → 404.
    second = auth_client.delete("/api/v2/downloads/qbit/TEST_DOWNLOAD_V2")
    assert second.status_code == 404


def test_delete_unknown_returns_404(auth_client: httpx.Client) -> None:
    resp = auth_client.delete("/api/v2/downloads/qbit/NO-SUCH-ID")
    assert resp.status_code == 404
