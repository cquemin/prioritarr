# Expand Backend v2 (Spec C) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Layer the v2 read + control surface on top of the Kotlin backend without disturbing the v1 webhook contract. Auth, RFC 7807 errors, pagination, SSE, and ~15 new endpoints. The existing 23-test contract suite still passes unchanged after every task.

**Spec:** `docs/specs/2026-04-18-prioritarr-expand-backend-design.md`

**Tech:** Same stack as Spec B (Kotlin 2.0.21, Ktor 2.3.12, kotlinx.serialization, SQLDelight, coroutines). No new build dependencies beyond Ktor's `ktor-server-sse` plugin and `ktor-server-cors` plugin.

---

## Critical pre-flight

1. Spec B is shipped; Kotlin shadow has soaked for ≥24h without diffs; the 23 contract tests are green on the `prioritarr-app` image on `main`.
2. This plan lives on `feat/expand-backend`. Do NOT rebase onto `main` mid-plan unless you explicitly need a fix from there — preserves bisectability.
3. Every task MUST keep the existing 23 contract tests green. Run them locally after each commit.

---

## File structure additions

All paths relative to `D:/docker/prioritarr/`:

```
prioritarr-app/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/
├── auth/
│   └── ApiKeyAuth.kt
├── errors/
│   └── ProblemDetails.kt
├── pagination/
│   └── Pagination.kt
├── events/
│   ├── EventBus.kt
│   └── RingBuffer.kt
├── api/v2/
│   ├── SeriesRoutes.kt
│   ├── DownloadsRoutes.kt
│   ├── AuditRoutes.kt
│   ├── SettingsRoutes.kt
│   ├── MappingsRoutes.kt
│   ├── ActionsRoutes.kt
│   └── EventsRoute.kt
└── schemas/
    ├── V2Series.kt
    ├── V2Downloads.kt
    ├── V2Audit.kt
    ├── V2Settings.kt
    ├── V2Mappings.kt
    └── V2Events.kt

contract-tests/
├── behavioral/
│   ├── test_v2_auth.py
│   ├── test_v2_errors.py
│   ├── test_v2_series.py
│   ├── test_v2_downloads.py
│   ├── test_v2_audit.py
│   ├── test_v2_settings.py
│   ├── test_v2_mappings.py
│   ├── test_v2_actions.py
│   └── test_v2_events.py
└── conftest.py                # MODIFY: inject X-Api-Key, expose sse helper
```

---

## Phase 1 — Cross-cutting foundations

These land first. Every later endpoint relies on them, and they have no dependencies on v2 endpoints, so they're independently testable.

## Task 1: Auth — X-Api-Key + Authorization: Bearer

**Files:**
- Create: `backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/auth/ApiKeyAuth.kt`
- Modify: `config/Settings.kt` (add `apiKey: String?` field)
- Modify: `app/Module.kt` (install auth plugin, gate `/api/v2/*`)
- Create: `backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/auth/ApiKeyAuthTest.kt`

**Steps:**
- [ ] Add `apiKey: String?` to Settings, loaded from `PRIORITARR_API_KEY`.
- [ ] TDD: write a Ktor `testApplication { }` unit test asserting 401 when `PRIORITARR_API_KEY=secret` and the request omits the header; 200 when `X-Api-Key: secret`; 200 when `Authorization: Bearer secret`; 401 when `X-Api-Key: wrong`.
- [ ] Implement a Ktor plugin `ApiKeyAuth` that inspects `X-Api-Key` then `Authorization: Bearer`, and short-circuits with 401 RFC 7807 when missing/wrong.
- [ ] Mount the plugin only on a `route("/api/v2") { authenticate(...) { ... } }` wrapper. v1 webhook paths must NOT go through auth.
- [ ] If `apiKey` is null at startup, log a `WARN` and leave v2 routes unauthenticated (dev mode).
- [ ] Commit: `feat(kotlin/auth): X-Api-Key + Bearer auth on /api/v2/*`

---

## Task 2: RFC 7807 Problem Details

**Files:**
- Create: `errors/ProblemDetails.kt`
- Modify: `app/Module.kt` (extend StatusPages — still keep the webhook always-200 handler)
- Create: `backend/src/test/kotlin/.../errors/ProblemDetailsTest.kt`

