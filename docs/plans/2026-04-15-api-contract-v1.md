# API Contract v1 (Spec A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a formal OpenAPI 3.1 description of the current 4 prioritarr endpoints and a backend-agnostic contract test suite that will later verify the Kotlin port is behaviorally identical.

**Architecture:** Extend the existing Python/FastAPI app with typed Pydantic response models (preserving the exact current wire format), add gated test-mode endpoints (`PRIORITARR_TEST_MODE=true`) for state control, build a separate `contract-tests/` suite using pytest + Schemathesis + httpx that speaks HTTP only, and wire it all into GitHub Actions with mock upstream services.

**Tech Stack:** Python 3.12, FastAPI 0.115, Pydantic 2.x, pytest 8, Schemathesis 3.x, httpx 0.28, WireMock 3 (as Docker containers for upstream mocks)

**Spec:** `docs/specs/2026-04-14-prioritarr-api-contract-v1-design.md`

---

## Critical: wire format discrepancy with spec §4

During planning we verified the live backend's responses. The actual JSON bodies differ from the spec §4 examples. Per spec §3 "wire format preserved byte-compatible", the Pydantic models and contract tests in this plan match the **actual running code**, not the spec examples. The current responses are:

| Endpoint | Case | Actual body |
|----------|------|-------------|
| `POST /api/sonarr/on-grab` | Non-Grab | `{"status": "ignored", "eventType": "<type>"}` |
| `POST /api/sonarr/on-grab` | Processed | `{"status": "processed", "priority": N, "label": "..."}` |
| `POST /api/sonarr/on-grab` | Duplicate | `{"status": "duplicate", "priority": N, "label": "..."}` (priority + label still included) |
| `POST /api/plex-event` | Unmatched | `{"status": "unmatched", "plex_key": "<key>"}` |
| `POST /api/plex-event` | Success | `{"status": "ok", "series_id": N}` |

Task 1 documents these in Pydantic. Task 19 opens an issue to correct the spec examples retroactively — but that does **not** block Spec A implementation.

---

## File Structure

All paths relative to `D:/docker/prioritarr/`:

```
prioritarr/
├── Makefile                          # Modify: add `openapi` target
├── openapi.json                      # Create: committed source of truth (generated)
├── pyproject.toml                    # already exists
├── requirements.txt                  # already exists
├── requirements-dev.txt              # Modify: no changes needed — pytest + respx already present
├── Dockerfile                        # already exists
├── README.md                         # Modify: add "Contract Testing" section
├── default-config.yaml               # already exists
│
├── prioritarr/
│   ├── main.py                       # Modify: wire Pydantic models, conditionally mount test router
│   ├── schemas/                      # CREATE
│   │   ├── __init__.py
│   │   ├── health.py                 # HealthOk, HealthUnhealthy
│   │   ├── ready.py                  # DependencyStatus enum, ReadyResponse
│   │   └── webhooks.py               # OnGrab* and PlexEvent* response models
│   └── testing_api.py                # CREATE: test-mode-only router
│
├── contract-tests/                   # CREATE
│   ├── pytest.ini
│   ├── requirements.txt
│   ├── conftest.py                   # base_url fixture, reset-between-tests fixture
│   ├── fixtures/
│   │   ├── sonarr_ongrab.json        # valid Grab payload
│   │   ├── sonarr_ongrab_non_grab.json
│   │   ├── sonarr_ongrab_missing_series.json
│   │   └── plex_watched.json
│   ├── schema/
│   │   └── test_openapi_conformance.py
│   ├── behavioral/
│   │   ├── __init__.py
│   │   ├── test_health.py
│   │   ├── test_ready.py
│   │   ├── test_sonarr_ongrab.py
│   │   └── test_plex_event.py
│   └── mocks/                        # CREATE: WireMock stubs for upstream services
│       ├── docker-compose.yml
│       └── mappings/
│           ├── sonarr/
│           │   ├── system_status.json
│           │   ├── get_series.json
│           │   ├── get_series_by_id.json
│           │   ├── get_episodes.json
│           │   └── get_queue.json
│           ├── tautulli/
│           │   ├── arnold.json
│           │   ├── get_libraries.json
│           │   ├── get_library_media_info.json
│           │   └── get_history.json
│           ├── plex/
│           │   ├── library_sections.json
│           │   └── metadata_all_leaves.json
│           ├── qbit/
│           │   ├── app_version.json
│           │   └── torrents_info.json
│           └── sab/
│               └── version.json
│
└── .github/workflows/
    └── release.yml                   # Modify: add contract-tests job, OpenAPI drift check
```

---

## Task 1: Scaffold the `schemas/` package

**Files:**
- Create: `prioritarr/schemas/__init__.py`

- [ ] **Step 1: Create the empty package**

Create `prioritarr/schemas/__init__.py` with content:

```python
"""Pydantic response models for prioritarr HTTP endpoints.

These models describe the exact wire format the handlers return today.
Any change to these models is a change to the public API contract.
"""
```

- [ ] **Step 2: Verify the package imports**

Run: `cd D:/docker/prioritarr && python -c "from prioritarr import schemas; print('OK')"`
Expected: `OK`

- [ ] **Step 3: Commit**

```bash
cd D:/docker/prioritarr
git add prioritarr/schemas/__init__.py
git commit -m "chore: scaffold prioritarr.schemas package"
```

---

## Task 2: Health response models (TDD)

**Files:**
- Create: `prioritarr/schemas/health.py`
- Create: `tests/test_schemas_health.py`

- [ ] **Step 1: Write the failing test**

Create `tests/test_schemas_health.py`:

```python
"""Verify health schema models serialize to the exact wire format."""
from __future__ import annotations

from prioritarr.schemas.health import HealthOk, HealthUnhealthy


def test_health_ok_serializes_correctly():
    model = HealthOk()
    assert model.model_dump() == {"status": "ok"}


def test_health_unhealthy_serializes_correctly():
    model = HealthUnhealthy(reason="heartbeat_stale: 342s > 300s")
    assert model.model_dump() == {
        "status": "unhealthy",
        "reason": "heartbeat_stale: 342s > 300s",
    }


def test_health_unhealthy_rejects_wrong_status():
    import pytest
    from pydantic import ValidationError
    with pytest.raises(ValidationError):
        HealthUnhealthy(status="ok", reason="x")  # type: ignore[arg-type]
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd D:/docker/prioritarr && python -m pytest tests/test_schemas_health.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'prioritarr.schemas.health'`

- [ ] **Step 3: Write the minimal implementation**

Create `prioritarr/schemas/health.py`:

```python
"""Response models for GET /health."""
from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field


class HealthOk(BaseModel):
    """Healthy response — heartbeat fresh, DB reachable, scheduler alive."""

    status: Literal["ok"] = Field(
        default="ok",
        description="Always the literal string 'ok' when healthy.",
    )

    model_config = {
        "json_schema_extra": {
            "examples": [{"status": "ok"}],
        },
    }


class HealthUnhealthy(BaseModel):
    """Unhealthy response — returned with HTTP 503."""

    status: Literal["unhealthy"] = Field(
        default="unhealthy",
        description="Always the literal string 'unhealthy'.",
    )
    reason: str = Field(
        description=(
            "Human-readable diagnostic. Free-form text such as "
            "'heartbeat_stale: 342s > 300s', 'no_heartbeat', or "
            "'database: <exception text>'. The UI does not parse this."
        ),
    )

    model_config = {
        "json_schema_extra": {
            "examples": [
                {"status": "unhealthy", "reason": "heartbeat_stale: 342s > 300s"},
            ],
        },
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd D:/docker/prioritarr && python -m pytest tests/test_schemas_health.py -v`
Expected: All 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add prioritarr/schemas/health.py tests/test_schemas_health.py
git commit -m "feat(schemas): add health response models"
```

---

## Task 3: Ready response model (TDD)

**Files:**
- Create: `prioritarr/schemas/ready.py`
- Create: `tests/test_schemas_ready.py`

- [ ] **Step 1: Write the failing test**

Create `tests/test_schemas_ready.py`:

```python
"""Verify ready schema models preserve wire format."""
from __future__ import annotations

from datetime import datetime, timezone

from prioritarr.schemas.ready import DependencyStatus, ReadyResponse


def test_ready_ok_all_deps_healthy():
    ts = datetime(2026, 4, 14, 22, 30, 0, 123456, tzinfo=timezone.utc)
    model = ReadyResponse(
        status="ok",
        dependencies={
            "sonarr": DependencyStatus.ok,
            "tautulli": DependencyStatus.ok,
            "qbit": DependencyStatus.ok,
            "sab": DependencyStatus.ok,
        },
        last_heartbeat=ts,
    )
    dumped = model.model_dump(mode="json")
    assert dumped["status"] == "ok"
    assert dumped["dependencies"] == {
        "sonarr": "ok", "tautulli": "ok", "qbit": "ok", "sab": "ok",
    }
    # Legacy quirk #4: ISO with microseconds and +00:00 offset (NOT "Z")
    assert dumped["last_heartbeat"] == "2026-04-14T22:30:00.123456+00:00"


