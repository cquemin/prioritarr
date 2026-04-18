"""Shared fixtures for the contract test suite.

Tests speak HTTP only — no Python imports from prioritarr. The suite must
work unchanged against Python today and Kotlin tomorrow.

Environment:
    CONTRACT_TEST_BASE_URL — backend under test (e.g. http://localhost:8000)
"""
from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any

import httpx
import pytest

FIXTURES = Path(__file__).parent / "fixtures"


@pytest.fixture(scope="session")
def base_url() -> str:
    url = os.environ.get("CONTRACT_TEST_BASE_URL")
    if not url:
        pytest.fail(
            "CONTRACT_TEST_BASE_URL env var is required — e.g. "
            "CONTRACT_TEST_BASE_URL=http://localhost:8000 pytest contract-tests/"
        )
    return url.rstrip("/")


@pytest.fixture(scope="session")
def client(base_url: str) -> httpx.Client:
    return httpx.Client(base_url=base_url, timeout=60.0)


@pytest.fixture(scope="session")
def api_key() -> str | None:
    """API key for /api/v2/* endpoints. If unset, v2 tests skip."""
    return os.environ.get("CONTRACT_TEST_API_KEY")


@pytest.fixture(scope="session")
def auth_client(base_url: str, api_key: str | None) -> httpx.Client:
    """Client with X-Api-Key pre-set. v2 tests use this exclusively."""
    if api_key is None:
        pytest.skip("CONTRACT_TEST_API_KEY not set — v2 tests skipped.")
    return httpx.Client(
        base_url=base_url,
        timeout=60.0,
        headers={"X-Api-Key": api_key},
    )


@pytest.fixture
def sse_events(auth_client: httpx.Client):
    """Generator that yields (id, event_type, data_dict) tuples from the SSE stream.

    Usage:
        with sse_events(last_event_id=None) as stream:
            for ev in stream:
                ...
    """
    import contextlib

    @contextlib.contextmanager
    def opener(last_event_id: int | None = None):
        headers = {}
        if last_event_id is not None:
            headers["Last-Event-ID"] = str(last_event_id)
        with auth_client.stream("GET", "/api/v2/events", headers=headers) as resp:
            def parse():
                event_type = None
                ev_id = None
                data = None
                for raw in resp.iter_lines():
                    line = raw.strip() if raw else ""
                    if not line:
                        if event_type and data is not None:
                            yield (ev_id, event_type, json.loads(data))
                        event_type = ev_id = data = None
                        continue
                    if line.startswith("event: "):
                        event_type = line[len("event: "):]
                    elif line.startswith("id: "):
                        try:
                            ev_id = int(line[len("id: "):])
                        except ValueError:
                            ev_id = None
                    elif line.startswith("data: "):
                        data = line[len("data: "):]
            yield parse()
    return opener


@pytest.fixture(autouse=True)
def reset_between_tests(client: httpx.Client) -> None:
    """Call /api/v1/_testing/reset before each test to isolate state."""
    resp = client.post("/api/v1/_testing/reset")
    if resp.status_code != 200:
        pytest.fail(
            f"Test-mode endpoints not available (status {resp.status_code}). "
            "The backend must be started with PRIORITARR_TEST_MODE=true."
        )


def load_fixture(name: str) -> dict[str, Any]:
    """Load a JSON fixture by filename (with or without .json suffix)."""
    path = FIXTURES / (name if name.endswith(".json") else f"{name}.json")
    return json.loads(path.read_text())


@pytest.fixture
def fixtures():
    """Access fixtures by name: fixtures('sonarr_ongrab') → dict."""
    return load_fixture