**Steps:**
- [ ] `@Serializable data class ProblemDetail(type, title, status, detail, instance?)`. Ensure `application/problem+json` content-type.
- [ ] Define a sealed hierarchy of typed exceptions: `UnauthorizedException`, `NotFoundException`, `ValidationException(field, message)`, `UpstreamUnreachableException`. Each carries the slug + HTTP status.
- [ ] TDD: write unit tests that `ValidationException("limit", "max 1000")` → HTTP 422 with body `{"type":"/errors/validation","title":"Validation","status":422,"detail":"limit: max 1000"}`.
- [ ] Extend StatusPages with path-aware routing: `/api/v2/*` exceptions → Problem; `/api/sonarr/on-grab` + `/api/plex-event` keep the existing always-200 shield untouched; everything else gets a generic 500 Problem.
- [ ] Commit: `feat(kotlin/errors): RFC 7807 problem details for /api/v2/*`

---

## Task 3: Pagination envelope + parser

**Files:**
- Create: `pagination/Pagination.kt`
- Create: `schemas/V2Common.kt` (the `PaginatedEnvelope<T>` data class)
- Create: `backend/src/test/kotlin/.../pagination/PaginationTest.kt`

**Steps:**
- [ ] `data class PageParams(offset: Int, limit: Int, sort: String?, sortDir: SortDir)` with `fun fromCall(call: ApplicationCall, allowedSorts: Set<String>, defaultSort: String): PageParams`.
- [ ] Validation: `limit in 1..1000`; `offset >= 0`; `sort in allowedSorts`; `sortDir in (asc, desc)`. Violation → `ValidationException`.
- [ ] `@Serializable data class PaginatedEnvelope<T>(records, totalRecords, offset, limit)` — generic, parameterised.
- [ ] TDD: table-driven test over `(offset, limit, sort, sortDir) → expected parse result or exception`.
- [ ] Commit: `feat(kotlin/pagination): offset/limit parser + arr-style envelope`

---

## Task 4: CORS

**Files:**
- Modify: `backend/build.gradle.kts` (add `ktor-server-cors-jvm`)
- Modify: `config/Settings.kt` (add `uiOrigin: String?` from `PRIORITARR_UI_ORIGIN`)
- Modify: `app/Module.kt` (install CORS plugin)

**Steps:**
- [ ] Install CORS with: hosts = `localhost`, `PRIORITARR_UI_ORIGIN` (if set); allowed headers `Content-Type, X-Api-Key, Authorization, Last-Event-ID`; allowed methods `Get, Post, Delete, Options`.
- [ ] TDD: a `testApplication { }` test with `Origin: http://localhost:5173` on `OPTIONS /api/v2/series` returns 204 with the declared headers.
- [ ] Commit: `feat(kotlin/cors): CORS for /api/v2/* with configurable UI origin`

---

## Task 5: Event bus + ring buffer

**Files:**
- Create: `events/EventBus.kt`
- Create: `events/RingBuffer.kt`
- Create: `schemas/V2Events.kt`
- Create: `backend/src/test/kotlin/.../events/RingBufferTest.kt`

**Steps:**
- [ ] `data class AppEvent(id: Long, type: String, payload: JsonElement)`.
- [ ] `class RingBuffer(capacity: Int = 1000)` — thread-safe (mutex), `fun append(event)` and `fun since(lastId: Long?): List<AppEvent>` that returns events strictly after `lastId`, empty if gap.
- [ ] `class EventBus(val buffer: RingBuffer)` — `fun publish(type, payload)` assigns the next id, appends, fanouts to subscribers (a `MutableSharedFlow<AppEvent>`).
- [ ] TDD:
  - append 1500 events with capacity 1000 → `since(0)` returns the newest 1000 (first 500 dropped).
  - `since(lastId)` within the window returns only events after `lastId`.
  - `since(lastId)` past the window returns empty (gap detection).
- [ ] Publish + subscribe basic test: launch a collector, publish 3 events, assert all 3 seen.
- [ ] Commit: `feat(kotlin/events): AppEvent ring buffer + EventBus publish/subscribe`

