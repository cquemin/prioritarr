# Spec C — Prioritarr Backend Expansion (v2, read + control surface)

**Author:** cquemin + Claude
**Status:** Draft
**Created:** 2026-04-18
**Dependencies:** Spec B (Kotlin port) — acceptance criterion met; shadow deploy running.
**Feeds:** Spec D (React UI) — everything the UI reads or mutates lands here.

---

## 1. Goal

Extend the Kotlin backend with the read + control endpoints the UI needs, plus the cross-cutting infra (auth, errors, pagination, real-time) that's required to do that sanely. No changes to the v1 webhook surface — Sonarr and Tautulli stay pointed at the same paths, same shapes.

The delivered artefact is a backend that:
- A UI can call directly from a browser (CORS, API key auth).
- Returns structured errors a JSON client can parse.
- Streams state changes so the UI doesn't have to poll.
- Scales to the ~100–200 series / ~dozens-of-downloads scale we have without pagination being decorative.

## 2. Why now

- **The UI work (Spec D) is blocked on this.** Without a read API the frontend has nothing to render.
- **Contract tests are at 23 today.** A contract-first expansion keeps that discipline — every new endpoint ships with a behavioural + schema test.
- **Kotlin parity is proven.** Spec B's 23-test suite passed against the port. We can layer on top confidently rather than simultaneously porting and extending.
- **Do it before the React work, not alongside.** The previous decomposition got this right — mixing backend and frontend in the same iteration is how APIs end up shaped by the first UI prototype's accidental needs.

## 3. Out of scope (tracked elsewhere)

- React UI → Spec D.
- WebSocket transport → SSE is enough for our traffic; WS only becomes worth it if the UI needs to push (reconnect policy, subscription multiplexing, etc.).
- Multi-user / RBAC → single shared API key is adequate for the self-hosted one-user case.
- OAuth / SSO → same.
- DB schema migration beyond additive columns → existing SQLite tables stay shaped as they are; Spec E would own migrations.

## 4. Versioning strategy

Two surfaces coexist:

| Surface | Paths | Scope | Auth |
|---|---|---|---|
| **v1 webhook** (frozen by Spec A) | `/health`, `/ready`, `/api/sonarr/on-grab`, `/api/plex-event`, `/api/v1/_testing/*` | Never changes without a new major bump | webhooks: none (shared secret is the Sonarr/Tautulli API key); health: none; testing: env-gated |
| **v2 read+control** (new in this spec) | `/api/v2/...` | New reads, actions, SSE | X-Api-Key required |

The `/api/v1/_testing/*` name is historical — it's still *only* the test-mode router. Don't read anything else into the `v1` prefix. All new work lands under `v2` unambiguously.

## 5. Auth

### 5.1 Mechanism

- Single shared API key, set via `PRIORITARR_API_KEY` env var. Unset → auth disabled (local-only development; warn-log at startup).
- Header: `X-Api-Key: <key>`. Alternative: `Authorization: Bearer <key>` — support both, since some UI libs default to one or the other.
- Applied to every `/api/v2/*` route. Not applied to `/health`, `/ready`, `/api/sonarr/on-grab`, `/api/plex-event`, `/openapi.json`, `/api/v1/_testing/*`.

### 5.2 Failure mode

Missing or wrong key → `401 Unauthorized` with an RFC 7807 body:
```json
{
  "type": "/errors/unauthorized",
  "title": "Unauthorized",
  "status": 401,
  "detail": "Missing or invalid X-Api-Key header."
}
```

### 5.3 Rotation

Restart the container with the new value. No revocation list, no rotation window — if that becomes important (shared hosting, multi-tenant) it lives in Spec E.

## 6. Error model — RFC 7807 Problem Details

Every non-2xx response on `/api/v2/*` returns `Content-Type: application/problem+json` with at minimum:

```json
{
  "type": "/errors/<slug>",
  "title": "Human title",
  "status": 4xx | 5xx,
  "detail": "What went wrong, specifically.",
  "instance": "/api/v2/series/99999"   // optional, usually the request path
}
```

Defined error types:

