# Spec B — Prioritarr Kotlin Port (v1, behaviour-identical)

**Author:** cquemin + Claude
**Status:** Draft
**Created:** 2026-04-18
**Supersedes:** nothing (new)
**Dependencies:** Spec A (API Contract v1) — must be shipped and contract-tests passing against Python before this spec is viable.

---

## 1. Goal

Port the Python/FastAPI implementation of prioritarr to Kotlin/JVM such that **the same contract-tests suite passes unchanged**. No new features, no removed features, no renamed endpoints, no schema changes. The cutover must be transparent to Sonarr and Tautulli — their webhook URLs, payloads, and observed responses do not change by a single byte.

Spec C (separate document, not yet written) will then layer new capabilities on top of the Kotlin backend (structured errors, SSE, list endpoints, auth, etc.).

## 2. Why port at all

- **Ecosystem alignment.** The broader toolchain in this repo is JVM-adjacent; a Kotlin backend plays nicer with IntelliJ, kotlinx.coroutines, and the team's other JVM work.
- **Compile-time safety.** Kotlin's null safety + data classes catch whole categories of bugs the current Python code handles at runtime (e.g. the `_con` vs `conn` attribute confusion we hit in Task 9).
- **Startup + memory.** Python/uvicorn's ~80 MB baseline is fine, but JVM with CDS/AppCDS and G1GC can hold steady <120 MB while serving 10× the webhook concurrency without the GIL.
- **Not** because Python is too slow — it isn't. This service is I/O-bound on Sonarr and Tautulli. The driver is language ergonomics, not performance.

None of the above justifies the port on its own. The real gate is that **Spec A has locked the API surface**, so the port is a mechanical exercise with a hard correctness criterion (contract tests). If Spec A weren't done, this port would risk silently shifting the wire format — unacceptable.

## 3. Stack selection

| Concern | Choice | Rationale |
|---|---|---|
| HTTP framework | **Ktor 2.x** (server + client) | Kotlin-native, coroutine-first, minimal magic. Spring Boot is heavy for a 4-endpoint service; Javalin lacks first-class coroutines. |
| Build | Gradle + Kotlin DSL | Standard for modern Kotlin; `shadowJar` for a fat jar Docker image. |
| Concurrency | `kotlinx.coroutines` | Matches FastAPI's async model 1:1; structured concurrency makes the reconcile/sweep jobs safer than the current `BackgroundScheduler` threading. |
| JSON | `kotlinx.serialization` | Kotlin-native, compile-time, no reflection. Jackson would work but adds classpath weight. |
| DB | **SQLite JDBC + SQLDelight** | SQLDelight generates Kotlin types from the existing schema; we don't rewrite the schema, we point it at the existing `/config/state.db`. |
| Redis | **Lettuce** | Non-blocking, coroutine-friendly via the Kotlin coroutines extension. Jedis is blocking. |
| Scheduler | `kotlinx.coroutines.delay` in a supervisor scope | Replaces APScheduler. Simpler, no third-party scheduler dependency, no misfire semantics to port. |
| Config | Env-first, hOCON overlay | Matches current env-first + YAML overlay. Hoplite or Konf for parsing. |
| OpenAPI | **Generated, not hand-written.** Dropwizard-style annotations + `ktor-openapi-gen` OR swagger-core | Must produce a spec **byte-identical** to the committed `openapi.json`. This is the #1 risk — see §8. |
| Base image | `eclipse-temurin:21-jre-alpine` | JRE-only (not JDK), Alpine for size. |
| Observability | SLF4J + Logback with JSON encoder (Logstash Logback Encoder) | Preserves the current `{"ts":"...","level":"...",...}` log format expected by the Monitoring via logs README section. |

## 4. Module layout

Mirror the Python module boundaries 1:1 so code review can diff side-by-side:

```
prioritarr-kotlin/
├── build.gradle.kts
├── settings.gradle.kts
├── Dockerfile
├── src/main/kotlin/org/cquemin/prioritarr/
│   ├── Main.kt                  # entrypoint, wires app + scheduler
│   ├── config/
│   │   └── Settings.kt          # ~ prioritarr/config.py
│   ├── database/
│   │   ├── Schema.sq            # SQLDelight schema (mirrors existing)
│   │   └── Database.kt          # façade around generated queries
│   ├── clients/
│   │   ├── Sonarr.kt            # ~ clients/sonarr.py
│   │   ├── Tautulli.kt
│   │   ├── Plex.kt
│   │   ├── QBittorrent.kt
│   │   └── Sabnzbd.kt
│   ├── priority/
│   │   ├── Snapshot.kt          # ~ priority.py / models.py SeriesSnapshot
│   │   └── Compute.kt           # the priority calculation (pure function)
│   ├── reconcile/
│   │   └── Reconcile.kt
│   ├── sweep/
│   │   └── Sweep.kt
│   ├── webhooks/
│   │   ├── SonarrGrab.kt
│   │   └── PlexEvent.kt
│   ├── api/
│   │   ├── Health.kt            # GET /health, /ready
│   │   ├── SonarrWebhook.kt     # POST /api/sonarr/on-grab
│   │   ├── PlexWebhook.kt       # POST /api/plex-event
│   │   └── Testing.kt           # /api/v1/_testing/* — gated on TEST_MODE
│   └── schemas/                 # kotlinx.serialization @Serializable data classes
│       ├── Health.kt
│       ├── Ready.kt
│       └── Webhooks.kt
└── src/test/kotlin/             # unit tests — Kotlin-idiomatic, not a port of pytest
```