---

## Phase 2 — Read endpoints

## Task 6: GET /api/v2/series (list + detail + paginated)

**Files:**
- Create: `api/v2/SeriesRoutes.kt`
- Create: `schemas/V2Series.kt`
- Create: `backend/src/test/kotlin/.../api/v2/SeriesRoutesTest.kt`

**Steps:**
- [ ] `SeriesSummary` + `SeriesDetail` data classes per spec §13.
- [ ] Handler: list from `sonarr.getAllSeries()` merged with `db.getPriorityCache(seriesId)` + managed-download counts from `db.listManagedDownloads()`. Paginate via `PageParams`.
- [ ] `GET /api/v2/series/{id}` → 404 ProblemDetail when Sonarr doesn't find the id; 200 SeriesDetail otherwise.
- [ ] Integration test via `testApplication`: stub Sonarr MockEngine with 2 series, stub priority cache for one of them, assert list pagination and detail shape.
- [ ] Commit: `feat(kotlin/api-v2): series list + detail endpoints`

---

## Task 7: GET /api/v2/downloads (list + detail, filter by client)

**Files:**
- Create: `api/v2/DownloadsRoutes.kt`
- Create: `schemas/V2Downloads.kt`
- Create: `backend/src/test/kotlin/.../api/v2/DownloadsRoutesTest.kt`

**Steps:**
- [ ] `data class ManagedDownload(client, clientId, seriesId, seriesTitle, episodeIds, initialPriority, currentPriority, pausedByUs, firstSeenAt, lastReconciledAt)`.
- [ ] Handler joins `db.listManagedDownloads(clientFilter)` with `sonarr.getSeries(seriesId)` for titles (tolerant: unknown → title=null). Paginate.
- [ ] Detail endpoint: `GET /api/v2/downloads/{client}/{clientId}` → 404 when no row.
- [ ] Commit: `feat(kotlin/api-v2): managed-downloads list + detail with client filter`

---

## Task 8: GET /api/v2/audit (paginated + filters)

**Files:**
- Create: `api/v2/AuditRoutes.kt`
- Create: `schemas/V2Audit.kt`
- Modify: `sqldelight/Schema.sq` — add `listAuditFiltered` query taking optional `:seriesId`, `:action`, `:since` params.

**Steps:**
- [ ] Implement the filtered query with dynamic WHERE — SQLDelight supports conditional clauses via `IS NULL OR col = :param`.
- [ ] Handler parses query-string filters, dispatches.
- [ ] TDD: seed 10 audit rows across 3 series + 2 actions, query each filter combo, assert correct subset.
- [ ] Commit: `feat(kotlin/api-v2): audit-log list with filters + pagination`

---

## Task 9: GET /api/v2/settings (redacted)

**Files:**
- Create: `api/v2/SettingsRoutes.kt`
- Create: `schemas/V2Settings.kt`

**Steps:**
- [ ] `data class SettingsRedacted` — every field in `Settings` except `sonarrApiKey`, `tautulliApiKey`, `sabApiKey`, `qbitPassword`, `plexToken`, `redisUrl`, `apiKey` which render as literal string `"***"` when non-null, null when null.
- [ ] Handler: just serialise the current `state.settings` after copying into the redacted shape.
- [ ] TDD: assert `"***"` appears in place of each secret.
- [ ] Commit: `feat(kotlin/api-v2): redacted settings read endpoint`

---

## Task 10: GET /api/v2/mappings

**Files:**
- Create: `api/v2/MappingsRoutes.kt`
- Create: `schemas/V2Mappings.kt`

**Steps:**
- [ ] `data class MappingSnapshot(plexKeyToSeriesId: Map<String, Long>, lastRefreshStats: RefreshStats?, tautulliAvailable: Boolean)`.
- [ ] Expose `MappingState`'s last refresh stats (add field to `MappingState` if not present — default null, updated by `refreshMappings()`).
- [ ] Commit: `feat(kotlin/api-v2): mappings snapshot endpoint`

---

## Phase 3 — Action endpoints

## Task 11: POST /api/v2/series/{id}/recompute

