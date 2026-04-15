# Prioritarr — API Contract v1 (Spec A)

**Date:** 2026-04-14
**Status:** Approved
**Project:** Prioritarr
**Backend version at time of writing:** v0.1.0 (Python/FastAPI)

---

## 1. Purpose & Context

This is the first of four specs that together migrate prioritarr from a headless Python service to a full Kotlin-backed product with a React UI:

| Spec | Scope |
|------|-------|
| **A (this doc)** | Document the current Python API surface as OpenAPI 3.1 + shared contract test suite |
| **B** | Port the Python backend to Kotlin, 1:1 behavioral match. Must pass Spec A contract tests. |
| **C** | Add new endpoints (dashboard, queue, series, activity, backfill, matching, settings, status, SSE, API key auth) to the Kotlin backend. OpenAPI spec grows. |
| **D** | Build the React UI against Spec C's API. Uses autobrr frontend stack as base. |

**Spec A is intentionally small.** It produces two artifacts — an OpenAPI 3.1 document and a contract test suite — that together lock the behavior of the current backend so Spec B has a concrete target and transparent swap is achievable.

## 2. Goals

1. Formally describe the current HTTP surface of prioritarr as OpenAPI 3.1
2. Build a shared contract test suite that runs against a live backend (Python today, Kotlin tomorrow)
3. Commit the OpenAPI spec to the repo so it can be diffed against FastAPI's auto-generated spec in CI — preventing drift between code and documentation
4. Document any existing inconsistencies explicitly as "legacy quirks" to preserve in the port

## 3. Non-Goals

- Adding new endpoints (deferred to Spec C)
- Changing the **wire format** of any response (Spec C only — current JSON bodies preserved byte-compatible)
- Introducing authentication on the existing endpoints (deferred to Spec C)
- Pagination, SSE, RFC 7807 errors (deferred to Spec C — new endpoints only)
- TypeScript client generation (deferred to Spec D — done during UI work)
- Changing any externally observable behavior whatsoever

**Note on internal changes allowed by Spec A:** introducing Pydantic response models (§5.2) is an internal refactor — the `model_dump()` output of the new models must produce exactly the JSON bytes the current handlers return. The OpenAPI spec becomes typed, but `curl` output stays identical.

## 4. Current Endpoint Inventory

Four endpoints exist today. Documenting each:

### 4.1 `GET /health`

**Purpose:** Liveness probe for Docker HEALTHCHECK + autoheal.

**Request:** no parameters, no body.

**Responses:**
- `200 OK` — `{"status": "ok"}` when the scheduler heartbeat is fresh and state.db is reachable
- `503 Service Unavailable` — `{"status": "unhealthy", "reason": "<text>"}` when the heartbeat is stale, state.db is unreachable, or the scheduler is dead

**Quirks to preserve:** The `reason` field is human-readable free text (e.g., `"heartbeat_stale: 342s > 300s"`, `"no_heartbeat"`, `"database: <exception text>"`). The UI never parses it.

### 4.2 `GET /ready`

**Purpose:** Readiness check — dependency status breakdown.

**Request:** no parameters, no body.

**Responses:**
- `200 OK` — all dependencies reachable
- `503 Service Unavailable` — at least one dependency unreachable

**Body (both status codes):**
```json
{
  "status": "ok" | "degraded",
  "dependencies": {
    "sonarr": "ok" | "unreachable",
    "tautulli": "ok" | "unreachable",
    "qbit": "ok" | "unreachable",
    "sab": "ok" | "unreachable"
  },
  "last_heartbeat": "2026-04-14T22:30:00.000000+00:00"
}
```

**Quirks to preserve:**
- Dependency status values are literals `"ok"` or `"unreachable"` (no extra values today)
- `last_heartbeat` may be `null` if the scheduler hasn't run yet
- The probe uses lightweight checks (`/api/v3/system/status`, `arnold`, `/api/v2/app/version`, `mode=version`), not full data queries

### 4.3 `POST /api/sonarr/on-grab`

**Purpose:** Sonarr OnGrab webhook receiver.

**Request body (Sonarr-defined shape):** Sonarr webhook JSON. Relevant fields:
- `eventType` (string): expected `"Grab"`. Non-Grab events are accepted and ignored.
- `series.id`, `series.title`, `series.tvdbId`
- `episodes[].id`, `episodes[].episodeNumber`, `episodes[].seasonNumber`, `episodes[].airDateUtc`
- `release.releaseTitle`
- `downloadClient` (string, case varies — `"QBittorrent"` / `"SABnzbd"` / `"qBittorrent"` seen in practice)
- `downloadId` (string, uppercase hex for qBit torrents; `"SABnzbd_nzo_..."` for SAB)

