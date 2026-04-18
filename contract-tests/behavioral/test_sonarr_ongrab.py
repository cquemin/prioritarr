"""Behavioral contract tests for POST /api/sonarr/on-grab.

Locks the three response shapes (ignored, processed, duplicate) and the
always-200 contract.
"""
from __future__ import annotations

import httpx
import pytest


def test_non_grab_event_returns_ignored(client: httpx.Client, fixtures) -> None:
    payload = fixtures("sonarr_ongrab_non_grab")
    resp = client.post("/api/sonarr/on-grab", json=payload)
    assert resp.status_code == 200
    body = resp.json()
    assert body == {"status": "ignored", "eventType": "Test"}


def test_grab_event_returns_processed_with_priority_and_label(
    client: httpx.Client, fixtures
) -> None:
    payload = fixtures("sonarr_ongrab")
    resp = client.post("/api/sonarr/on-grab", json=payload)
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "processed"
    assert 1 <= body["priority"] <= 5
    assert body["label"].startswith(f"P{body['priority']}")
    assert set(body.keys()) == {"status", "priority", "label"}


def test_duplicate_grab_returns_duplicate_with_priority_and_label(
    client: httpx.Client, fixtures
) -> None:
    """Legacy quirk: duplicate still carries priority + label.

    The *label suffix* may differ between first and second call — on the
    second call priority is served from the cache and the handler
    annotates the label with ' (cached)'. Priority itself is stable.
    """
    payload = fixtures("sonarr_ongrab")
    first = client.post("/api/sonarr/on-grab", json=payload).json()
    assert first["status"] == "processed"
    second = client.post("/api/sonarr/on-grab", json=payload).json()
    assert second["status"] == "duplicate"
    assert second["priority"] == first["priority"]
    assert second["label"].startswith(f"P{second['priority']}")
    assert set(second.keys()) == {"status", "priority", "label"}


def test_grab_with_missing_series_still_returns_200(
    client: httpx.Client, fixtures
) -> None:
    """Legacy quirk #1: webhooks are always-200, even on malformed payloads."""
    payload = fixtures("sonarr_ongrab_missing_series")
    resp = client.post("/api/sonarr/on-grab", json=payload)
    assert resp.status_code == 200


@pytest.mark.parametrize("event_type", ["Test", "Download", "Health", "Rename"])
def test_any_non_grab_eventtype_is_ignored(
    client: httpx.Client, event_type: str
) -> None:
    resp = client.post("/api/sonarr/on-grab", json={"eventType": event_type})
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ignored"
    assert body["eventType"] == event_type