**Files:**
- Create: `api/v2/ActionsRoutes.kt`
- Extend: `priority/PriorityService.kt` — add `fun invalidateAndRecompute(seriesId)`.

**Steps:**
- [ ] Handler: 404 if Sonarr doesn't know `id`; else call `invalidateAndRecompute`, return the new `PriorityResult`. Publish `priority-recomputed` event on success.
- [ ] Dry-run gate: if `settings.dryRun`, don't clear cache — just recompute in-memory and return, body includes `dry_run: true`.
- [ ] TDD: POST to series not in Sonarr → 404. POST to real series → new priority returned + event emitted.
- [ ] Commit: `feat(kotlin/api-v2): series recompute action`

---

## Task 12: POST /api/v2/mappings/refresh

**Files:**
- Extend: `api/v2/ActionsRoutes.kt`

**Steps:**
- [ ] Call `refreshMappings(sonarr, tautulli, cache, state)`, return `RefreshStats` + duration. Publish `mapping-refreshed` event.
- [ ] Integration test with MockEngine-backed clients.
- [ ] Commit: `feat(kotlin/api-v2): mappings refresh action`

---

## Task 13: POST /api/v2/downloads/{client}/{id}/actions/{pause|resume|boost|demote}

**Files:**
- Extend: `api/v2/ActionsRoutes.kt`

**Steps:**
- [ ] For `client=qbit` → QBitClient (`pause`, `resume`, `topPriority`, `bottomPriority`). For `client=sab` → SABClient `setPriority` with the value mapped from the action (`boost` → Force=2; `demote` → Low=-1; `pause`/`resume` → SAB equivalent).
- [ ] Idempotency: if the managed_download row shows `paused_by_us=true` and action is `pause`, respond 200 with `"already_paused": true` in body. No upstream call.
- [ ] Dry-run: skip upstream call, log audit, return 200 with `dry_run: true`.
- [ ] Publish `download-action` event with `{clientId, action, result}`.
- [ ] TDD: idempotency matrix test + dry-run assertion.
- [ ] Commit: `feat(kotlin/api-v2): download pause/resume/boost/demote actions`

---

## Task 14: DELETE /api/v2/downloads/{client}/{id}

**Files:**
- Extend: `api/v2/ActionsRoutes.kt`

**Steps:**
- [ ] 404 if row doesn't exist. Else `db.deleteManagedDownload(client, clientId)`. Publish `download-untracked` event.
- [ ] Does NOT call upstream to cancel the download — document in OpenAPI.
- [ ] Commit: `feat(kotlin/api-v2): untrack managed download`

---

## Phase 4 — SSE

## Task 15: SSE endpoint + replay

**Files:**
- Create: `api/v2/EventsRoute.kt`
- Modify: `backend/build.gradle.kts` (add `ktor-server-sse-jvm`)

**Steps:**
- [ ] `GET /api/v2/events` installs `SSE` plugin; on request:
  1. Read `Last-Event-ID` header or `?lastEventId=` query.
  2. Replay `ringBuffer.since(lastEventId)` events first.
  3. Subscribe to `eventBus.flow`, stream every new event thereafter.
  4. Set `X-Accel-Buffering: no` response header.
- [ ] Implement heartbeat: a `launch { while(isActive) { eventBus.publish("heartbeat", now); delay(30_000) } }` supervised by the app scope.
- [ ] Handle cancel: when client disconnects, unsubscribe cleanly.
- [ ] TDD via `testApplication`: open SSE stream, publish 2 events, assert both received in order with ids. Reconnect with `Last-Event-ID=<first id>`, assert only 2nd event replayed.
- [ ] Commit: `feat(kotlin/api-v2): SSE events stream with Last-Event-ID replay`

---

## Phase 5 — OpenAPI

## Task 16: OpenAPI document updates

**Files:**
- Modify: `openapi.json` (committed at repo root)
- Modify: `scripts/generate_openapi.py` — the Kotlin backend serves the file verbatim, but the python backend is still the generator source of truth during transition. Manually add the v2 paths + security scheme.