| Slug | Status | When |
|---|---|---|
| `unauthorized` | 401 | missing/invalid X-Api-Key |
| `forbidden` | 403 | reserved — not used in v1 of this spec |
| `not-found` | 404 | requested entity doesn't exist |
| `validation` | 422 | request shape wrong (missing field, bad enum, etc.) |
| `upstream-unreachable` | 502 | Sonarr/Tautulli/etc. timed out or refused |
| `internal` | 500 | unhandled — body `detail` is scrubbed |

Webhook endpoints (`/api/sonarr/on-grab`, `/api/plex-event`) keep the **always-200** contract from Spec A §5.3 — they never emit RFC 7807. This is intentional and calls out the difference loudly.

## 7. New endpoints

### 7.1 Reads

| Verb | Path | Returns | Paginated | Notes |
|---|---|---|---|---|
| GET | `/api/v2/series` | `PaginatedEnvelope<SeriesSummary>` | yes | sort by priority asc by default; `?sort=title` supported |
| GET | `/api/v2/series/{id}` | `SeriesDetail` | no | snapshot + current priority + cache metadata |
| GET | `/api/v2/downloads` | `PaginatedEnvelope<ManagedDownload>` | yes | filter `?client=qbit|sab` |
| GET | `/api/v2/downloads/{client}/{client_id}` | `ManagedDownloadDetail` | no | |
| GET | `/api/v2/audit` | `PaginatedEnvelope<AuditEntry>` | yes | filter `?series_id=N`, `?since=<iso>`, `?action=ongrab\|cache_invalidated\|priority_recomputed` |
| GET | `/api/v2/settings` | `SettingsRedacted` | no | no secrets (API keys, passwords, tokens blanked `"***"`) |
| GET | `/api/v2/mappings` | `MappingSnapshot` | no | current plex↔sonarr map + stats from last refresh |
| GET | `/api/v2/events` | `text/event-stream` | no | SSE — see §8 |

### 7.2 Actions

| Verb | Path | Body | Effect |
|---|---|---|---|
| POST | `/api/v2/series/{id}/recompute` | `{}` | invalidate priority cache + recompute synchronously; return new `PriorityResult` |
| POST | `/api/v2/mappings/refresh` | `{}` | trigger `refreshMappings` out-of-band; return refresh stats |
| POST | `/api/v2/downloads/{client}/{client_id}/actions/pause` | `{}` | upstream-pause the download; update `paused_by_us=true` |
| POST | `/api/v2/downloads/{client}/{client_id}/actions/resume` | `{}` | resume; `paused_by_us=false` |
| POST | `/api/v2/downloads/{client}/{client_id}/actions/boost` | `{}` | top-priority in the torrent/nzb client |
| POST | `/api/v2/downloads/{client}/{client_id}/actions/demote` | `{}` | bottom-priority |
| DELETE | `/api/v2/downloads/{client}/{client_id}` | — | untrack (remove the `managed_downloads` row); does NOT cancel the upstream download |

All actions are idempotent where that makes sense (resume on already-resumed returns 200 with an informative body; double-untrack returns 404 on the second call).

Dry-run behaviour: when `PRIORITARR_DRY_RUN=true`, action endpoints return `200` but include `"dry_run": true` in the response body and emit an audit log entry rather than calling upstream.

## 8. Real-time — Server-Sent Events

### 8.1 Why SSE not WS

- One-way (server → client) covers every current need: priority recomputes, audit appends, mapping refreshes, heartbeats. The UI never pushes state changes that the server doesn't already see via webhooks or action endpoints.
- HTTP/1.1 friendly, survives corporate proxies that choke on WebSocket upgrades.
- Auto-reconnect with `Last-Event-ID` is in every browser and is trivial to honour server-side.
- Zero framing library needed — it's line-oriented text.

If the UI ever needs to push (e.g. bulk select-and-act operations that need ordered ack), revisit and add WS alongside.

### 8.2 Event shape

```
event: priority-recomputed
id: 1234
data: {"series_id":42,"priority":1,"label":"P1 Live-following","reason":"..."}

event: audit-appended
id: 1235
data: {"id":9876,"ts":"2026-04-18T12:00:00.123456+00:00","action":"ongrab","series_id":42,...}

event: mapping-refreshed
id: 1236
data: {"stats":{"cached":120,"tvdb":3,"title":1,"unmatched":0},"duration_ms":842}

event: heartbeat
id: 1237
data: {"ts":"2026-04-18T12:00:30.000000+00:00"}
```