Contract tests live outside this repo layout — they're in `prioritarr/contract-tests/` and speak HTTP, so they're backend-agnostic. Point them at the Kotlin instance; no changes needed.

## 5. Schema translation

### 5.1 Response models

Python (Pydantic) → Kotlin (`kotlinx.serialization`):

```kotlin
// schemas/Webhooks.kt
@Serializable
sealed class OnGrabResponse

@Serializable
@SerialName("ignored")  // NOTE: discriminator approach TBD — see §8
data class OnGrabIgnored(
    val status: String = "ignored",
    val eventType: String,
) : OnGrabResponse()

@Serializable
data class OnGrabProcessed(
    val status: String = "processed",
    val priority: Int,
    val label: String,
) : OnGrabResponse()

@Serializable
data class OnGrabDuplicate(
    val status: String = "duplicate",
    val priority: Int,
    val label: String,
) : OnGrabResponse()
```

The three shapes must serialise identically to the Pydantic versions. Crucially the field **order** matters for strict byte-identical output (some JSON consumers pin on order even though the spec says they shouldn't) — kotlinx.serialization preserves declaration order, matching FastAPI's output.

### 5.2 The ISO +00:00 gotcha

Python: `datetime.now(timezone.utc).isoformat()` → `"2026-04-18T10:00:00.123456+00:00"`.

Kotlin default `Instant.toString()` → `"2026-04-18T10:00:00.123456Z"`.

Must be explicitly formatted: `OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)` or equivalent. Contract test `test_ready_last_heartbeat_is_iso_offset_or_null` catches this; it's non-negotiable since changing the format is a wire-format break.

### 5.3 The always-200 webhook contract

Ktor's content negotiation by default will return 422 on malformed JSON. **Override with a custom `StatusPages` plugin** so *any* exception inside the webhook handlers still returns HTTP 200 with a best-effort ignored/error body — matching the Python handler's try/except-everything posture.

## 6. Endpoint-by-endpoint translation

| Endpoint | Python behaviour | Kotlin translation notes |
|---|---|---|
| `GET /health` | Reads `heartbeat` row, returns 200 or 503. | Direct. SQLDelight query. |
| `GET /ready` | Calls 4 upstreams with short timeouts, rolls up. | Direct. Parallel `async { }` for the 4 checks; `awaitAll()`. |
| `POST /api/sonarr/on-grab` | Parse payload → dedupe check → compute priority → apply label → audit. | Same flow. Pure function `compute_priority` transliterates cleanly. |
| `POST /api/plex-event` | Lookup in `_plex_key_to_series_id` map, refresh on miss, invalidate cache. | The map lives in a `Mutex`-guarded singleton or `ConcurrentHashMap`. `_refresh_mappings()` becomes a coroutine; synchronous-on-miss behaviour preserved. |

## 7. Background jobs

`APScheduler`'s 4 jobs (heartbeat, reconcile, backfill_sweep, cutoff_sweep, refresh_mappings) map to 5 supervisor-scoped coroutines:

```kotlin
suspend fun scheduler(settings: Settings) = supervisorScope {
    launch { every(Duration.ofSeconds(30)) { db.updateHeartbeat() } }
    launch { every(intervals.reconcileMinutes) { reconcile() } }
    launch { every(intervals.backfillSweepHours) { backfillSweep() } }
    // ...
}
```

Failure isolation is clearer than APScheduler's `misfire_grace_time` — a failed coroutine doesn't kill siblings (supervisor scope), but no silent job skipping either.

## 8. Top risks

### 8.1 OpenAPI byte-identical drift (HIGHEST)

Spec A's CI drift check compares the committed `openapi.json` byte-for-byte against the running backend. If the Kotlin backend generates a spec that differs in key ordering, schema title casing, or `nullable` expression, **every contract test still passes but CI fails the drift job**.

Options, in order of preference:
1. **Serve the committed `openapi.json` as a static file.** Simplest. The spec is the source of truth; the Kotlin code exposes it verbatim. Drift check trivially passes. Downside: lose auto-generation, drift between annotations and served spec possible — but that's caught by the contract tests anyway.
2. Generate and normalise (sort keys, strip generator metadata). Fragile.
3. Accept some drift and teach the CI check to ignore key order. Defeats the purpose.

**Recommendation: option 1.** The Python backend currently also *commits* the generated spec. Kotlin just skips the "generate" step and serves the same committed file. Tests catch behaviour drift regardless.

### 8.2 Serialisation discriminator mismatch

Pydantic's `Literal["ignored"]` in `OnGrabIgnored` serialises as `{"status": "ignored", ...}`. kotlinx.serialization's sealed-class polymorphism adds a `"type"` discriminator by default — **must be disabled**. Use the `classDiscriminatorMode = NONE` setting, or avoid sealed hierarchies entirely and return `Any` / `JsonObject` from handlers. Worth prototyping in week 1 — this is where the port is most likely to silently break.

### 8.3 Redis key/value formats

Python stores `plex_key → series_id` as `HSET` with string values. Lettuce will happily do the same, but the key names and TTL (7d) must be byte-identical for a hot cutover. Add a contract test that pre-seeds Redis via the test-mode endpoints and verifies the Kotlin handler reads the same cache the Python handler wrote. (Not in scope for Spec A contract tests; add to Spec B validation.)

### 8.4 Datetime, floating-point, regex quirks

- Python `re` vs Kotlin `Regex` — the anime title matching in `_normalise_title` looks simple but **test the TVDB GUID extraction regex** on a real Plex payload before cutover.
- SQLite `datetime` column comparisons are string comparisons; as long as both backends write `isoformat()` with the `+00:00` offset, ordering holds.

### 8.5 Webhook validation strictness

FastAPI is lenient about malformed JSON — it parses what it can and ignores missing fields. Ktor's `ContentNegotiation` is stricter. The `always-200` rule (§5.3) handles this, but requires discipline: *every* handler must catch `SerializationException` and downgrade to an `ignored` response.

## 9. Acceptance criteria

This spec ships when **all** of the following are true:

- [ ] The contract-tests suite (`contract-tests/`) passes unchanged against the Kotlin backend with `CONTRACT_TEST_BASE_URL=http://kotlin-prioritarr:8000`.
- [ ] The committed `openapi.json` is served byte-identical from the Kotlin backend (CI drift check is green).
- [ ] Live-upstream smoke test (same as Step 2 of Spec A shipping) passes — 23 tests green against real Sonarr/Tautulli/Plex/qBit/SAB.
- [ ] Running container memory ≤ 200 MB steady state, startup ≤ 10 s.
- [ ] Log format is still single-line JSON with `ts|level|logger|msg` keys (README's Monitoring section stays correct).
- [ ] All 4 background jobs execute at their configured cadences without drift over a 24h soak.
- [ ] `PRIORITARR_TEST_MODE=true` mounts the three `/api/v1/_testing/*` endpoints exactly as the Python backend does.

## 10. Out of scope (explicit)

- New endpoints (list/search, delete, dry-run override, detailed queue introspection)
- Structured errors (RFC 7807 Problem Details)
- Server-Sent Events / WebSockets
- API key auth / auth at all
- Pagination on any endpoint
- Schema versioning or `/api/v1` prefix
- UI
- DB schema migration away from SQLite
- Replacing Redis with something JVM-native (e.g. Caffeine + disk persistence)

All of the above land in **Spec C**.

## 11. Migration strategy

Two-phase:

1. **Shadow.** Deploy the Kotlin backend to a new container `prioritarr-kotlin` on port 8001, pointing at the same Redis + SQLite as the Python one. Tautulli/Sonarr still webhook the Python container. Run contract-tests continuously against 8001 for 72 hours. Compare audit_log rows written by each — they should be byte-identical for identical inputs. (A simple background script can dual-send webhooks to both and diff responses.)
2. **Cutover.** Flip Sonarr and Tautulli webhook URLs to the Kotlin container. Keep the Python container running but idle for 7 days as rollback. After 7 days with no regressions, decommission.

Rollback is just flipping URLs back — same upstream state, same DB, no schema migration.

## 12. Effort estimate

- Stack scaffold + Docker + CI: **1 day**
- Upstream clients (5 of them, each ~100 LOC in Python): **2 days**
- Priority computation (pure function port + unit tests): **1 day**
- Scheduler + background jobs: **1 day**
- Webhook handlers + API routes: **1 day**
- Debugging contract-test failures (realistically the long pole): **3–5 days**
- Drift-check plumbing + `openapi.json` static serve: **0.5 day**
- Shadow deploy + soak: **3 days elapsed (passive)**

**Total: ~8 engineering-days + 3 elapsed for soak.** Estimate is ±50%; the contract-test debugging phase is the unknown.

## 13. Open questions

1. **Gradle vs Maven?** Default to Gradle Kotlin DSL. Ask if someone has a strong Maven preference.
2. **Spring Boot fallback?** If Ktor's OpenAPI story proves painful, Spring's `springdoc` is more mature. Decide at end of day 1.
3. **Do we port the unit tests too?** Recommend no — write fresh Kotlin-idiomatic unit tests and lean on the contract suite for behavioural coverage. Python tests stay in `tests/`, Kotlin tests in `src/test/kotlin/`, neither crosses over.
4. **Does Spec C start in parallel or strictly after?** Recommend strictly after; Spec C will be much easier to write against a Kotlin codebase than trying to do it during the port.

---

*Next step once this spec is approved: write the implementation plan (`docs/plans/2026-04-XX-kotlin-port-v1.md`), same structure as the Spec A plan — 15–20 numbered tasks, each with files-touched + TDD steps + commit message.*