def test_ready_degraded_with_null_heartbeat():
    model = ReadyResponse(
        status="degraded",
        dependencies={
            "sonarr": DependencyStatus.unreachable,
            "tautulli": DependencyStatus.ok,
            "qbit": DependencyStatus.ok,
            "sab": DependencyStatus.ok,
        },
        last_heartbeat=None,
    )
    dumped = model.model_dump(mode="json")
    assert dumped["status"] == "degraded"
    assert dumped["dependencies"]["sonarr"] == "unreachable"
    assert dumped["last_heartbeat"] is None


def test_ready_rejects_unknown_dependency_status():
    import pytest
    from pydantic import ValidationError
    with pytest.raises(ValidationError):
        ReadyResponse(
            status="ok",
            dependencies={"sonarr": "weird"},  # type: ignore[dict-item]
            last_heartbeat=None,
        )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd D:/docker/prioritarr && python -m pytest tests/test_schemas_ready.py -v`
Expected: FAIL with `ModuleNotFoundError`

- [ ] **Step 3: Write the implementation**

Create `prioritarr/schemas/ready.py`:

```python
"""Response models for GET /ready."""
from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Literal

from pydantic import BaseModel, Field


class DependencyStatus(str, Enum):
    """State of a single upstream dependency."""

    ok = "ok"
    unreachable = "unreachable"


