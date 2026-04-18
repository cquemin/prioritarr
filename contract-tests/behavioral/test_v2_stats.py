"""GET /api/v2/stats — aggregated counters for dashboard widgets."""
from __future__ import annotations

import os

import httpx
import pytest


pytestmark = pytest.mark.skipif(
    os.environ.get("CONTRACT_TEST_API_KEY") is None,
    reason="CONTRACT_TEST_API_KEY not set",
)

REQUIRED_FIELDS = {
    "liveFollowing", "caughtUpLapsed", "fewUnwatched", "partialBackfill",
    "dormant", "totalCached", "managedDownloads", "pausedByUs",
    "unmatchedShows", "mappedShows", "lastMappingRefreshAt",
    "tautulliAvailable", "dryRun", "lastHeartbeat",
}


def test_stats_shape(auth_client: httpx.Client) -> None:
    resp = auth_client.get("/api/v2/stats")
    assert resp.status_code == 200
    body = resp.json()
    missing = REQUIRED_FIELDS - body.keys()
    assert not missing, f"missing fields: {missing}"
    # Every scalar count is a non-negative int.
    for k in (
        "liveFollowing", "caughtUpLapsed", "fewUnwatched", "partialBackfill",
        "dormant", "totalCached", "managedDownloads", "pausedByUs",
        "unmatchedShows", "mappedShows",
    ):
        assert isinstance(body[k], int) and body[k] >= 0, f"{k} must be a non-negative int, got {body[k]!r}"


def test_stats_requires_auth(client: httpx.Client) -> None:
    resp = client.get("/api/v2/stats")
    assert resp.status_code == 401


def test_stats_counts_managed_downloads(auth_client: httpx.Client, fixtures) -> None:
    # Seed one download via the v1 webhook; stats should reflect it.
    p = fixtures("sonarr_ongrab")
    p["downloadId"] = "STATS-TEST-1"
    auth_client.post("/api/sonarr/on-grab", json=p)
    body = auth_client.get("/api/v2/stats").json()
    assert body["managedDownloads"] >= 1
