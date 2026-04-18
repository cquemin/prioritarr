"""GET /api/v2/mappings (Spec C §7.1)."""
from __future__ import annotations

import os

import httpx
import pytest


pytestmark = pytest.mark.skipif(
    os.environ.get("CONTRACT_TEST_API_KEY") is None,
    reason="CONTRACT_TEST_API_KEY not set",
)


def test_mappings_snapshot_shape(auth_client: httpx.Client) -> None:
    body = auth_client.get("/api/v2/mappings").json()
    assert set(body.keys()) >= {"plexKeyToSeriesId", "tautulliAvailable"}
    assert isinstance(body["plexKeyToSeriesId"], dict)


def test_injected_mapping_appears(auth_client: httpx.Client) -> None:
    """Use the test-mode injector then read via v2."""
    auth_client.post(
        "/api/v1/_testing/inject-series-mapping",
        json={"plex_key": "MAPPINGS_V2_KEY", "series_id": 777},
    )
    body = auth_client.get("/api/v2/mappings").json()
    assert body["plexKeyToSeriesId"].get("MAPPINGS_V2_KEY") == 777
