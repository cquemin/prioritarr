"""Auto-generated conformance tests from the OpenAPI spec.

Schemathesis reads /openapi.json, generates hypothesis inputs for every
operation, and verifies every response against the declared schema.
"""
from __future__ import annotations

import os

import schemathesis

BASE_URL = os.environ.get("CONTRACT_TEST_BASE_URL", "http://localhost:8000")

schema = schemathesis.from_uri(f"{BASE_URL}/openapi.json")


@schema.parametrize(endpoint="/health")
def test_health_conformance(case) -> None:
    response = case.call()
    case.validate_response(response)


@schema.parametrize(endpoint="/ready")
def test_ready_conformance(case) -> None:
    response = case.call()
    case.validate_response(response)


@schema.parametrize(endpoint="/api/sonarr/on-grab")
def test_sonarr_ongrab_conformance(case) -> None:
    response = case.call()
    case.validate_response(response)


@schema.parametrize(endpoint="/api/plex-event")
def test_plex_event_conformance(case) -> None:
    response = case.call()
    case.validate_response(response)
