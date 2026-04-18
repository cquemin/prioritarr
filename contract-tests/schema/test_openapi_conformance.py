"""Auto-generated conformance tests from the OpenAPI spec.

Schemathesis reads /openapi.json, generates hypothesis inputs for every
operation, and verifies every response against the declared schema.
"""
from __future__ import annotations

import os

import schemathesis
from schemathesis import experimental
from schemathesis.checks import response_schema_conformance

BASE_URL = os.environ.get("CONTRACT_TEST_BASE_URL", "http://localhost:8000")
API_KEY = os.environ.get("CONTRACT_TEST_API_KEY")

# FastAPI emits OpenAPI 3.1, which Schemathesis 3.x supports behind a feature flag.
experimental.OPEN_API_3_1.enable()

schema = schemathesis.from_uri(f"{BASE_URL}/openapi.json")

# v2 is kotlin-only. If the runtime schema doesn't advertise v2 paths,
# schemathesis would fail parametrize collection for test_v2_*_conformance
# with "does not match any API operations". Compute this once so the v2
# tests can be pytest-skipped wholesale.
_HAS_V2 = any(p.startswith("/api/v2/") for p in schema.raw_schema.get("paths", {}))


@schemathesis.hook
def before_call(context, case):
    """Inject X-Api-Key on every v2 request so Schemathesis doesn't 401.

    Must be registered on the module-level `schemathesis.hook` dispatcher
    (GLOBAL scope). `@schema.hook` only accepts per-schema hooks, and
    `before_call` isn't one — Schemathesis fails collection with a
    ScopeError if we try.
    """
    if API_KEY and case.path.startswith("/api/v2/"):
        case.headers = {**(case.headers or {}), "X-Api-Key": API_KEY}


# Only run response_schema_conformance — the default check set includes
# not_a_server_error, which fails on 5xx. The current spec (v1) does not
# declare structured error responses for malformed hypothesis-generated
# payloads. Structured errors (RFC 7807) arrive with Spec C; until then,
# we lock only the schemas we *do* declare (the 200 webhook shapes,
# /health and /ready bodies).
_CHECKS = (response_schema_conformance,)


@schema.parametrize(endpoint="/health")
def test_health_conformance(case) -> None:
    response = case.call()
    case.validate_response(response, checks=_CHECKS)


@schema.parametrize(endpoint="/ready")
def test_ready_conformance(case) -> None:
    response = case.call()
    case.validate_response(response, checks=_CHECKS)


@schema.parametrize(endpoint="/api/sonarr/on-grab")
def test_sonarr_ongrab_conformance(case) -> None:
    response = case.call()
    case.validate_response(response, checks=_CHECKS)


@schema.parametrize(endpoint="/api/plex-event")
def test_plex_event_conformance(case) -> None:
    response = case.call()
    case.validate_response(response, checks=_CHECKS)


# ---- v2 conformance ----
# Guard every v2 parametrize so the tests skip cleanly when running
# against a v1-only backend (e.g. the python contract-tests job).

import pytest  # noqa: E402 — imported late so the v1 tests above don't depend on it

pytestmark_v2 = pytest.mark.skipif(not _HAS_V2, reason="runtime schema has no /api/v2/* paths")

if _HAS_V2:
    @schema.parametrize(endpoint="/api/v2/series")
    def test_v2_series_conformance(case) -> None:
        response = case.call()
        case.validate_response(response, checks=_CHECKS)

    @schema.parametrize(endpoint="/api/v2/settings")
    def test_v2_settings_conformance(case) -> None:
        response = case.call()
        case.validate_response(response, checks=_CHECKS)

    @schema.parametrize(endpoint="/api/v2/mappings")
    def test_v2_mappings_conformance(case) -> None:
        response = case.call()
        case.validate_response(response, checks=_CHECKS)