**Steps:**
- [ ] Add `components.securitySchemes.api_key = { type: apiKey, in: header, name: X-Api-Key }`.
- [ ] Add every v2 path with `security: [{ api_key: [] }]`. v1 paths keep empty `security: []`.
- [ ] Declare `application/problem+json` responses on 4xx/5xx for v2 endpoints.
- [ ] Declare SSE `text/event-stream` response on `/api/v2/events`.
- [ ] Run `openapi-spec-validator openapi.json` → must pass.
- [ ] Commit: `feat(openapi): declare v2 paths + security scheme + problem responses`

---

## Task 17: OpenAPI lint CI step

**Files:**
- Modify: `.github/workflows/release.yml`

**Steps:**
- [ ] Add a job step (can run within `test` or `app-contract-tests`): `pip install openapi-spec-validator && openapi-spec-validator openapi.json`.
- [ ] Add a grep-based lint: every line matching `"/api/v2/"` in openapi.json must have a `"security":` entry in its operation. Fail the step if the count of v2 paths doesn't equal the count of `api_key` security entries.
- [ ] Commit: `ci: lint openapi.json for v2 security declarations`

---

## Phase 6 — Contract tests

Each file below creates a new `test_v2_*.py` in `contract-tests/behavioral/`. All inherit the `reset_between_tests` autouse fixture (cleans DB + mappings). All new tests read `CONTRACT_TEST_API_KEY` from env and inject `X-Api-Key`. Missing env var → skipped with a clear message.

## Task 18: conftest.py updates

**Files:**
- Modify: `contract-tests/conftest.py`

**Steps:**
- [ ] New fixture `auth_client(base_url)` — an `httpx.Client` with `X-Api-Key: $CONTRACT_TEST_API_KEY` pre-set.
- [ ] New helper `sse_events(client, since=None)` — generator yielding `(id, event_type, data_dict)` tuples from `/api/v2/events`. Used by `test_v2_events.py`.
- [ ] Commit: `test(contract): conftest helpers for api key + sse`

---

## Task 19: test_v2_auth.py + test_v2_errors.py

**Files:**
- Create both test files.