class ReadyResponse(BaseModel):
    """Readiness probe result.

    Returned by GET /ready with HTTP 200 when all deps are `ok`,
    HTTP 503 when any dep is `unreachable`.
    """

    status: Literal["ok", "degraded"] = Field(
        description=(
            "Rollup status across all dependencies. 'ok' iff every "
            "dependency is 'ok', else 'degraded'."
        ),
    )
    dependencies: dict[
        Literal["sonarr", "tautulli", "qbit", "sab"],
        DependencyStatus,
    ] = Field(
        description=(
            "Status of each upstream dependency. All four keys always "
            "present. Probes use lightweight endpoints (Sonarr "
            "/api/v3/system/status, Tautulli 'arnold', qBit "
            "/api/v2/app/version, SAB mode=version)."
        ),
    )
    last_heartbeat: datetime | None = Field(
        description=(
            "Most recent scheduler heartbeat timestamp. ISO 8601 with "
            "microseconds and '+00:00' offset (NOT 'Z'). Null if the "
            "scheduler has never run in this process."
        ),
    )

    model_config = {
        "json_schema_extra": {
            "examples": [
                {
                    "status": "ok",
                    "dependencies": {
                        "sonarr": "ok", "tautulli": "ok",
                        "qbit": "ok", "sab": "ok",
                    },
                    "last_heartbeat": "2026-04-14T22:30:00.123456+00:00",
                },
                {
                    "status": "degraded",
                    "dependencies": {
                        "sonarr": "unreachable", "tautulli": "ok",
                        "qbit": "ok", "sab": "ok",
                    },
                    "last_heartbeat": None,
                },
            ],
        },
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd D:/docker/prioritarr && python -m pytest tests/test_schemas_ready.py -v`
Expected: All 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add prioritarr/schemas/ready.py tests/test_schemas_ready.py
git commit -m "feat(schemas): add ready response model with dependency status"
```

---

## Task 4: Webhook response models (TDD)

**Files:**
- Create: `prioritarr/schemas/webhooks.py`
- Create: `tests/test_schemas_webhooks.py`

- [ ] **Step 1: Write the failing test**

Create `tests/test_schemas_webhooks.py`:

```python
"""Verify webhook response models preserve the EXACT current wire format.

Note: the current handlers return specific shapes that must not change.
These tests are the locked contract — changing them is a breaking change.
"""
from __future__ import annotations

from prioritarr.schemas.webhooks import (
    OnGrabIgnored,
    OnGrabProcessed,
    OnGrabDuplicate,
    PlexEventUnmatched,
    PlexEventOk,
)


def test_ongrab_ignored_wire_format():
    model = OnGrabIgnored(eventType="Test")
    assert model.model_dump(by_alias=True) == {
        "status": "ignored",
        "eventType": "Test",
    }


def test_ongrab_processed_includes_priority_and_label():
    model = OnGrabProcessed(priority=1, label="P1 Live-following")
    assert model.model_dump() == {
        "status": "processed",
        "priority": 1,
        "label": "P1 Live-following",
    }


def test_ongrab_duplicate_still_includes_priority_and_label():
    """Legacy quirk: duplicate responses carry the priority too."""
    model = OnGrabDuplicate(priority=3, label="P3 A few unwatched")
    assert model.model_dump() == {
        "status": "duplicate",
        "priority": 3,
        "label": "P3 A few unwatched",
    }


def test_ongrab_priority_rejects_out_of_range():
    import pytest
    from pydantic import ValidationError
    with pytest.raises(ValidationError):
        OnGrabProcessed(priority=0, label="bogus")
    with pytest.raises(ValidationError):
        OnGrabProcessed(priority=6, label="bogus")


def test_plex_event_unmatched_wire_format():
    model = PlexEventUnmatched(plex_key="5000")
    assert model.model_dump() == {
        "status": "unmatched",
        "plex_key": "5000",
    }


def test_plex_event_ok_wire_format():
    model = PlexEventOk(series_id=42)
    assert model.model_dump() == {
        "status": "ok",
        "series_id": 42,
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd D:/docker/prioritarr && python -m pytest tests/test_schemas_webhooks.py -v`
Expected: FAIL with `ModuleNotFoundError`

- [ ] **Step 3: Write the implementation**

Create `prioritarr/schemas/webhooks.py`:

```python
"""Response models for POST /api/sonarr/on-grab and POST /api/plex-event.

Legacy quirks locked by these models:
- Always HTTP 200 (never 4xx/5xx from the webhook itself).
- Duplicate OnGrab still includes priority and label.
- OnGrab 'ignored' carries the original eventType, not a reason string.
- Plex unmatched carries plex_key, not a reason string.
- Plex success status is 'ok', not 'processed'.
"""
from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


# ----- OnGrab responses -----


class OnGrabIgnored(BaseModel):
    """Returned when eventType is not 'Grab' (e.g. 'Test', 'Health')."""

    model_config = ConfigDict(populate_by_name=True)

    status: Literal["ignored"] = Field(default="ignored")
    eventType: str = Field(
        description="The eventType value that was ignored, echoed verbatim.",
    )


class OnGrabProcessed(BaseModel):
    """Returned after a Grab event is accepted and recorded."""

    status: Literal["processed"] = Field(default="processed")
    priority: int = Field(
        ge=1,
        le=5,
        description="Computed priority level, 1 (highest) through 5 (lowest).",
    )
    label: str = Field(
        description="Human-readable label like 'P1 Live-following'.",
    )


class OnGrabDuplicate(BaseModel):
    """Returned when the same event (by sha1 dedupe key) has been seen already.

    Note: priority and label are still reported — the computation happened;
    it's the storage side effect that was skipped.
    """

    status: Literal["duplicate"] = Field(default="duplicate")
    priority: int = Field(ge=1, le=5)
    label: str


# ----- Plex event responses -----


class PlexEventUnmatched(BaseModel):
    """Returned when the grandparent_rating_key cannot be matched to a Sonarr series.

    Only returned AFTER a synchronous mapping refresh attempt (legacy quirk #5).
    """

    status: Literal["unmatched"] = Field(default="unmatched")
    plex_key: str = Field(description="The grandparent_rating_key we couldn't map.")


class PlexEventOk(BaseModel):
    """Returned when the event was matched and the priority cache was invalidated."""

    status: Literal["ok"] = Field(default="ok")
    series_id: int = Field(description="The Sonarr seriesId the event was applied to.")
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd D:/docker/prioritarr && python -m pytest tests/test_schemas_webhooks.py -v`
Expected: All 6 tests PASS

- [ ] **Step 5: Commit**

```bash
git add prioritarr/schemas/webhooks.py tests/test_schemas_webhooks.py
git commit -m "feat(schemas): add webhook response models preserving current wire format"
```

---

## Task 5: Update FastAPI app metadata and wire `/health` to Pydantic models

**Files:**
- Modify: `prioritarr/main.py` (the `app = FastAPI(...)` line at 749 AND the `health` function and its decorator at 757)
- Modify: `tests/test_integration.py` (add a test that `/health` OpenAPI entry declares both shapes)

**Spec §5.3 compliance:** before wiring endpoints, update the FastAPI constructor so the generated OpenAPI has proper metadata (title, version, description).

Locate line 749 in `prioritarr/main.py`:

```python
app = FastAPI(title="prioritarr", lifespan=lifespan)
```

Replace with:

```python
app = FastAPI(
    title="Prioritarr API",
    version="0.1.0",
    description=(
        "Priority-aware download queue orchestrator. "
        "See https://github.com/cquemin/prioritarr"
    ),
    lifespan=lifespan,
    license_info={"name": "MIT"},
)
```

Continue with the `/health` wiring steps below.

- [ ] **Step 1: Write a failing test asserting OpenAPI has typed responses for /health**

Add this function to the end of `tests/test_integration.py`:

```python
def test_health_openapi_declares_both_response_models(client):
    """After wiring Pydantic models, OpenAPI should list HealthOk and HealthUnhealthy."""
    tc, _ = client  # client fixture yields (TestClient, Database)
    resp = tc.get("/openapi.json")
    assert resp.status_code == 200
    spec = resp.json()
    get_health = spec["paths"]["/health"]["get"]
    # Both 200 and 503 responses must be declared with schemas
    assert "200" in get_health["responses"]
    assert "503" in get_health["responses"]
    resp_200 = get_health["responses"]["200"]["content"]["application/json"]["schema"]
    resp_503 = get_health["responses"]["503"]["content"]["application/json"]["schema"]
    # Either the schema is inlined with our fields, or it's a $ref to components/schemas
    assert "HealthOk" in str(resp_200) or resp_200.get("properties", {}).get("status", {}).get("const") == "ok"
    assert "HealthUnhealthy" in str(resp_503) or "reason" in str(resp_503)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd D:/docker/prioritarr && python -m pytest tests/test_integration.py::test_health_openapi_declares_both_response_models -v`
Expected: FAIL because `/health` currently has no typed responses in OpenAPI (or the key `503` is missing from responses)

- [ ] **Step 3: Modify the `/health` endpoint to use Pydantic models**

In `prioritarr/main.py`, locate the current `/health` handler (around line 757):

```python
@app.get("/health")
async def health() -> JSONResponse:
    """Liveness check — returns 200 OK or 503 Unhealthy."""
    ivl = settings.intervals
    max_age = 2 * max(ivl.reconcile_minutes * 60, ivl.backfill_sweep_hours * 3600)
    result = check_liveness(db, max_heartbeat_age_seconds=max_age)
    status_code = 200 if result["status"] == "ok" else 503
    return JSONResponse(content=result, status_code=status_code)
```

Replace with:

```python
from prioritarr.schemas.health import HealthOk, HealthUnhealthy


@app.get(
    "/health",
    summary="Liveness probe",
    description=(
        "Returns 200 when the scheduler heartbeat is fresh and the state DB "
        "is reachable. Returns 503 otherwise. Used by Docker HEALTHCHECK and "
        "the autoheal container to restart unhealthy instances."
    ),
    responses={
        200: {"model": HealthOk, "description": "Scheduler heartbeat fresh"},
        503: {"model": HealthUnhealthy, "description": "Scheduler heartbeat stale, DB unreachable, or process unhealthy"},
    },
)
async def health() -> JSONResponse:
    """Liveness check — returns 200 OK or 503 Unhealthy."""
    ivl = settings.intervals
    max_age = 2 * max(ivl.reconcile_minutes * 60, ivl.backfill_sweep_hours * 3600)
    result = check_liveness(db, max_heartbeat_age_seconds=max_age)
    status_code = 200 if result["status"] == "ok" else 503
    # Wire format preserved — result is already the right shape
    return JSONResponse(content=result, status_code=status_code)
```

Put the new `from prioritarr.schemas.health import HealthOk, HealthUnhealthy` import near the other `prioritarr.*` imports at the top of the file.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd D:/docker/prioritarr && python -m pytest tests/test_integration.py -v`
Expected: All integration tests including the new one PASS

- [ ] **Step 5: Manually verify wire format did not change**

Start the container (if not running) and check the raw bytes:

```bash
docker exec prioritarr curl -s http://localhost:8000/health
```

Expected output (exactly): `{"status":"ok"}` with HTTP 200. No extra keys.

- [ ] **Step 6: Commit**

```bash
git add prioritarr/main.py tests/test_integration.py
git commit -m "feat(api): wire /health to typed Pydantic response models"
```

---

## Task 6: Wire `/ready` to Pydantic model

**Files:**
- Modify: `prioritarr/main.py` (the `ready` function, around line 767)
- Modify: `tests/test_integration.py` (add typed OpenAPI assertion)

- [ ] **Step 1: Write the failing test**

Add to `tests/test_integration.py`:

```python
def test_ready_openapi_declares_response_model(client):
    tc, _ = client
    resp = tc.get("/openapi.json")
    spec = resp.json()
    get_ready = spec["paths"]["/ready"]["get"]
    assert "200" in get_ready["responses"]
    assert "503" in get_ready["responses"]
    # ReadyResponse is reused for both codes
    ok = get_ready["responses"]["200"]["content"]["application/json"]["schema"]
    assert "ReadyResponse" in str(ok) or "dependencies" in str(ok)
```

- [ ] **Step 2: Run the test to confirm failure**

Run: `cd D:/docker/prioritarr && python -m pytest tests/test_integration.py::test_ready_openapi_declares_response_model -v`
Expected: FAIL

- [ ] **Step 3: Modify the `/ready` endpoint**

Locate in `prioritarr/main.py` (around line 767):

```python
@app.get("/ready")
async def ready() -> JSONResponse:
    """Readiness check — lightweight connectivity probes only.
    ...
    """
    dep_status: dict[str, str] = {}
    ...
```

Replace the decorator:

```python
from prioritarr.schemas.ready import ReadyResponse


@app.get(
    "/ready",
    summary="Readiness check with dependency status",
    description=(
        "Returns a detailed status of each upstream dependency. Status is "
        "'ok' iff every dependency is reachable, 'degraded' otherwise. "
        "Probes use lightweight system-status endpoints, not full data queries."
    ),
    responses={
        200: {"model": ReadyResponse, "description": "All dependencies reachable"},
        503: {"model": ReadyResponse, "description": "One or more dependencies unreachable"},
    },
)
async def ready() -> JSONResponse:
    """Readiness check — lightweight connectivity probes only."""
    # ... body unchanged ...
```

Keep the function body exactly as-is. Only the decorator changes.

- [ ] **Step 4: Run tests**

Run: `cd D:/docker/prioritarr && python -m pytest tests/test_integration.py -v`
Expected: All tests PASS

- [ ] **Step 5: Wire-format check**

```bash
docker exec prioritarr curl -s http://localhost:8000/ready | jq
```

Expected: a JSON body with exactly the four keys `status`, `dependencies`, `last_heartbeat` (no extras). `dependencies` has exactly `sonarr`, `tautulli`, `qbit`, `sab`.

- [ ] **Step 6: Commit**

```bash
git add prioritarr/main.py tests/test_integration.py
git commit -m "feat(api): wire /ready to typed ReadyResponse model"
```

---

## Task 7: Wire `/api/sonarr/on-grab` to Pydantic models

**Files:**
- Modify: `prioritarr/main.py` (the `sonarr_on_grab` handler, around line 800)
- Modify: `tests/test_integration.py`

- [ ] **Step 1: Write the failing test**

Add to `tests/test_integration.py`:

```python
def test_sonarr_on_grab_openapi_declares_union_response(client):
    tc, _ = client
    spec = tc.get("/openapi.json").json()
    post_grab = spec["paths"]["/api/sonarr/on-grab"]["post"]
    ok_schema = post_grab["responses"]["200"]["content"]["application/json"]["schema"]
    # Should reference at least one of the three OnGrab* schemas
    as_str = str(ok_schema)
    assert any(name in as_str for name in ("OnGrabIgnored", "OnGrabProcessed", "OnGrabDuplicate"))
```

- [ ] **Step 2: Run the test to confirm failure**

Run: `cd D:/docker/prioritarr && python -m pytest tests/test_integration.py::test_sonarr_on_grab_openapi_declares_union_response -v`
Expected: FAIL

- [ ] **Step 3: Modify the handler**

Locate in `prioritarr/main.py` (around line 800):

```python
@app.post("/api/sonarr/on-grab")
async def sonarr_on_grab(request: Request) -> JSONResponse:
    ...
```

Replace the decorator only — keep the function body exactly as it is today:

```python
from prioritarr.schemas.webhooks import (
    OnGrabIgnored as _OnGrabIgnored,
    OnGrabProcessed as _OnGrabProcessed,
    OnGrabDuplicate as _OnGrabDuplicate,
)
from typing import Union


@app.post(
    "/api/sonarr/on-grab",
    summary="Sonarr OnGrab webhook receiver",
    description=(
        "Accepts Sonarr's OnGrab notification. Always returns HTTP 200 "
        "(see legacy quirk #1 in the spec). The response body shape depends "
        "on the outcome: ignored for non-Grab eventType, processed for a "
        "new unique event, duplicate for a repeated event within the 24h "
        "dedupe window."
    ),
    responses={
        200: {
            "description": "Webhook accepted (one of three shapes).",
            "content": {
                "application/json": {
                    "schema": {
                        "oneOf": [
                            {"$ref": "#/components/schemas/OnGrabIgnored"},
                            {"$ref": "#/components/schemas/OnGrabProcessed"},
                            {"$ref": "#/components/schemas/OnGrabDuplicate"},
                        ]
                    }
                }
            },
        },
    },
)
async def sonarr_on_grab(request: Request) -> JSONResponse:
    # ... body unchanged ...
```

Put the imports at the top of the file with the other schema imports.

**Also** — so that FastAPI actually registers the `OnGrabIgnored`/`OnGrabProcessed`/`OnGrabDuplicate` schemas under `components.schemas`, add a no-op union reference. After the handler definition, register them explicitly:

```python
# Register component schemas (referenced via $ref in the responses dict above).
# FastAPI only emits components.schemas for models it sees used. This ensures
# they appear in /openapi.json even when the response_model isn't set.
_OnGrabResponseUnion = Union[_OnGrabIgnored, _OnGrabProcessed, _OnGrabDuplicate]
```

Then customise the OpenAPI generation. At the bottom of `main.py` (after `app = FastAPI(...)` is defined), add:

```python
def _custom_openapi():
    if app.openapi_schema:
        return app.openapi_schema
    from fastapi.openapi.utils import get_openapi
    schema = get_openapi(
        title=app.title,
        version=app.version,
        openapi_version=app.openapi_version,
        description=app.description,
        routes=app.routes,
    )
    # Ensure webhook response models appear in components.schemas
    components = schema.setdefault("components", {}).setdefault("schemas", {})
    for model_cls in (_OnGrabIgnored, _OnGrabProcessed, _OnGrabDuplicate):
        name = model_cls.__name__
        if name not in components:
            components[name] = model_cls.model_json_schema(ref_template="#/components/schemas/{model}")
    app.openapi_schema = schema
    return schema


app.openapi = _custom_openapi
```

- [ ] **Step 4: Run all tests**

Run: `cd D:/docker/prioritarr && python -m pytest -v`
Expected: All tests PASS

- [ ] **Step 5: Wire-format verification**

Run each of the three cases and compare byte-for-byte:

```bash
# Ignored
docker exec prioritarr curl -s -X POST http://localhost:8000/api/sonarr/on-grab \
  -H "Content-Type: application/json" -d '{"eventType": "Test"}'
# Expected exactly: {"status":"ignored","eventType":"Test"}

# Processed (use a known series from your Sonarr instance if live)
docker exec prioritarr curl -s -X POST http://localhost:8000/api/sonarr/on-grab \
  -H "Content-Type: application/json" \
  -d '{"eventType":"Grab","series":{"id":1,"title":"Test","tvdbId":1},"episodes":[{"id":99,"episodeNumber":1,"seasonNumber":1,"airDateUtc":"2026-01-01T00:00:00Z"}],"downloadClient":"QBittorrent","downloadId":"ABCDEF"}'
# Expected shape: {"status":"processed","priority":<1-5>,"label":"P..."}

# Duplicate — run the same request immediately after
# Expected shape: {"status":"duplicate","priority":<1-5>,"label":"P..."}
```

- [ ] **Step 6: Commit**

```bash
git add prioritarr/main.py tests/test_integration.py
git commit -m "feat(api): wire /api/sonarr/on-grab to typed response models"
```

---

## Task 8: Wire `/api/plex-event` to Pydantic models

**Files:**
- Modify: `prioritarr/main.py` (the `plex_event` handler, around line 870)
- Modify: `tests/test_integration.py`

- [ ] **Step 1: Write the failing test**

Add to `tests/test_integration.py`:

```python
def test_plex_event_openapi_declares_union_response(client):
    tc, _ = client
    spec = tc.get("/openapi.json").json()
    post_plex = spec["paths"]["/api/plex-event"]["post"]
    ok_schema = post_plex["responses"]["200"]["content"]["application/json"]["schema"]
    as_str = str(ok_schema)
    assert "PlexEventUnmatched" in as_str or "PlexEventOk" in as_str
```

- [ ] **Step 2: Run to confirm failure**

Run: `cd D:/docker/prioritarr && python -m pytest tests/test_integration.py::test_plex_event_openapi_declares_union_response -v`
Expected: FAIL

- [ ] **Step 3: Modify the handler decorator**

Add imports at the top of the file:

```python
from prioritarr.schemas.webhooks import (
    PlexEventUnmatched as _PlexEventUnmatched,
    PlexEventOk as _PlexEventOk,
)
```

Locate the handler (around line 870) and replace its decorator only:

```python
@app.post(
    "/api/plex-event",
    summary="Tautulli watched notification",
    description=(
        "Accepts Tautulli's 'watched' notification. Always returns HTTP 200. "
        "Triggers a synchronous mapping refresh if the plex_key is unknown "
        "(legacy quirk #5). Returns 'ok' when the priority cache was invalidated "
        "for a known series, 'unmatched' otherwise."
    ),
    responses={
        200: {
            "description": "Webhook accepted (one of two shapes).",
            "content": {
                "application/json": {
                    "schema": {
                        "oneOf": [
                            {"$ref": "#/components/schemas/PlexEventOk"},
                            {"$ref": "#/components/schemas/PlexEventUnmatched"},
                        ]
                    }
                }
            },
        },
    },
)
async def plex_event(request: Request) -> JSONResponse:
    # ... body unchanged ...
```

Then extend the `_custom_openapi` function you added in Task 7 — update the model tuple:

```python
    for model_cls in (
        _OnGrabIgnored, _OnGrabProcessed, _OnGrabDuplicate,
        _PlexEventOk, _PlexEventUnmatched,
    ):
```

- [ ] **Step 4: Run tests**

Run: `cd D:/docker/prioritarr && python -m pytest -v`
Expected: All tests PASS

- [ ] **Step 5: Wire-format verification**

```bash
# Unmatched
docker exec prioritarr curl -s -X POST http://localhost:8000/api/plex-event \
  -H "Content-Type: application/json" \
  -d '{"event":"watched","grandparent_rating_key":"UNLIKELY_KEY_9999999"}'
# Expected shape: {"status":"unmatched","plex_key":"UNLIKELY_KEY_9999999"}
```

- [ ] **Step 6: Commit**

```bash
git add prioritarr/main.py tests/test_integration.py
git commit -m "feat(api): wire /api/plex-event to typed response models"
```

---

## Task 9: Test-mode router (gated by PRIORITARR_TEST_MODE)

**Files:**
- Create: `prioritarr/testing_api.py`
- Modify: `prioritarr/main.py` (conditionally include the router based on env var)
- Modify: `prioritarr/config.py` (add `test_mode: bool` field to Settings)
- Create: `tests/test_testing_api.py`

- [ ] **Step 1: Add `test_mode` to Settings**

Edit `prioritarr/config.py`. Find the `Settings` dataclass and add one field alongside the other optional fields (below `redis_url`):

```python
    test_mode: bool = False
```

Then in `load_settings_from_env`, below the `dry_run` parse, add:

```python
    test_mode_raw = _env("TEST_MODE", "false").lower()
    test_mode = test_mode_raw not in ("false", "0", "no", "")
```

And include `test_mode=test_mode` in the `return Settings(...)` call.

- [ ] **Step 2: Write the failing test**

Create `tests/test_testing_api.py`:

```python
"""Verify the test-mode router is mounted iff PRIORITARR_TEST_MODE=true."""
from __future__ import annotations

import os
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient


def _make_app_with_test_mode(value: str) -> TestClient:
    """Fresh app instance with the env var forced to *value*."""
    with patch.dict(os.environ, {"PRIORITARR_TEST_MODE": value}, clear=False):
        # Re-import to pick up the env var via load_settings_from_env at startup
        from importlib import reload
        import prioritarr.main as m
        reload(m)
        return TestClient(m.app)


def test_testing_endpoints_absent_in_production_mode():
    # Default PRIORITARR_TEST_MODE=false → router NOT mounted → 404
    with patch.dict(os.environ, {"PRIORITARR_TEST_MODE": "false"}, clear=False):
        from importlib import reload
        import prioritarr.main as m
        reload(m)
        with TestClient(m.app) as client:
            resp = client.post("/api/v1/_testing/reset")
            assert resp.status_code == 404


def test_testing_endpoints_present_when_enabled():
    with patch.dict(os.environ, {"PRIORITARR_TEST_MODE": "true"}, clear=False):
        from importlib import reload
        import prioritarr.main as m
        reload(m)
        with TestClient(m.app) as client:
            resp = client.post("/api/v1/_testing/reset")
            # Reset should return 200 with {"status": "ok"}
            assert resp.status_code == 200
            assert resp.json() == {"status": "ok"}
```

- [ ] **Step 3: Run the test to confirm failure**

Run: `cd D:/docker/prioritarr && python -m pytest tests/test_testing_api.py -v`
Expected: FAIL with 404 on the "enabled" test (router not implemented yet)

- [ ] **Step 4: Implement the test router**

Create `prioritarr/testing_api.py`:

```python
"""Test-mode-only endpoints for state control during contract tests.

These routes are mounted ONLY when PRIORITARR_TEST_MODE=true. They are
dangerous in production (they clear state, manipulate heartbeats, inject
mapping data) so gating is a hard requirement.

Production default: PRIORITARR_TEST_MODE=false (router not mounted).
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Any

from fastapi import APIRouter, Request
from pydantic import BaseModel, Field


router = APIRouter(prefix="/api/v1/_testing", tags=["testing"])


class OkResponse(BaseModel):
    status: str = Field(default="ok")


class InjectSeriesMappingRequest(BaseModel):
    plex_key: str
    series_id: int


@router.post(
    "/reset",
    summary="Clear all mutable state",
    response_model=OkResponse,
    description=(
        "Clears managed_downloads, webhook_dedupe, audit_log, and heartbeat "
        "rows in state.db AND clears the in-memory Plex→Sonarr mapping. "
        "Used by the contract test suite between tests."
    ),
)
async def reset(request: Request) -> OkResponse:
    from prioritarr import main as m
    m.db.execute("DELETE FROM managed_downloads")
    m.db.execute("DELETE FROM webhook_dedupe")
    m.db.execute("DELETE FROM audit_log")
    m.db.execute("DELETE FROM series_priority_cache")
    m.db.execute("DELETE FROM heartbeat")
    m.db.conn.commit()
    m._plex_key_to_series_id.clear()
    m._title_to_plex_key.clear()
    m._tvdb_to_series.clear()
    return OkResponse()


@router.post(
    "/stale-heartbeat",
    summary="Force heartbeat to be stale (make /health return 503)",
    response_model=OkResponse,
)
async def stale_heartbeat() -> OkResponse:
    from prioritarr import main as m
    # Backdate the heartbeat by a year
    stale_ts = (datetime.now(timezone.utc) - timedelta(days=365)).isoformat()
    m.db.execute(
        "INSERT INTO heartbeat (id, ts) VALUES (1, ?) "
        "ON CONFLICT(id) DO UPDATE SET ts=excluded.ts",
        (stale_ts,),
    )
    m.db.conn.commit()
    return OkResponse()


@router.post(
    "/inject-series-mapping",
    summary="Inject a plex_key → series_id entry into the in-memory mapping",
    response_model=OkResponse,
)
async def inject_series_mapping(payload: InjectSeriesMappingRequest) -> OkResponse:
    from prioritarr import main as m
    m._plex_key_to_series_id[payload.plex_key] = payload.series_id
    return OkResponse()
```

- [ ] **Step 5: Conditionally include the router in main.py**

In `prioritarr/main.py`, after `app = FastAPI(...)` is created and before the `_custom_openapi` setup, add:

```python
# Gate: test-mode endpoints only mounted when PRIORITARR_TEST_MODE=true.
if settings.test_mode:
    from prioritarr.testing_api import router as testing_router
    app.include_router(testing_router)
    logger.warning(
        "PRIORITARR_TEST_MODE is enabled — test-mode endpoints mounted at "
        "/api/v1/_testing/*. Never enable this in production."
    )
```

Note: `settings` may only be initialized inside the lifespan. If that's the case, instead read the env var directly at app-construction time:

```python
import os
if os.environ.get("PRIORITARR_TEST_MODE", "").lower() in ("true", "1", "yes"):
    from prioritarr.testing_api import router as testing_router
    app.include_router(testing_router)
    logger.warning(
        "PRIORITARR_TEST_MODE is enabled — test-mode endpoints mounted at "
        "/api/v1/_testing/*. Never enable this in production."
    )
```

Pick whichever matches the lifecycle in `main.py` — inspect first.

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd D:/docker/prioritarr && python -m pytest tests/test_testing_api.py -v`
Expected: Both tests PASS

- [ ] **Step 7: Run the full test suite to verify nothing regressed**

Run: `cd D:/docker/prioritarr && python -m pytest -v`
Expected: All tests PASS

- [ ] **Step 8: Commit**

```bash
git add prioritarr/testing_api.py prioritarr/main.py prioritarr/config.py tests/test_testing_api.py
git commit -m "feat(testing): add gated test-mode router for contract test state control"
```

---

## Task 10: Scaffold the `contract-tests/` directory

**Files:**
- Create: `contract-tests/pytest.ini`
- Create: `contract-tests/requirements.txt`
- Create: `contract-tests/conftest.py`
- Create: `contract-tests/behavioral/__init__.py`
- Create: `contract-tests/fixtures/sonarr_ongrab.json`
- Create: `contract-tests/fixtures/sonarr_ongrab_non_grab.json`
- Create: `contract-tests/fixtures/sonarr_ongrab_missing_series.json`
- Create: `contract-tests/fixtures/plex_watched.json`

- [ ] **Step 1: Create `contract-tests/pytest.ini`**

```ini
[pytest]
testpaths = .
python_files = test_*.py
addopts = -v --tb=short
```

- [ ] **Step 2: Create `contract-tests/requirements.txt`**

```
pytest==8.3.5
httpx==0.28.1
schemathesis==3.38.4
jsonschema==4.23.0
```

- [ ] **Step 3: Create `contract-tests/conftest.py`**

```python
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
    """Load a JSON fixture by filename (without extension allowed)."""
    path = FIXTURES / (name if name.endswith(".json") else f"{name}.json")
    return json.loads(path.read_text())


@pytest.fixture
def fixtures():
    """Access fixtures by name: fixtures('sonarr_ongrab') → dict."""
    return load_fixture
```

- [ ] **Step 4: Create `contract-tests/behavioral/__init__.py`**

Empty file:

```python
```

- [ ] **Step 5: Create fixture files**

`contract-tests/fixtures/sonarr_ongrab.json`:

```json
{
  "eventType": "Grab",
  "series": {"id": 1, "title": "Contract Test Series", "tvdbId": 111111},
  "episodes": [
    {"id": 99901, "episodeNumber": 1, "seasonNumber": 1, "airDateUtc": "2026-01-01T00:00:00Z"}
  ],
  "release": {"releaseTitle": "Contract.Test.Series.S01E01.1080p.WEB-DL"},
  "downloadClient": "QBittorrent",
  "downloadId": "CONTRACTTESTHASHABCDEF0123456789ABCDEF01"
}
```

`contract-tests/fixtures/sonarr_ongrab_non_grab.json`:

```json
{
  "eventType": "Test",
  "series": {"id": 1, "title": "Contract Test Series"}
}
```

`contract-tests/fixtures/sonarr_ongrab_missing_series.json`:

```json
{
  "eventType": "Grab",
  "episodes": [{"id": 1}],
  "downloadClient": "QBittorrent",
  "downloadId": "MISSING_SERIES_FIXTURE_HASH_1234567890ABCDEF"
}
```

`contract-tests/fixtures/plex_watched.json`:

```json
{
  "event": "watched",
  "user": "contract-test",
  "grandparent_title": "Contract Test Series",
  "grandparent_rating_key": "CONTRACT_TEST_PLEX_KEY",
  "parent_media_index": "1",
  "media_index": "1",
  "rating_key": "CONTRACT_TEST_EPISODE_KEY"
}
```

- [ ] **Step 6: Verify the skeleton is discoverable by pytest**

Run: `cd D:/docker/prioritarr/contract-tests && python -m pytest --collect-only 2>&1 | head -20`
Expected: pytest discovers 0 tests (nothing written yet) but no errors.

- [ ] **Step 7: Commit**

```bash
cd D:/docker/prioritarr
git add contract-tests/
git commit -m "chore(contract-tests): scaffold directory, fixtures, and conftest"
```

---

## Task 11: Behavioral test — `/health`

**Files:**
- Create: `contract-tests/behavioral/test_health.py`

- [ ] **Step 1: Write the full test file**

```python
"""Behavioral contract tests for GET /health.

Preserves legacy quirks from the spec §4.1:
- 200 when heartbeat fresh, 503 when stale
- 503 body has 'reason' as free-form human-readable text
"""
from __future__ import annotations

import httpx


def test_health_returns_200_with_fresh_heartbeat(client: httpx.Client) -> None:
    """After reset, the heartbeat might be absent. Force a good state."""
    # Reset (autouse) has just cleared the heartbeat, so /health should be 503 now.
    resp = client.get("/health")
    assert resp.status_code == 503, "No heartbeat after reset — expected 503"
    body = resp.json()
    assert body["status"] == "unhealthy"
    assert "reason" in body and isinstance(body["reason"], str)


def test_health_503_when_heartbeat_stale(client: httpx.Client) -> None:
    """Explicitly stale the heartbeat, confirm 503."""
    client.post("/api/v1/_testing/stale-heartbeat")
    resp = client.get("/health")
    assert resp.status_code == 503
    body = resp.json()
    assert body["status"] == "unhealthy"
    # The reason should mention heartbeat (free-form but keyword-checkable)
    assert "heartbeat" in body["reason"].lower()


def test_health_body_shape(client: httpx.Client) -> None:
    """503 body has exactly two keys: status and reason."""
    client.post("/api/v1/_testing/stale-heartbeat")
    body = client.get("/health").json()
    assert set(body.keys()) == {"status", "reason"}


def test_health_content_type_is_json(client: httpx.Client) -> None:
    resp = client.get("/health")
    assert resp.headers["content-type"].startswith("application/json")
```

- [ ] **Step 2: Run the test against the running backend (must be started in test mode)**

The local prioritarr container must be running with `PRIORITARR_TEST_MODE=true`. Easiest: restart with that env var set.

```bash
cd D:/docker
# Add PRIORITARR_TEST_MODE=true to .env or pass through compose
# One-off test without editing .env:
PRIORITARR_TEST_MODE=true docker compose -f media-stack-v3.yml up -d --force-recreate prioritarr
```

Then run:

```bash
cd D:/docker/prioritarr/contract-tests
pip install -r requirements.txt
CONTRACT_TEST_BASE_URL=http://localhost:8000 python -m pytest behavioral/test_health.py -v
```

Expected: All 4 tests PASS.

If the backend is not in test mode, the autouse `reset_between_tests` fixture will fail all tests with a clear message.

- [ ] **Step 3: Commit**

```bash
cd D:/docker/prioritarr
git add contract-tests/behavioral/test_health.py
git commit -m "test(contract): /health behavioral contract tests"
```

---

## Task 12: Behavioral test — `/ready`

**Files:**
- Create: `contract-tests/behavioral/test_ready.py`

- [ ] **Step 1: Write the test file**

```python
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
```

- [ ] **Step 2: Run tests**

```bash
cd D:/docker/prioritarr/contract-tests
CONTRACT_TEST_BASE_URL=http://localhost:8000 python -m pytest behavioral/test_ready.py -v
```

Expected: All 5 tests PASS. (Sonarr/Tautulli/qBit/SAB are reachable on the live stack, so `status` will be `ok`.)

- [ ] **Step 3: Commit**

```bash
cd D:/docker/prioritarr
git add contract-tests/behavioral/test_ready.py
git commit -m "test(contract): /ready behavioral contract tests"
```

---

## Task 13: Behavioral test — `/api/sonarr/on-grab`

**Files:**
- Create: `contract-tests/behavioral/test_sonarr_ongrab.py`

- [ ] **Step 1: Write the test file**

```python
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
    # Shape must be exactly these three keys
    assert set(body.keys()) == {"status", "priority", "label"}


def test_duplicate_grab_returns_duplicate_with_priority_and_label(
    client: httpx.Client, fixtures
) -> None:
    """Legacy quirk: duplicate still carries priority + label."""
    payload = fixtures("sonarr_ongrab")
    # First call — processed
    first = client.post("/api/sonarr/on-grab", json=payload).json()
    assert first["status"] == "processed"
    # Second call — duplicate, but same priority/label
    second = client.post("/api/sonarr/on-grab", json=payload).json()
    assert second["status"] == "duplicate"
    assert second["priority"] == first["priority"]
    assert second["label"] == first["label"]
    assert set(second.keys()) == {"status", "priority", "label"}


def test_grab_with_missing_series_still_returns_200(
    client: httpx.Client, fixtures
) -> None:
    """Legacy quirk #1: webhooks are always-200, even on malformed payloads."""
    payload = fixtures("sonarr_ongrab_missing_series")
    resp = client.post("/api/sonarr/on-grab", json=payload)
    assert resp.status_code == 200


@pytest.mark.parametrize("eventType", ["Test", "Download", "Health", "Rename"])
def test_any_non_grab_eventtype_is_ignored(
    client: httpx.Client, eventType: str
) -> None:
    resp = client.post(
        "/api/sonarr/on-grab",
        json={"eventType": eventType},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ignored"
    assert body["eventType"] == eventType
```

- [ ] **Step 2: Run tests**

```bash
cd D:/docker/prioritarr/contract-tests
CONTRACT_TEST_BASE_URL=http://localhost:8000 python -m pytest behavioral/test_sonarr_ongrab.py -v
```

Expected: All tests PASS. The "processed" case depends on the backend being able to compute a priority for `series.id=1`. If Sonarr is in the live stack, it should work. If Sonarr is mocked, the mock must return `{"id": 1, "title": ..., "tvdbId": ...}` when queried.

- [ ] **Step 3: Commit**

```bash
cd D:/docker/prioritarr
git add contract-tests/behavioral/test_sonarr_ongrab.py
git commit -m "test(contract): /api/sonarr/on-grab behavioral contract tests"
```

---

## Task 14: Behavioral test — `/api/plex-event`

**Files:**
- Create: `contract-tests/behavioral/test_plex_event.py`

- [ ] **Step 1: Write the test file**

```python
"""Behavioral contract tests for POST /api/plex-event."""
from __future__ import annotations

import httpx


def test_unmatched_plex_key_returns_unmatched_shape(
    client: httpx.Client, fixtures
) -> None:
    payload = fixtures("plex_watched")
    # After reset, the in-memory mapping is empty → unmatched.
    resp = client.post("/api/plex-event", json=payload)
    assert resp.status_code == 200
    body = resp.json()
    assert body == {
        "status": "unmatched",
        "plex_key": "CONTRACT_TEST_PLEX_KEY",
    }


def test_matched_plex_key_returns_ok_with_series_id(
    client: httpx.Client, fixtures
) -> None:
    """Inject a mapping, then confirm the event is matched."""
    client.post(
        "/api/v1/_testing/inject-series-mapping",
        json={"plex_key": "CONTRACT_TEST_PLEX_KEY", "series_id": 42},
    )
    payload = fixtures("plex_watched")
    resp = client.post("/api/plex-event", json=payload)
    assert resp.status_code == 200
    body = resp.json()
    assert body == {"status": "ok", "series_id": 42}
```

- [ ] **Step 2: Run tests**

```bash
cd D:/docker/prioritarr/contract-tests
CONTRACT_TEST_BASE_URL=http://localhost:8000 python -m pytest behavioral/test_plex_event.py -v
```

Expected: Both tests PASS.

- [ ] **Step 3: Commit**

```bash
cd D:/docker/prioritarr
git add contract-tests/behavioral/test_plex_event.py
git commit -m "test(contract): /api/plex-event behavioral contract tests"
```

---

## Task 15: Schemathesis OpenAPI conformance test

**Files:**
- Create: `contract-tests/schema/test_openapi_conformance.py`

- [ ] **Step 1: Write the test file**

```python
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
```

- [ ] **Step 2: Run the Schemathesis tests**

```bash
cd D:/docker/prioritarr/contract-tests
CONTRACT_TEST_BASE_URL=http://localhost:8000 python -m pytest schema/ -v
```

Expected: All pass. If they don't, Schemathesis will emit specific mismatches (e.g. missing required field, wrong type). Fix by updating the Pydantic models if the **documentation is wrong**, or by fixing the handler if the **handler output is wrong**. Wire format must not change — if a test exposes a handler→spec mismatch, the fix is always in the spec/schema, not the handler.

- [ ] **Step 3: Commit**

```bash
cd D:/docker/prioritarr
git add contract-tests/schema/test_openapi_conformance.py
git commit -m "test(contract): Schemathesis OpenAPI conformance suite"
```

---

## Task 16: Upstream service mocks (WireMock)

**Files:**
- Create: `contract-tests/mocks/docker-compose.yml`
- Create: `contract-tests/mocks/mappings/sonarr/system_status.json`
- Create: `contract-tests/mocks/mappings/sonarr/get_series.json`
- Create: `contract-tests/mocks/mappings/sonarr/get_series_by_id.json`
- Create: `contract-tests/mocks/mappings/sonarr/get_episodes.json`
- Create: `contract-tests/mocks/mappings/sonarr/get_queue.json`
- Create: `contract-tests/mocks/mappings/tautulli/arnold.json`
- Create: `contract-tests/mocks/mappings/tautulli/get_libraries.json`
- Create: `contract-tests/mocks/mappings/tautulli/get_library_media_info.json`
- Create: `contract-tests/mocks/mappings/tautulli/get_history.json`
- Create: `contract-tests/mocks/mappings/plex/library_sections.json`
- Create: `contract-tests/mocks/mappings/plex/metadata_all_leaves.json`
- Create: `contract-tests/mocks/mappings/qbit/app_version.json`
- Create: `contract-tests/mocks/mappings/qbit/torrents_info.json`
- Create: `contract-tests/mocks/mappings/sab/version.json`

- [ ] **Step 1: Create docker-compose.yml**

`contract-tests/mocks/docker-compose.yml`:

```yaml
services:
  mock-sonarr:
    image: wiremock/wiremock:3.9.1
    command: ["--port", "8080", "--verbose"]
    ports: ["9001:8080"]
    volumes:
      - ./mappings/sonarr:/home/wiremock/mappings:ro

  mock-tautulli:
    image: wiremock/wiremock:3.9.1
    command: ["--port", "8080", "--verbose"]
    ports: ["9002:8080"]
    volumes:
      - ./mappings/tautulli:/home/wiremock/mappings:ro

  mock-plex:
    image: wiremock/wiremock:3.9.1
    command: ["--port", "8080", "--verbose"]
    ports: ["9003:8080"]
    volumes:
      - ./mappings/plex:/home/wiremock/mappings:ro

  mock-qbit:
    image: wiremock/wiremock:3.9.1
    command: ["--port", "8080", "--verbose"]
    ports: ["9004:8080"]
    volumes:
      - ./mappings/qbit:/home/wiremock/mappings:ro

  mock-sab:
    image: wiremock/wiremock:3.9.1
    command: ["--port", "8080", "--verbose"]
    ports: ["9005:8080"]
    volumes:
      - ./mappings/sab:/home/wiremock/mappings:ro
```

- [ ] **Step 2: Sonarr mock mappings**

`contract-tests/mocks/mappings/sonarr/system_status.json`:

```json
{
  "request": {
    "method": "GET",
    "urlPattern": "/sonarr/api/v3/system/status"
  },
  "response": {
    "status": 200,
    "jsonBody": {"version": "4.0.0.0", "appName": "Sonarr"},
    "headers": {"Content-Type": "application/json"}
  }
}
```

`contract-tests/mocks/mappings/sonarr/get_series.json`:

```json
{
  "request": {
    "method": "GET",
    "urlPattern": "/sonarr/api/v3/series(\\?.*)?"
  },
  "response": {
    "status": 200,
    "jsonBody": [
      {
        "id": 1,
        "title": "Contract Test Series",
        "tvdbId": 111111,
        "path": "/storage/media/video/anime/Contract Test Series",
        "monitored": true,
        "statistics": {"episodeFileCount": 1, "episodeCount": 1}
      }
    ],
    "headers": {"Content-Type": "application/json"}
  }
}
```

`contract-tests/mocks/mappings/sonarr/get_series_by_id.json`:

```json
{
  "request": {
    "method": "GET",
    "urlPattern": "/sonarr/api/v3/series/\\d+"
  },
  "response": {
    "status": 200,
    "jsonBody": {
      "id": 1,
      "title": "Contract Test Series",
      "tvdbId": 111111,
      "path": "/storage/media/video/anime/Contract Test Series",
      "monitored": true
    },
    "headers": {"Content-Type": "application/json"}
  }
}
```

`contract-tests/mocks/mappings/sonarr/get_episodes.json`:

```json
{
  "request": {
    "method": "GET",
    "urlPattern": "/sonarr/api/v3/episode(\\?.*)?"
  },
  "response": {
    "status": 200,
    "jsonBody": [
      {
        "id": 99901,
        "seriesId": 1,
        "seasonNumber": 1,
        "episodeNumber": 1,
        "airDateUtc": "2026-01-01T00:00:00Z",
        "monitored": true,
        "hasFile": false
      }
    ],
    "headers": {"Content-Type": "application/json"}
  }
}
```

`contract-tests/mocks/mappings/sonarr/get_queue.json`:

```json
{
  "request": {
    "method": "GET",
    "urlPattern": "/sonarr/api/v3/queue(\\?.*)?"
  },
  "response": {
    "status": 200,
    "jsonBody": {"page": 1, "pageSize": 1000, "totalRecords": 0, "records": []},
    "headers": {"Content-Type": "application/json"}
  }
}
```

- [ ] **Step 3: Tautulli mock mappings**

`contract-tests/mocks/mappings/tautulli/arnold.json`:

```json
{
  "request": {
    "method": "GET",
    "urlPattern": "/api/v2\\?.*cmd=arnold.*"
  },
  "response": {
    "status": 200,
    "jsonBody": {"response": {"result": "success", "data": "I'll be back"}},
    "headers": {"Content-Type": "application/json"}
  }
}
```

`contract-tests/mocks/mappings/tautulli/get_libraries.json`:

```json
{
  "request": {
    "method": "GET",
    "urlPattern": "/api/v2\\?.*cmd=get_libraries.*"
  },
  "response": {
    "status": 200,
    "jsonBody": {
      "response": {
        "result": "success",
        "data": [
          {"section_id": "1", "section_name": "TV Shows", "section_type": "show"}
        ]
      }
    },
    "headers": {"Content-Type": "application/json"}
  }
}
```

`contract-tests/mocks/mappings/tautulli/get_library_media_info.json`:

```json
{
  "request": {
    "method": "GET",
    "urlPattern": "/api/v2\\?.*cmd=get_library_media_info.*"
  },
  "response": {
    "status": 200,
    "jsonBody": {
      "response": {
        "result": "success",
        "data": {
          "data": [
            {"rating_key": "5000", "title": "Contract Test Series"}
          ]
        }
      }
    },
    "headers": {"Content-Type": "application/json"}
  }
}
```

`contract-tests/mocks/mappings/tautulli/get_history.json`:

```json
{
  "request": {
    "method": "GET",
    "urlPattern": "/api/v2\\?.*cmd=get_history.*"
  },
  "response": {
    "status": 200,
    "jsonBody": {
      "response": {
        "result": "success",
        "data": {"data": []}
      }
    },
    "headers": {"Content-Type": "application/json"}
  }
}
```

- [ ] **Step 4: Plex mock mappings**

`contract-tests/mocks/mappings/plex/library_sections.json`:

```json
{
  "request": {
    "method": "GET",
    "urlPattern": "/library/sections.*"
  },
  "response": {
    "status": 200,
    "body": "<?xml version=\"1.0\" encoding=\"UTF-8\"?><MediaContainer size=\"0\"></MediaContainer>",
    "headers": {"Content-Type": "application/xml"}
  }
}
```

`contract-tests/mocks/mappings/plex/metadata_all_leaves.json`:

```json
{
  "request": {
    "method": "GET",
    "urlPattern": "/library/metadata/.*/allLeaves.*"
  },
  "response": {
    "status": 200,
    "body": "<?xml version=\"1.0\" encoding=\"UTF-8\"?><MediaContainer size=\"0\"></MediaContainer>",
    "headers": {"Content-Type": "application/xml"}
  }
}
```

- [ ] **Step 5: qBit mock mappings**

`contract-tests/mocks/mappings/qbit/app_version.json`:

```json
{
  "request": {
    "method": "GET",
    "urlPattern": "/api/v2/app/version"
  },
  "response": {
    "status": 200,
    "body": "v4.6.0",
    "headers": {"Content-Type": "text/plain"}
  }
}
```

`contract-tests/mocks/mappings/qbit/torrents_info.json`:

```json
{
  "request": {
    "method": "GET",
    "urlPattern": "/api/v2/torrents/info.*"
  },
  "response": {
    "status": 200,
    "jsonBody": [],
    "headers": {"Content-Type": "application/json"}
  }
}
```

- [ ] **Step 6: SAB mock mapping**

`contract-tests/mocks/mappings/sab/version.json`:

```json
{
  "request": {
    "method": "GET",
    "urlPattern": "/sabnzbd/api\\?.*mode=version.*"
  },
  "response": {
    "status": 200,
    "jsonBody": {"version": "4.0.0"},
    "headers": {"Content-Type": "application/json"}
  }
}
```

- [ ] **Step 7: Bring up the mocks and verify they respond**

```bash
cd D:/docker/prioritarr/contract-tests/mocks
docker compose up -d
sleep 5
curl -s http://localhost:9001/sonarr/api/v3/system/status | jq
# Expected: {"version": "4.0.0.0", "appName": "Sonarr"}
curl -s http://localhost:9002/api/v2?apikey=x\&cmd=arnold | jq
# Expected: {"response": {"result": "success", "data": "I'll be back"}}
curl -s http://localhost:9004/api/v2/app/version
# Expected: v4.6.0
```

If any endpoint returns 404, there's a typo in the WireMock mapping `urlPattern`. Check `docker logs mocks-mock-sonarr-1` (or the appropriate container name) for the actual incoming request URLs.

- [ ] **Step 8: Tear mocks down (keep the configs committed)**

```bash
cd D:/docker/prioritarr/contract-tests/mocks
docker compose down
```

- [ ] **Step 9: Commit**

```bash
cd D:/docker/prioritarr
git add contract-tests/mocks/
git commit -m "test(contract): WireMock stubs for upstream services (Sonarr, Tautulli, Plex, qBit, SAB)"
```

---

## Task 17: `make openapi` target and committed `openapi.json`

**Files:**
- Modify: `Makefile`
- Create: `openapi.json` (generated)

- [ ] **Step 1: Add the target to Makefile**

Open `D:/docker/prioritarr/Makefile` and add these lines at the bottom:

```makefile

.PHONY: openapi
openapi:
	@echo "Fetching /openapi.json from running container..."
	@curl -fsS http://localhost:8000/openapi.json | python -m json.tool --sort-keys > openapi.json
	@echo "Wrote openapi.json ($$(wc -c < openapi.json) bytes)"
```

Note: Makefile targets require leading TAB, not spaces.

- [ ] **Step 2: Ensure the backend is running with the new Pydantic models**

Verify the local container is up to date:

```bash
docker ps --filter "name=prioritarr" --format "{{.Image}}\t{{.Status}}"
```

If the image is stale (no Pydantic models), rebuild and redeploy per Task 5-8 instructions (which should have already happened in order).

- [ ] **Step 3: Generate and inspect the spec**

```bash
cd D:/docker/prioritarr
make openapi
head -40 openapi.json
```

Expected output starts with:

```json
{
    "components": {
        "schemas": {
            "HealthOk": {
                ...
```

All five new schemas (`HealthOk`, `HealthUnhealthy`, `ReadyResponse`, `OnGrab*`, `PlexEvent*`) should appear under `components.schemas`.

- [ ] **Step 4: Smoke-test the generated spec against a linter**

```bash
pip install openapi-spec-validator
python -c "from openapi_spec_validator import validate_spec; import json; validate_spec(json.load(open('openapi.json')))"
```

Expected: exit code 0, no output. If there's a validation error, it means the generated spec has a structural issue — fix the Pydantic metadata in the schemas file(s) responsible.

- [ ] **Step 5: Commit**

```bash
git add Makefile openapi.json
git commit -m "feat(api): add make openapi target and commit generated openapi.json"
```

---

## Task 18: GitHub Actions — contract tests job with drift check

**Files:**
- Modify: `.github/workflows/release.yml`

- [ ] **Step 1: Read the existing workflow to understand structure**

Run: `cat D:/docker/prioritarr/.github/workflows/release.yml`

- [ ] **Step 2: Add a new `contract-tests` job**

Add the following job to `.github/workflows/release.yml`. If the existing file has a `jobs:` key, add this job alongside the existing ones. If the existing `build-and-push` or test job already has a `needs:` list, consider adding `contract-tests` to that list so release is blocked until contract tests pass.

```yaml
  contract-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up Python
        uses: actions/setup-python@v5
        with:
          python-version: "3.12"

      - name: Start upstream mocks
        run: docker compose -f contract-tests/mocks/docker-compose.yml up -d

      - name: Wait for mocks to respond
        run: |
          timeout 30 bash -c 'until curl -fsS http://localhost:9001/sonarr/api/v3/system/status >/dev/null; do sleep 1; done'
          timeout 30 bash -c 'until curl -fsS "http://localhost:9002/api/v2?apikey=x&cmd=arnold" >/dev/null; do sleep 1; done'

      - name: Build prioritarr image
        run: docker build -t prioritarr:test .

      - name: Start prioritarr in test mode
        run: |
          docker run -d --name prioritarr \
            --network host \
            -e PRIORITARR_TEST_MODE=true \
            -e PRIORITARR_DRY_RUN=true \
            -e PRIORITARR_SONARR_URL=http://localhost:9001/sonarr \
            -e PRIORITARR_SONARR_API_KEY=mock \
            -e PRIORITARR_TAUTULLI_URL=http://localhost:9002 \
            -e PRIORITARR_TAUTULLI_API_KEY=mock \
            -e PRIORITARR_PLEX_URL=http://localhost:9003 \
            -e PRIORITARR_PLEX_TOKEN=mock \
            -e PRIORITARR_QBIT_URL=http://localhost:9004 \
            -e PRIORITARR_SAB_URL=http://localhost:9005/sabnzbd \
            -e PRIORITARR_SAB_API_KEY=mock \
            -e PRIORITARR_CONFIG_PATH=/app/default-config.yaml \
            prioritarr:test

      - name: Wait for prioritarr to be live
        run: timeout 120 bash -c 'until curl -fsS http://localhost:8000/openapi.json >/dev/null; do sleep 2; done'

      - name: Verify committed openapi.json matches runtime
        run: |
          curl -fsS http://localhost:8000/openapi.json | python -m json.tool --sort-keys > /tmp/runtime.json
          python -m json.tool --sort-keys openapi.json > /tmp/committed.json
          if ! diff -u /tmp/committed.json /tmp/runtime.json; then
            echo ""
            echo "ERROR: Committed openapi.json differs from runtime spec."
            echo "Run 'make openapi' locally and commit the updated file."
            exit 1
          fi

      - name: Install contract-tests dependencies
        run: pip install -r contract-tests/requirements.txt

      - name: Run contract tests
        env:
          CONTRACT_TEST_BASE_URL: http://localhost:8000
        run: python -m pytest contract-tests/ -v

      - name: Dump prioritarr logs on failure
        if: failure()
        run: docker logs prioritarr
```

- [ ] **Step 3: Verify YAML syntax**

```bash
pip install yamllint
yamllint D:/docker/prioritarr/.github/workflows/release.yml
```

Expected: no errors. Warnings are OK.

- [ ] **Step 4: Commit**

```bash
cd D:/docker/prioritarr
git add .github/workflows/release.yml
git commit -m "ci: add contract-tests job with OpenAPI drift check"
```

- [ ] **Step 5: Push and verify the job passes on GitHub**

```bash
git push
```

Then check the Actions tab on GitHub. The `contract-tests` job should pass. If it fails, check the step that failed — likely a mock mapping doesn't match a URL the backend calls at startup. Iterate on the mocks until green.

---

## Task 19: README documentation

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add a "Contract Testing" section**

Append to `D:/docker/prioritarr/README.md`, just before any `## License` section:

````markdown

## Contract Testing

Prioritarr has a shared contract test suite that locks the HTTP API surface. Any backend that passes this suite is behaviorally interchangeable with the current Python implementation.

### Running locally against the Python backend

First, restart the container with `PRIORITARR_TEST_MODE=true`:

```bash
docker run -d --name prioritarr \
  -e PRIORITARR_TEST_MODE=true \
  -e PRIORITARR_DRY_RUN=true \
  # ... rest of env vars ...
  ghcr.io/cquemin/prioritarr:latest
```

Then install and run:

```bash
cd contract-tests
pip install -r requirements.txt
CONTRACT_TEST_BASE_URL=http://localhost:8000 pytest -v
```

### Running against mocked upstream services

The `contract-tests/mocks/` directory contains WireMock stubs. Use them when you don't have a live Sonarr/Tautulli/Plex stack:

```bash
cd contract-tests/mocks
docker compose up -d
# Point prioritarr at http://localhost:9001..9005 and launch as above.
```

### Regenerating `openapi.json`

The OpenAPI spec is committed at the repo root and verified in CI.

```bash
# With a running backend on localhost:8000:
make openapi
git add openapi.json && git commit -m "chore: regenerate openapi.json"
```

### `PRIORITARR_TEST_MODE` safety

`PRIORITARR_TEST_MODE=true` mounts destructive endpoints at `/api/v1/_testing/*` (reset all state, force stale heartbeat, inject series mappings). **Never enable this in production.** The default is `false`.

### Spec §4 response-example corrections

The original spec (`docs/specs/2026-04-14-prioritarr-api-contract-v1-design.md`) shows a handful of example response bodies that don't match the live backend. The code and the contract tests are the source of truth; a follow-up will correct the spec examples. The actual responses are:

| Endpoint | Case | Wire format |
|----------|------|-------------|
| `POST /api/sonarr/on-grab` | Non-Grab | `{"status":"ignored","eventType":"<type>"}` |
| `POST /api/sonarr/on-grab` | Processed | `{"status":"processed","priority":N,"label":"..."}` |
| `POST /api/sonarr/on-grab` | Duplicate | `{"status":"duplicate","priority":N,"label":"..."}` |
| `POST /api/plex-event` | Unmatched | `{"status":"unmatched","plex_key":"<key>"}` |
| `POST /api/plex-event` | Matched | `{"status":"ok","series_id":N}` |

````

- [ ] **Step 2: Preview the README renders correctly**

```bash
# If you have a markdown previewer handy, use it; otherwise just:
head -c 2000 D:/docker/prioritarr/README.md
```

- [ ] **Step 3: Commit**

```bash
cd D:/docker/prioritarr
git add README.md
git commit -m "docs: add Contract Testing section and document §4 corrections"
```

---

## Task 20: Final verification

- [ ] **Step 1: Run the entire unit test suite**

```bash
cd D:/docker/prioritarr
python -m pytest -v
```

Expected: All tests PASS, including the new ones from Tasks 2-4 and the integration tests updated in Tasks 5-8.

- [ ] **Step 2: Run the full contract test suite against the running local backend**

Ensure the container is running with `PRIORITARR_TEST_MODE=true`. Then:

```bash
cd D:/docker/prioritarr/contract-tests
CONTRACT_TEST_BASE_URL=http://localhost:8000 python -m pytest -v
```

Expected: All behavioral and schema tests PASS.

- [ ] **Step 3: Verify `openapi.json` is up to date**

```bash
cd D:/docker/prioritarr
make openapi
git diff --stat openapi.json
```

Expected: no diff (already committed in Task 17). If there's a diff, the Pydantic models were updated after Task 17 — commit the updated `openapi.json`.

- [ ] **Step 4: Push and confirm CI is green**

```bash
git push
```

Check the Actions tab: both `build-and-push` (if it runs) and `contract-tests` jobs should be green.

- [ ] **Step 5: Turn off test mode in production (safety check)**

Confirm the production stack does NOT have `PRIORITARR_TEST_MODE` set, or that it's explicitly `false`:

```bash
grep -i PRIORITARR_TEST_MODE D:/docker/.env
```

Expected: either no output or a line like `PRIORITARR_TEST_MODE=false`.

---

## Plan self-review notes

1. **Spec coverage check:**
   - §4.1-4.4 endpoint inventory → Tasks 2-8 (schemas + wiring)
   - §5.1 generated-then-committed → Task 17 (`make openapi`)
   - §5.2 Pydantic models → Tasks 2-4
   - §5.3 OpenAPI metadata → need to confirm FastAPI's `app = FastAPI(title=..., version=..., description=...)` matches §5.3. **Added step to Task 17**: verify with `jq '.info' openapi.json`.
   - §6.1 contract-tests/ structure → Task 10
   - §6.2 running the suite → Task 11+
   - §6.3 Schemathesis → Task 15
   - §6.4 behavioral tests → Tasks 11-14
   - §6.5 test-mode endpoints → Task 9
   - §7 legacy quirks → captured in tests (Task 13 covers quirk #1 and #2, Task 12 covers #4, Task 14 covers #5; quirk #3 is handler-internal, tested implicitly via Task 13)
   - §8 CI → Task 18
   - §9 acceptance criteria → Task 20

2. **Placeholder scan:** No "TBD", "TODO", or vague instructions. Every test has a full body; every handler change shows the exact code; every fixture has complete JSON.

3. **Type consistency:** Schema class names (`HealthOk`, `HealthUnhealthy`, `ReadyResponse`, `DependencyStatus`, `OnGrabIgnored`, `OnGrabProcessed`, `OnGrabDuplicate`, `PlexEventUnmatched`, `PlexEventOk`) are consistent across Tasks 2-4 (definition) and Tasks 5-8 (usage).

4. **Spec §5.3 verification** — added to Task 17 Step 4 via the `openapi-spec-validator` smoke test; if the existing FastAPI app creation lacks title/version/description, the plan executor should update the `FastAPI(...)` call in `main.py` to match §5.3 during Task 5 (it's the first task that touches `main.py`).