Any additional fields Sonarr sends are ignored.

**Responses:**
- `200 OK` — always. Prioritarr never fails a webhook.

**Body cases:**
```json
{"status": "ignored", "reason": "not_grab_event"}        // eventType != "Grab"
{"status": "ok", "priority": N, "label": "P..."}          // processed successfully
{"status": "deduplicated"}                                // seen the same event_key before
```

Where `priority` is `1..5` (integer) and `label` follows the pattern `"P<N> <description>"`.

**Quirks to preserve:**
- Always 200. Prioritarr must never return 4xx/5xx to Sonarr webhook retries.
- The `event_key` is `sha1(eventType + series_id + episode_ids + download_id)`; duplicates within the dedupe window (24h) return `"deduplicated"`.
- In dry-run mode, the response body is identical — dry-run is NOT observable from the response. It shows up only in logs.

### 4.4 `POST /api/plex-event`

**Purpose:** Tautulli Watched notification receiver.

**Request body (Tautulli-defined shape, customized in our webhook config):**
```json
{
  "event": "watched",
  "user": "<username>",
  "grandparent_title": "<show title>",
  "grandparent_rating_key": "<plex key>",
  "parent_media_index": "<season number as string>",
  "media_index": "<episode number as string>",
  "rating_key": "<plex episode key>"
}
```

**Responses:**
- `200 OK` — always.

**Body cases:**
```json
{"status": "ignored", "reason": "show_not_in_sonarr"}    // unmapped plex_show_key
{"status": "ok", "series_id": N}                          // cache invalidated for series_id
```

**Quirks to preserve:**
- Same always-200 rule. Tautulli retries; we don't want to encourage that.
- If the plex_show_key isn't in `_plex_key_to_series_id`, prioritarr triggers a mapping refresh first. If still unmapped after refresh, returns `"ignored"`.

## 5. OpenAPI 3.1 Document

### 5.1 Approach

**Generated-then-committed.** FastAPI produces `/openapi.json` from the Pydantic models and route decorators. We:

1. Enhance Pydantic models in `prioritarr/schemas/` (new directory) with `description=`, `example=`, and `title=` metadata so the auto-generated spec is documentation-grade
2. A `make openapi` target fetches `/openapi.json` from a running container and writes `openapi.json` (pretty-printed, stable key ordering) at the repo root. That's the committed source of truth.
3. A CI job:
   - Builds and starts the container with `PRIORITARR_TEST_MODE=true` and mock upstream services
   - Fetches `/openapi.json` from the running container
   - Diffs (semantically — via `deepdiff` to ignore cosmetic differences) against the committed `openapi.json`
   - Fails the build if they diverge — forces the developer to run `make openapi` and commit the update

We commit JSON, not YAML — it's what FastAPI emits natively, it diffs cleanly with `jq -S`, and no lossy conversion step is needed.

### 5.2 Pydantic models required

The current code returns dicts, not Pydantic models, for the webhook responses. To produce a clean OpenAPI spec, we introduce response models — without changing the wire format:

```python
# prioritarr/schemas/health.py
class HealthOk(BaseModel):
    status: Literal["ok"] = "ok"

class HealthUnhealthy(BaseModel):
    status: Literal["unhealthy"]
    reason: str

# prioritarr/schemas/ready.py
class DependencyStatus(str, Enum):
    ok = "ok"
    unreachable = "unreachable"

class ReadyResponse(BaseModel):
    status: Literal["ok", "degraded"]
    dependencies: dict[Literal["sonarr", "tautulli", "qbit", "sab"], DependencyStatus]
    last_heartbeat: datetime | None

# prioritarr/schemas/webhooks.py
class OnGrabIgnored(BaseModel):
    status: Literal["ignored"]
    reason: str

class OnGrabOk(BaseModel):
    status: Literal["ok"]
    priority: int = Field(ge=1, le=5)
    label: str

class OnGrabDeduplicated(BaseModel):
    status: Literal["deduplicated"]

class PlexEventIgnored(BaseModel):
    status: Literal["ignored"]
    reason: str

class PlexEventOk(BaseModel):
    status: Literal["ok"]
    series_id: int
```

FastAPI's `response_model=Union[HealthOk, HealthUnhealthy]` + `responses={200: ..., 503: ...}` produces the right `oneOf` in OpenAPI.

### 5.3 OpenAPI metadata

