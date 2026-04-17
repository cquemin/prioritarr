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