**Steps:**
- [ ] Auth: request to `/api/v2/series` with no header → 401 + `application/problem+json` body + `type: /errors/unauthorized`. With wrong key → 401. With right key → 200.
- [ ] Errors: assert every declared slug (unauthorized, not-found, validation, upstream-unreachable) renders correctly for at least one endpoint. Use the settings endpoint for 404 simulations (e.g., a series id that doesn't exist).
- [ ] Commit: `test(contract/v2): auth + rfc7807 error shape`

---

## Task 20: test_v2_series.py + test_v2_downloads.py + test_v2_audit.py

**Files:**
- Create three test files.

**Steps:**
- [ ] For each: list endpoint returns `PaginatedEnvelope` shape (`records`, `totalRecords`, `offset`, `limit`). Detail endpoint shape matches spec §13 sketches.
- [ ] Pagination: `?limit=1&offset=0` + `?limit=1&offset=1` returns non-overlapping records with `totalRecords >= 2`.
- [ ] Sort: `?sort=title` returns records in alphabetical order.
- [ ] Filters: `audit?action=ongrab` returns only ongrab entries.
- [ ] Commit (one per test file): `test(contract/v2): series|downloads|audit list+detail+pagination`

---

## Task 21: test_v2_settings.py + test_v2_mappings.py

**Files:**
- Create both.

**Steps:**
- [ ] Settings: assert every secret field's value is literally `"***"`; assert non-secret fields pass through (e.g. `dryRun`, `logLevel`).
- [ ] Mappings: POST the test-mode `/inject-series-mapping` helper, then GET `/api/v2/mappings`, assert the injected mapping appears.
- [ ] Commit: `test(contract/v2): settings redaction + mappings snapshot`

---

## Task 22: test_v2_actions.py

**Files:**
- Create.

**Steps:**
- [ ] Recompute: POST recompute for a stubbed series, assert the response shape + that `/api/v2/audit?action=priority_recomputed` shows the new entry.
- [ ] Idempotent pause: POST pause twice, second response has `already_paused: true`.
- [ ] Untrack: POST recompute creates a managed_download row via the on-grab path → DELETE → GET detail → 404.
- [ ] Commit: `test(contract/v2): action endpoints + dry-run + idempotency`

---

## Task 23: test_v2_events.py

**Files:**
- Create.

**Steps:**
- [ ] Open SSE stream; in another thread, POST recompute on a series; assert `priority-recomputed` event arrives within 2s with the right series_id.
- [ ] Reconnect with `Last-Event-ID=<id of first event>`, open another action, assert only the new event replays (not the first).
- [ ] Heartbeat: open stream, wait 35s, assert at least one `heartbeat` event arrived.
- [ ] Commit: `test(contract/v2): SSE events + replay + heartbeat`

---

## Phase 7 — Acceptance + docs

## Task 24: Schemathesis with API key

**Files:**
- Modify: `contract-tests/schema/test_openapi_conformance.py`

**Steps:**
- [ ] Inject `X-Api-Key` via a Schemathesis hook (`@schema.hook`). Skip if env var unset.
- [ ] Verify all four v1 operations + every new v2 operation pass schema conformance. `response_schema_conformance` check only, same as Spec A.
- [ ] Commit: `test(contract): schemathesis injects api key for v2 paths`

---

## Task 25: CI wiring for CONTRACT_TEST_API_KEY

**Files:**
- Modify: `.github/workflows/release.yml`

**Steps:**
- [ ] In the `app-contract-tests` job, set `PRIORITARR_API_KEY=ci-test-key` on the container startup env, and `CONTRACT_TEST_API_KEY=ci-test-key` in the pytest run env.
- [ ] Same for the python `contract-tests` job for parity.
- [ ] Commit: `ci: inject api key env vars for contract-tests jobs`

---

## Task 26: README + shadow_diff_v2

**Files:**
- Modify: `README.md`
- Create: `scripts/shadow_diff_v2.sh`

**Steps:**
- [ ] README: extend the Contract Testing section with the v2 surface summary, auth instructions, `CONTRACT_TEST_API_KEY` note.
- [ ] `shadow_diff_v2.sh`: dual-send a GET /api/v2/series request to two backends (old+new), normalise + diff. Supplement to the existing `shadow_diff.sh`.
- [ ] Commit: `docs: README v2 contract-testing + shadow_diff_v2.sh`

---

## Task 27: Final acceptance

- [ ] All 23 Spec A contract tests still pass against the backend.
- [ ] All ≥30 new v2 contract tests pass.
- [ ] `openapi-spec-validator openapi.json` passes; the new lint step (every v2 path has a security entry) passes.
- [ ] Memory steady-state ≤ 200MB with an SSE stream open (measure via `docker stats`).
- [ ] CI all-green on the feat/expand-backend branch once it's merged to main.
- [ ] Backwards-compat smoke test: run the pre-Spec-C kotlin image (old tag) against the same v1 contract-tests suite, confirm still green — guards against accidental v1 breakage.
- [ ] Tick every box in Spec §15.

---

## Out of scope for this plan (belongs to Spec D or E)

- Any UI work.
- WebSocket transport (SSE is sufficient).
- Multi-user or RBAC.
- Rate limiting.
- Cursor-based pagination.
- DB schema migrations beyond additive columns.

---

## Plan self-review notes

1. **Spec coverage:** §4 versioning → Task 1+16; §5 auth → Task 1; §6 errors → Task 2; §7 endpoints → Tasks 6–14; §8 SSE → Tasks 5+15; §9 pagination → Task 3; §10 CORS → Task 4; §11 tests → Tasks 18–23; §12 migration → N/A (no migration); §15 acceptance → Task 27.

2. **Safety:** Every task keeps the existing contract tests green. Any task that touches the webhook surface is flagged and has a reason.

3. **Commit cadence:** ~27 commits, each independently green-CI-able. Safe to bisect.

4. **Biggest risk:** Task 15 (SSE reconnect semantics) — subtle to test and proxy-sensitive. Prototype in Task 5+15 before committing to the API shape. Task 24 (Schemathesis + auth) also has hidden sharp edges if Schemathesis doesn't provide an easy hook interface — fallback is to skip schemathesis for v2 routes and lean on the behavioural tests.