```yaml
openapi: 3.1.0
info:
  title: Prioritarr API
  description: Priority-aware download queue orchestrator. See https://github.com/cquemin/prioritarr
  version: 0.1.0            # matches pyproject.toml
  license:
    name: MIT
servers:
  - url: http://prioritarr:8000
    description: Internal service URL (Docker network)
```

No `security` section in Spec A — the endpoints are unauthenticated today.

## 6. Contract Test Suite

### 6.1 Location & structure

```
prioritarr/
├── contract-tests/            # NEW
│   ├── conftest.py            # base_url fixture from env var
│   ├── pytest.ini
│   ├── requirements.txt       # pytest, httpx, schemathesis, jsonschema
│   ├── schema/                # schema-level via Schemathesis
│   │   └── test_openapi_conformance.py
│   ├── behavioral/            # handwritten scenario tests
│   │   ├── test_health.py
│   │   ├── test_ready.py
│   │   ├── test_sonarr_ongrab.py
│   │   └── test_plex_event.py
│   └── fixtures/
│       ├── sonarr_ongrab.json
│       ├── sonarr_ongrab_non_grab.json
│       └── plex_watched.json
```

### 6.2 Running the suite

```bash
# Against local Python backend
CONTRACT_TEST_BASE_URL=http://localhost:8000 pytest contract-tests/

# Against deployed Python backend
CONTRACT_TEST_BASE_URL=http://prioritarr:8000 pytest contract-tests/

# Against future Kotlin backend — same command
CONTRACT_TEST_BASE_URL=http://prioritarr-kotlin:8000 pytest contract-tests/
```

The suite is backend-agnostic. It only speaks HTTP.

### 6.3 Schema conformance tests (Schemathesis)

One test file, auto-generates hundreds of test cases from the OpenAPI spec:

```python
# contract-tests/schema/test_openapi_conformance.py
import schemathesis
import os

schema = schemathesis.from_uri(f"{os.environ['CONTRACT_TEST_BASE_URL']}/openapi.json")

@schema.parametrize()
def test_openapi_conformance(case):
    """Auto-test: every endpoint, every response schema, every status code."""
    response = case.call()
    case.validate_response(response)
```

This hits every declared endpoint with hypothesis-generated inputs, verifies:
- Status codes match what's declared
- Response bodies conform to the declared schema
- Required fields are present
- Types match

### 6.4 Behavioral tests (handwritten)

These encode the *semantic* contract — what the Kotlin port must match beyond just shape.

**`test_health.py`:**
- Given a fresh container, `GET /health` returns 200 `{"status": "ok"}` within 90s of startup
- Stop the scheduler thread (how? see seed endpoint), wait 10 min, `GET /health` returns 503 with `"reason"` containing `"heartbeat"`

**`test_ready.py`:**
- `GET /ready` returns 200 when all deps are reachable, 503 otherwise
- Response always contains all four `dependencies` keys
- `last_heartbeat` is ISO 8601 or null

**`test_sonarr_ongrab.py`:**
- POST a valid Grab payload — response is `{"status": "ok", "priority": N, "label": "P..."}` with `1 <= priority <= 5`
- POST same payload twice — second response is `{"status": "deduplicated"}`
- POST with `"eventType": "Test"` — response is `{"status": "ignored", "reason": "not_grab_event"}`
- POST with missing `series.id` — still returns 200 (fault-tolerant)

**`test_plex_event.py`:**
- POST a valid watched payload for a known series — response is `{"status": "ok", "series_id": N}`
- POST with an unknown `grandparent_rating_key` — response is `{"status": "ignored", "reason": "show_not_in_sonarr"}` (after mapping refresh attempt)

### 6.5 Test isolation — the seed endpoint

Problem: contract tests need to control state. How do we make the container return 503 from `/health` on demand? How do we guarantee dedupe on the second OnGrab?

**Solution:** gated test-only endpoints. `POST /api/v1/_testing/...` — only mounted when `PRIORITARR_TEST_MODE=true` env var is set.

| Endpoint | Purpose |
|----------|---------|
| `POST /api/v1/_testing/reset` | Clear state.db (managed_downloads, webhook_dedupe, audit_log, heartbeat) |
| `POST /api/v1/_testing/stale-heartbeat` | Backdate heartbeat to force `/health` → 503 |
| `POST /api/v1/_testing/inject-series-mapping` | Manually inject `plex_key → series_id` into the in-memory map |

The contract test suite calls `_testing/reset` at the start of each test. Kotlin port must implement the same test endpoints under the same flag for the tests to be portable.

