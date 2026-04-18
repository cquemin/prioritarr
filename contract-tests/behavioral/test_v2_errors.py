"""RFC 7807 problem-details contract tests (Spec C §6)."""
from __future__ import annotations

import os

import httpx
import pytest


pytestmark = pytest.mark.skipif(
    os.environ.get("CONTRACT_TEST_API_KEY") is None,
    reason="CONTRACT_TEST_API_KEY not set",
)


def test_404_renders_as_problem(auth_client: httpx.Client) -> None:
    resp = auth_client.get("/api/v2/series/999999999")
    assert resp.status_code == 404
    assert resp.headers["content-type"].startswith("application/problem+json")
    body = resp.json()
    assert body["type"] == "/errors/not-found"
    assert body["status"] == 404
    assert "series 999999999" in body["detail"]


def test_422_on_bad_pagination(auth_client: httpx.Client) -> None:
    """Limit above 1000 should be a validation error."""
    resp = auth_client.get("/api/v2/series?limit=2000")
    assert resp.status_code == 422
    body = resp.json()
    assert body["type"] == "/errors/validation"
    assert "limit" in body["detail"]


def test_422_on_unknown_sort(auth_client: httpx.Client) -> None:
    resp = auth_client.get("/api/v2/series?sort=unknown_field")
    assert resp.status_code == 422
    assert resp.json()["type"] == "/errors/validation"


def test_problem_content_type_on_errors(auth_client: httpx.Client) -> None:
    """Error responses MUST use application/problem+json per RFC 7807."""
    resp = auth_client.get("/api/v2/downloads/unknown/unknown")
    assert resp.status_code == 404
    assert "problem+json" in resp.headers["content-type"]
