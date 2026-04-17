"""Behavioral contract tests for GET /ready."""
from __future__ import annotations

import re

import httpx


ISO_WITH_OFFSET = re.compile(
    r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d+\+\d{2}:\d{2}$"
)


def test_ready_body_always_has_the_four_dependency_keys(client: httpx.Client) -> None:
    body = client.get("/ready").json()
    assert set(body["dependencies"].keys()) == {"sonarr", "tautulli", "qbit", "sab"}


def test_ready_dependency_values_are_ok_or_unreachable(client: httpx.Client) -> None:
    body = client.get("/ready").json()
    for name, value in body["dependencies"].items():
        assert value in ("ok", "unreachable"), f"{name} has invalid status {value!r}"


def test_ready_status_matches_rollup(client: httpx.Client) -> None:
    body = client.get("/ready").json()
    any_bad = any(v == "unreachable" for v in body["dependencies"].values())
    expected_status = "degraded" if any_bad else "ok"
    assert body["status"] == expected_status


def test_ready_last_heartbeat_is_iso_offset_or_null(client: httpx.Client) -> None:
    """Legacy quirk #4: ISO 8601 with '+00:00', NOT 'Z'."""
    body = client.get("/ready").json()
    hb = body["last_heartbeat"]
    assert hb is None or ISO_WITH_OFFSET.match(hb), (
        f"last_heartbeat must be null or ISO with +00:00 offset; got {hb!r}"
    )


def test_ready_status_code_reflects_rollup(client: httpx.Client) -> None:
    resp = client.get("/ready")
    body = resp.json()
    if body["status"] == "ok":
        assert resp.status_code == 200
    else:
        assert resp.status_code == 503