### 8.3 Reconnect

Server honours `Last-Event-ID` header on reconnect — replays every event from the internal ring buffer after that ID. Ring buffer size: 1000 events (≈ several hours at our traffic). Beyond that window, replay skips silently; the client does a full reload on gap detection.

### 8.4 Heartbeat

Server emits one `heartbeat` event every 30 seconds even if no state changed. UI uses this as a liveness check and reconnects aggressively if more than 2× that goes by without one.

## 9. Pagination

arr-style, matching Sonarr/Radarr conventions the user is already fluent in:

```
GET /api/v2/series?offset=0&limit=50&sort=priority&sort_dir=asc
```

Response envelope:

```json
{
  "records": [...],
  "totalRecords": 249,
  "offset": 0,
  "limit": 50
}
```

Rules:
- `limit` default 50, max 1000, min 1. Over the max → 422 validation error.
- `offset` default 0, min 0. Beyond `totalRecords` → empty `records`, same `totalRecords`.
- `sort` supported values per endpoint documented in OpenAPI; unknown → 422.
- `sort_dir` is `asc` or `desc`; default per endpoint.
- No cursor pagination in v1 of this spec — offset is fine at our scale. Revisit at 10k+ rows.

## 10. CORS

Default: allow `Origin: http://localhost:*` and `Origin: <PRIORITARR_UI_ORIGIN>` (from env). Methods: `GET, POST, DELETE, OPTIONS`. Headers: `Content-Type, X-Api-Key, Authorization, Last-Event-ID`. Credentials: false (API key, not cookies).

Production deploy (Traefik fronting both prioritarr and the UI) will typically run both under the same origin and CORS becomes a no-op; the env var covers the dev and split-origin cases.

## 11. Test strategy

### 11.1 Contract tests

Extend `contract-tests/` with:
- `behavioral/test_v2_series.py` — list/detail/recompute shapes, priority field present, pagination envelope fields.
- `behavioral/test_v2_downloads.py` — list/filter/action happy paths + idempotency of double-pause, double-untrack → 404.
- `behavioral/test_v2_audit.py` — filter by series_id + since + action, pagination across ≥2 pages.
- `behavioral/test_v2_auth.py` — missing header → 401 RFC 7807; valid → 200; wrong key → 401.
- `behavioral/test_v2_events.py` — SSE subscribe → trigger a recompute via POST → event arrives within 2s with the expected shape. `Last-Event-ID` replay test.
- `schema/test_openapi_conformance.py` — Schemathesis expands to cover v2 paths automatically once they're in `openapi.json`.

### 11.2 Unit tests

- Auth plugin: table-driven `(header, value) → decision` matrix.
- Error mapping: every declared slug has an exception-to-Problem mapping test.
- Pagination parser: malformed `limit`/`offset` → 422.
- SSE ring buffer: fills, wraps, replays correctly on `Last-Event-ID` past the window.

### 11.3 OpenAPI drift

Same `openapi.json` generate-and-commit approach as Spec A. The committed spec grows; the CI drift check still compares byte-for-byte against the served `/openapi.json`. The Kotlin backend still serves the file verbatim (Spec B §8.1), so "committed matches served" is tautological; the real drift risk is committed-vs-code.

Add a small `backend-openapi-lint` CI step: `openapi-spec-validator openapi.json` (already used in Spec A). Plus a new rule: for every `/api/v2/*` path, the OpenAPI MUST declare a `security: [{ api_key: [] }]` entry.

## 12. Migration from v1 → v2

There is no migration — v1 stays frozen. Clients decide per endpoint which surface to call. Internally:

- **Webhook paths stay at `/api/sonarr/on-grab` and `/api/plex-event`** because Sonarr/Tautulli point there and re-configuring each client per endpoint-version bump is avoidable friction for nothing.
- **`/health`, `/ready`, `/openapi.json`** stay unversioned because they're meta.
- **`/api/v1/_testing/*`** stays because changing the env-gated test router for a rename is pure churn.

The only "migration" is that UI-facing code from day 1 targets `/api/v2/*`. There's no v1 UI to port.

## 13. Data shapes (sketches)

