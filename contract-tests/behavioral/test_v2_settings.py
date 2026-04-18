"""GET /api/v2/settings — secrets must render as '***' (Spec C §7.1)."""
from __future__ import annotations

import os

import httpx
import pytest


pytestmark = pytest.mark.skipif(
    os.environ.get("CONTRACT_TEST_API_KEY") is None,
    reason="CONTRACT_TEST_API_KEY not set",
)


SECRET_FIELDS = {"sonarrApiKey", "tautulliApiKey", "sabApiKey"}
OPTIONAL_SECRETS = {"qbitPassword", "plexToken", "redisUrl", "apiKey"}


def test_secrets_redacted(auth_client: httpx.Client) -> None:
    body = auth_client.get("/api/v2/settings").json()
    for field in SECRET_FIELDS:
        assert body[field] == "***", f"{field} must be redacted to '***', got {body[field]!r}"
    for field in OPTIONAL_SECRETS:
        if body.get(field) is not None:
            assert body[field] == "***", f"{field} must be redacted, got {body[field]!r}"


def test_non_secret_fields_pass_through(auth_client: httpx.Client) -> None:
    body = auth_client.get("/api/v2/settings").json()
    assert isinstance(body["dryRun"], bool)
    assert isinstance(body["logLevel"], str)
    assert body["sonarrUrl"].startswith("http")