**Security:** `PRIORITARR_TEST_MODE=false` is the production default. CI sets it to `true`. Production deployments never enable it.

## 7. Legacy quirks to preserve

Things that are weird about the current API that the Kotlin port must reproduce exactly:

1. **Always-200 webhooks** — `/api/sonarr/on-grab` and `/api/plex-event` never return error codes, even on malformed payloads. They log and return 200.
2. **Dry-run is invisible in responses** — OnGrab returns `{"status": "ok", "priority": N}` whether or not dry-run is enabled. Dry-run is a logging/behavior toggle, not a response field.
3. **Case-insensitive `downloadId` matching** — incoming Sonarr payloads have uppercase hex hashes for qBit. The port must lowercase before comparison.
4. **`last_heartbeat` timestamp format** — ISO 8601 with microseconds and explicit `+00:00` offset, not `Z`. Example: `"2026-04-14T22:30:00.123456+00:00"`. Python's `datetime.isoformat()` produces this; Kotlin must match format exactly to avoid tooling confusion.
5. **Plex event refresh-on-miss** — if a `grandparent_rating_key` isn't mapped, the webhook handler triggers a mapping refresh (synchronously, within the request) before deciding `ignored`. This can make a single webhook call take >30s. Preserve or explicitly change in Spec C.

## 8. CI integration

Add to `.github/workflows/release.yml` (runs on every push):

```yaml
contract-tests:
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v4

    - name: Start mock upstream services
      run: docker compose -f contract-tests/mocks/docker-compose.yml up -d
      # Mocks: WireMock instances for Sonarr, Tautulli, Plex; stub qBit + SAB.
      # Mock configs live in contract-tests/mocks/{sonarr,tautulli,plex}/mappings/.

    - name: Build and start prioritarr
      run: |
        docker build -t prioritarr:test .
        docker run -d --name prioritarr \
          --network host \
          -e PRIORITARR_TEST_MODE=true \
          -e PRIORITARR_SONARR_URL=http://localhost:9001 \
          -e PRIORITARR_SONARR_API_KEY=mock \
          -e PRIORITARR_TAUTULLI_URL=http://localhost:9002 \
          -e PRIORITARR_TAUTULLI_API_KEY=mock \
          -e PRIORITARR_PLEX_URL=http://localhost:9003 \
          -e PRIORITARR_PLEX_TOKEN=mock \
          -e PRIORITARR_QBIT_URL=http://localhost:9004 \
          -e PRIORITARR_SAB_URL=http://localhost:9005 \
          -e PRIORITARR_SAB_API_KEY=mock \
          -e PRIORITARR_DRY_RUN=true \
          -e PRIORITARR_CONFIG_PATH=/config/prioritarr.yaml \
          -v "$PWD/default-config.yaml:/config/prioritarr.yaml" \
          prioritarr:test

    - name: Wait for healthy
      run: timeout 120 bash -c 'until curl -sf http://localhost:8000/health; do sleep 2; done'

    - name: Verify committed OpenAPI matches runtime
      run: |
        curl -s http://localhost:8000/openapi.json | jq -S . > /tmp/current.json
        jq -S . openapi.json > /tmp/committed.json
        diff -u /tmp/committed.json /tmp/current.json

    - name: Run contract tests
      run: |
        pip install -r contract-tests/requirements.txt
        CONTRACT_TEST_BASE_URL=http://localhost:8000 pytest contract-tests/ -v
```

## 9. Acceptance criteria

Spec A is complete when:

1. `openapi.yaml` (or `.json`) is committed at the repo root and describes all 4 endpoints with their full request/response schemas
2. The committed spec matches FastAPI's auto-generated `/openapi.json` (CI-enforced)
3. `contract-tests/` directory exists with Schemathesis + behavioral tests
4. Contract tests pass 100% against the running Python container
5. Mock servers or WireMock configs exist for the external dependencies so CI can run without real Sonarr/Tautulli/qBit/SAB
6. README is updated with a "Contract Testing" section explaining how to run the suite

## 10. What happens next

After Spec A lands and the contract tests pass against Python, **Spec B** begins: Kotlin port. The Kotlin backend serves the same HTTP port (`8000` internally, mapped however compose chooses externally) so the only change to `docker-compose.yml` for the swap is the `image:` line. The contract test command stays literally identical:

```bash
CONTRACT_TEST_BASE_URL=http://localhost:8000 pytest contract-tests/
```

Spec B is complete when that command passes 100% against a running Kotlin container. At that point the Kotlin image replaces the Python image in production with zero observable behavior change.