```kotlin
data class SeriesSummary(
    val id: Long,
    val title: String,
    val tvdbId: Long,
    val priority: Int?,          // null if never computed
    val label: String?,
    val computedAt: String?,     // ISO +00:00
    val managedDownloadCount: Int,
)

data class SeriesDetail(
    val summary: SeriesSummary,
    val snapshot: SeriesSnapshot?, // null if build failed
    val priority: PriorityResult?,
    val cacheExpiresAt: String?,
    val recentAudit: List<AuditEntry>, // last 10, most recent first
)

data class PaginatedEnvelope<T>(
    val records: List<T>,
    val totalRecords: Int,
    val offset: Int,
    val limit: Int,
)

data class ProblemDetail(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String,
    val instance: String? = null,
)
```

Final field sets get locked in the implementation plan — draft list above is not authoritative.

## 14. Risks

### 14.1 v1 drift

Any accidental change to `/api/v1/_testing/*` or webhook shapes breaks the existing contract suite. Mitigation: the same suite runs in CI for both backends; Spec C implementation must never touch Spec A tests.

### 14.2 SSE proxy buffering

Some reverse proxies buffer SSE streams and only flush on close. Set `X-Accel-Buffering: no` on the response and confirm against the traefik + authelia stack in staging. CI can't easily verify this — add to the acceptance §15 manual checklist.

### 14.3 Ring buffer memory

1000 events × ~200 bytes each ≈ 200KB. Trivial. If we later add large-payload events, cap by byte-size not count.

### 14.4 Schemathesis fuzzing vs auth

Schemathesis will hammer v2 endpoints without the API key by default → every test fails with 401. Mitigation: configure the contract suite to read `CONTRACT_TEST_API_KEY` and inject it into the Schemathesis `Hooks.before_call` handler. Same mechanism the behavioural tests use.

## 15. Acceptance

- [ ] All existing 23 contract tests still pass unchanged against the new backend.
- [ ] ≥30 new contract tests covering the v2 surface (reads, actions, auth, errors, SSE).
- [ ] Every v2 endpoint documented in the committed `openapi.json`, passes `openapi-spec-validator`, every v2 path has `security: [{ api_key: [] }]`.
- [ ] `/api/v2/events` SSE: open a stream, trigger an action via another curl, receive the event within 2s in a scripted end-to-end test.
- [ ] Memory steady-state still ≤ 200MB with SSE connection open, under 10 open streams.
- [ ] Auth: request without `X-Api-Key` → 401 RFC 7807. Request with correct key → 200.
- [ ] CORS preflight (`OPTIONS`) from an allowed origin returns the declared headers.
- [ ] Dry-run gate: with `PRIORITARR_DRY_RUN=true`, no POST action endpoint calls upstream; response body reports `dry_run: true`.
- [ ] `shadow_diff.sh` equivalent for v2: a `shadow_diff_v2.sh` that exercises a handful of v2 endpoints against two backends (e.g. before-change vs after-change of this branch).

## 16. Effort estimate

- Auth plugin + RFC 7807 plumbing: **1 day**
- 8 read endpoints + pagination: **2 days**
- 7 action endpoints + dry-run wiring: **2 days**
- SSE + ring buffer + reconnect: **1.5 days**
- CORS: **0.5 day**
- Contract + unit tests: **2 days**
- OpenAPI generation + drift + lint: **0.5 day**
- CI wiring: **0.5 day**

**Total: ~10 engineering-days.** Less contested than Spec B because there's no behavioural drift risk — additive surface, existing tests protect the old surface.

## 17. Open questions

1. **Do `/api/v2/*` endpoints share the heartbeat coroutine's lifecycle or run under a separate scope?** Recommend: same supervisor, so a crash in an SSE handler doesn't take down the scheduler. Revisit in the plan.
2. **Idempotency keys on actions?** Probably not — UI buttons don't retry automatically. Add if the frontend needs it.
3. **Rate limiting?** Self-hosted single-user — no. Leave slot open in the plan for a middleware later.
4. **Do we want a `/api/v2/healthz` that ALSO requires auth, distinct from the public `/health`?** Recommend: no. `/health` is already fit-for-purpose for Traefik's liveness check; making it auth'd would require threading the API key to Traefik.

---

*Next step if approved: write `docs/plans/2026-04-XX-expand-backend-v2.md` with the usual per-task TDD breakdown.*
