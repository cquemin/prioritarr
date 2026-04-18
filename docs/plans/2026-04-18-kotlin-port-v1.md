# Kotlin Port v1 (Spec B) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the Python/FastAPI implementation of prioritarr with a byte-compatible Kotlin/JVM one. The **existing contract-tests suite passes unchanged** — this is the single correctness criterion.

**Spec:** `docs/specs/2026-04-18-prioritarr-kotlin-port-design.md`

**Architecture:** Ktor 2 server + client, kotlinx.serialization, SQLDelight over SQLite JDBC, Lettuce for Redis, kotlinx.coroutines for scheduling, Gradle Kotlin DSL build. Fat jar in an eclipse-temurin JRE container. OpenAPI is served as the committed static `openapi.json` (no re-generation).

**Tech versions (pin in `build.gradle.kts`):** Kotlin 2.0.21, Ktor 2.3.12, kotlinx.serialization 1.7.3, SQLDelight 2.0.2, Lettuce 6.4.0.RELEASE, Logback 1.5.12 + logstash-logback-encoder 8.0, Hoplite 2.8.0, JDK 21 target bytecode.

---

## Critical pre-flight

1. Spec A is shipped and its contract suite passes against the Python backend. If it doesn't, stop and fix Spec A first.
2. The `prioritarr-kotlin/` tree lives **alongside** the Python `prioritarr/` package at the repo root, not replacing it. We keep Python running in production until cutover (§Task 15).
3. Expected failure mode during TDD: contract tests fail on some subtle wire-format detail (byte-identical is harder than it looks). Budget half the total effort for debugging §Task 14.

---

## File structure

All new paths relative to `D:/docker/prioritarr/`:

```
prioritarr-kotlin/                            # NEW — sibling of prioritarr/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/                            # committed wrapper
├── gradlew, gradlew.bat
├── Dockerfile                                  # NEW, JVM-based
├── openapi.json                                # SYMLINK to ../openapi.json — single source of truth
├── src/main/kotlin/org/cquemin/prioritarr/
│   ├── Main.kt
│   ├── config/Settings.kt
│   ├── database/Schema.sq                      # SQLDelight — ports prioritarr/database.py schema
│   ├── database/Database.kt
│   ├── clients/{Sonarr,Tautulli,Plex,QBittorrent,Sabnzbd}.kt
│   ├── priority/{Snapshot,Compute}.kt
│   ├── reconcile/Reconcile.kt
│   ├── sweep/Sweep.kt
│   ├── webhooks/{SonarrGrab,PlexEvent}.kt
│   ├── api/{Health,SonarrWebhook,PlexWebhook,Testing}.kt
│   └── schemas/{Health,Ready,Webhooks}.kt
├── src/main/resources/
│   ├── logback.xml
│   └── openapi.json                            # copied in from repo root at build time
└── src/test/kotlin/...

.github/workflows/
└── release.yml                                  # MODIFY: add kotlin-build + kotlin-contract-tests jobs

docker/prioritarr-kotlin/                       # new compose include for shadow deploy
└── docker-compose.yml                           # container definition, port 8001
```

---

## Task 1: Gradle scaffold

**Files:**
- Create: `prioritarr-kotlin/settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`
- Create: Gradle wrapper (`./gradlew`, `./gradlew.bat`, `gradle/wrapper/*`)

**Steps:**
- [ ] Run `gradle init --type kotlin-application --dsl kotlin --package org.cquemin.prioritarr --project-name prioritarr-kotlin` from an empty dir, then move the generated files into `prioritarr-kotlin/`.
- [ ] Edit `build.gradle.kts` to:
  - `kotlin("jvm") version "2.0.21"`
  - `kotlin("plugin.serialization") version "2.0.21"`
  - `id("app.cash.sqldelight") version "2.0.2"`
  - `id("com.github.johnrengelman.shadow") version "8.1.1"` (fat-jar)
  - Dependencies: `io.ktor:ktor-server-core-jvm`, `ktor-server-netty-jvm`, `ktor-server-content-negotiation-jvm`, `ktor-serialization-kotlinx-json-jvm`, `ktor-server-status-pages-jvm`, `ktor-client-core-jvm`, `ktor-client-cio-jvm`, `ktor-client-content-negotiation-jvm`, `app.cash.sqldelight:sqlite-driver`, `app.cash.sqldelight:coroutines-extensions`, `io.lettuce:lettuce-core:6.4.0.RELEASE`, `com.sksamuel.hoplite:hoplite-yaml:2.8.0`, `ch.qos.logback:logback-classic:1.5.12`, `net.logstash.logback:logstash-logback-encoder:8.0`
  - Test deps: `io.ktor:ktor-server-test-host-jvm`, `org.jetbrains.kotlin:kotlin-test-junit5`
- [ ] `sqldelight { databases { create("Db") { packageName.set("org.cquemin.prioritarr.database") } } }`
- [ ] Confirm `./gradlew build` succeeds on an empty project (no tests yet).
- [ ] Commit: `chore(kotlin): gradle scaffold for prioritarr kotlin port`

---

## Task 2: SQLDelight schema port

**Files:**
- Read: `prioritarr/database.py` (Python schema at lines 10–56)
- Create: `prioritarr-kotlin/src/main/sqldelight/org/cquemin/prioritarr/database/Schema.sq`

**Steps:**
- [ ] Copy the CREATE TABLE statements verbatim (series_priority_cache, managed_downloads, webhook_dedupe, audit_log, heartbeat). SQLDelight accepts SQLite DDL as-is.
- [ ] For each, add SQLDelight-style named queries that mirror the methods on `prioritarr/database.py`:
  - `selectPriorityCache: SELECT * FROM series_priority_cache WHERE series_id = ?;`
  - `upsertPriorityCache: INSERT INTO series_priority_cache ... ON CONFLICT(series_id) DO UPDATE ...;`
  - etc. — **one generated function per Python `db.x()` callsite in the current code**.
- [ ] Write a unit test that opens an in-memory SQLite via `JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)`, applies `Database.Schema.create(driver)`, inserts/reads via the generated API. Run `./gradlew test` — expect PASS.
- [ ] Commit: `feat(kotlin/database): port sqlite schema and query surface via sqldelight`

---

## Task 3: `Database.kt` façade

**Files:**
- Create: `prioritarr-kotlin/src/main/kotlin/org/cquemin/prioritarr/database/Database.kt`

**Steps:**
- [ ] Wrap the SQLDelight-generated API in a `Database(path: String)` class whose public methods mirror `prioritarr/database.py` Python method signatures 1:1 — `updateHeartbeat()`, `getHeartbeat(): Instant?`, `insertManagedDownload(...)`, etc.
- [ ] Enforce that `execute()`-style commits happen per-call (SQLite JDBC auto-commits by default; confirm).
- [ ] Unit test: round-trip a heartbeat timestamp and assert the returned `Instant` formats to ISO with `+00:00` offset via `OffsetDateTime.ofInstant(instant, ZoneOffset.UTC).format(ISO_OFFSET_DATE_TIME)`.
- [ ] Commit: `feat(kotlin/database): Database façade with Python-equivalent method surface`

---

## Task 4: Settings + env loader

**Files:**
- Create: `prioritarr-kotlin/src/main/kotlin/org/cquemin/prioritarr/config/Settings.kt`

**Steps:**
- [ ] Port `prioritarr/config.py` Settings dataclass + `load_settings_from_env()` to a Kotlin `data class Settings` + `fun loadFromEnv(): Settings`.
- [ ] Read every `PRIORITARR_*` env var the Python version reads (full list: SONARR_URL/SONARR_API_KEY/TAUTULLI_URL/TAUTULLI_API_KEY/QBIT_URL/QBIT_USERNAME/QBIT_PASSWORD/SAB_URL/SAB_API_KEY/PLEX_URL/PLEX_TOKEN/REDIS_URL/DRY_RUN/TEST_MODE/LOG_LEVEL/CONFIG_PATH).
- [ ] If `CONFIG_PATH` is set, overlay the YAML file onto nested config sections (priority_thresholds, intervals, cache, audit) via Hoplite.
- [ ] Unit test: set env vars, call `loadFromEnv()`, assert correct values, including the YAML overlay. Assert `testMode` defaults false and is true for `"true"`, `"1"`, `"yes"`.
- [ ] Commit: `feat(kotlin/config): env + yaml overlay settings loader`

---

## Task 5: Upstream HTTP clients (one commit per client)

**Files:**
- Create 5 files in `prioritarr-kotlin/src/main/kotlin/org/cquemin/prioritarr/clients/`: `Sonarr.kt`, `Tautulli.kt`, `Plex.kt`, `QBittorrent.kt`, `Sabnzbd.kt`

**For each client:**
- [ ] Port the public methods from `prioritarr/clients/<name>.py` to suspend functions on a Kotlin class taking a Ktor `HttpClient` + base URL + API key.
- [ ] Same timeouts as Python (120s per the main.py defaults).
- [ ] Same URL patterns, same headers (`X-Api-Key` for Sonarr, `apikey=` query for Tautulli, `X-Plex-Token` for Plex).
- [ ] Return types: kotlinx.serialization data classes matching the payload subset the Python code actually reads (don't over-model — if the Python code only uses `id`, `title`, `tvdbId`, the Kotlin response class has those three fields and `ignoreUnknownKeys = true`).
- [ ] Per-client unit test with MockEngine serving the corresponding JSON fixture from `prioritarr/tests/fixtures/` (reuse the existing fixtures — they're JSON, language-agnostic).
- [ ] Commit per client: `feat(kotlin/clients): port <ClientName>`

---

## Task 6: Pure priority computation

**Files:**
- Read: `prioritarr/priority.py`, `prioritarr/models.py` (SeriesSnapshot, PriorityResult)
- Create: `prioritarr-kotlin/src/main/kotlin/org/cquemin/prioritarr/priority/{Snapshot.kt,Compute.kt}`

**Steps:**
- [ ] Port `PriorityThresholds`, `SeriesSnapshot`, `PriorityResult` as Kotlin data classes.
- [ ] Port `compute_priority(snap, thresholds)` to a pure function `fun computePriority(snap: SeriesSnapshot, thresholds: PriorityThresholds): PriorityResult`.
- [ ] Copy the Python unit tests from `tests/test_priority.py` and translate one-for-one into kotlin.test. The priority logic is the highest-risk single piece of business logic; every Python test must have a Kotlin counterpart that passes.
- [ ] Run `./gradlew test :priority:test` (or whichever task is scoped) — expect ALL tests PASS.
- [ ] Commit: `feat(kotlin/priority): port priority computation with full unit-test parity`

---

## Task 7: Redis mapping cache + Plex↔Sonarr matching

**Files:**
- Read: `prioritarr/main.py` lines 153–300 (`_refresh_mappings`, `_load_cached_mappings`, `_save_cached_mappings`).
- Create: `prioritarr-kotlin/src/main/kotlin/org/cquemin/prioritarr/mapping/Mapping.kt`

**Steps:**
- [ ] Port the 3-step TVDB + path + title matching flow using Lettuce's coroutine API for Redis hget/hset.
- [ ] Use the **exact same Redis key name** (`prioritarr:plex_to_series`) and TTL (`604800` seconds = 7 days) so the Kotlin backend can read a mapping the Python backend wrote (and vice versa). Critical for shadow deploy in §Task 15.
- [ ] Unit test with an embedded Redis (`com.github.kstyrc:embedded-redis` or testcontainers).
- [ ] Commit: `feat(kotlin/mapping): port plex↔sonarr matching with redis cache parity`

---

## Task 8: Response schemas + StatusPages webhook shield

**Files:**
- Create: `prioritarr-kotlin/src/main/kotlin/org/cquemin/prioritarr/schemas/{Health,Ready,Webhooks}.kt`
- Create: `prioritarr-kotlin/src/main/kotlin/org/cquemin/prioritarr/api/Errors.kt`

**Steps:**
- [ ] Declare `@Serializable data class` for each shape: HealthOk, HealthUnhealthy, ReadyResponse (with the `DependencyStatus` enum), OnGrabIgnored/Processed/Duplicate, PlexEventOk/Unmatched.
- [ ] Configure the Json instance with `classDiscriminator = ""` and `classDiscriminatorMode = ClassDiscriminatorMode.NONE` if using sealed classes. Prefer *not* using sealed classes for the union response shapes — handlers return `JsonElement` directly from a concrete data class, no discriminator is added.
- [ ] Install a Ktor StatusPages plugin that turns every unhandled exception on `/api/sonarr/on-grab` and `/api/plex-event` into a 200 JSON body `{"status":"ignored","eventType":"<best-effort>"}` or equivalent. Spec §5.3 calls this out explicitly.
- [ ] Unit test the serialisation: assert `Json.encodeToString(OnGrabProcessed(priority=3, label="P3 X"))` produces `{"status":"processed","priority":3,"label":"P3 X"}` with that exact key order.
- [ ] Commit: `feat(kotlin/schemas): response models + always-200 status-pages shield`

---

## Task 9: `/health` + `/ready` endpoints

**Files:**
- Create: `prioritarr-kotlin/src/main/kotlin/org/cquemin/prioritarr/api/Health.kt`

**Steps:**
- [ ] Port the health logic from `prioritarr/health.py` (liveness = heartbeat fresh; readiness = 4 upstream checks in parallel).
- [ ] Register `GET /health` and `GET /ready` Ktor routes returning the serialised schemas.
- [ ] ISO-format `last_heartbeat` using `OffsetDateTime` with `+00:00` offset — see Spec §5.2.
- [ ] Integration test against an in-memory Ktor `testApplication { }` — boot without real upstreams, assert `/health` returns 503 before heartbeat and 200 after.
- [ ] Commit: `feat(kotlin/api): /health and /ready endpoints with dependency rollup`

---

## Task 10: Webhook handlers

**Files:**
- Create: `prioritarr-kotlin/src/main/kotlin/org/cquemin/prioritarr/webhooks/{SonarrGrab,PlexEvent}.kt`
- Create: `prioritarr-kotlin/src/main/kotlin/org/cquemin/prioritarr/api/{SonarrWebhook,PlexWebhook}.kt`

**Steps:**
- [ ] Port `parse_ongrab_payload` + `handle_ongrab` → `parseOnGrab` + `handleOnGrab` suspend fns.
- [ ] Port `parse_tautulli_watched` + `handle_watched` → `parseTautulliWatched` + `handleWatched`.
- [ ] Register `POST /api/sonarr/on-grab` and `POST /api/plex-event`. Handlers call the parse/handle pair, emit the correct oneOf response.
- [ ] Preserve the "duplicate carries priority+label (cached)" quirk — the priority cache lookup inside `handleOnGrab` appends `" (cached)"` to the label.
- [ ] Integration test via `testApplication { }` with MockEngine-backed upstream clients and the in-memory DB.
- [ ] Commit: `feat(kotlin/api): sonarr-grab and plex-event webhook handlers`

---

## Task 11: Test-mode router (gated on `PRIORITARR_TEST_MODE`)

**Files:**
- Create: `prioritarr-kotlin/src/main/kotlin/org/cquemin/prioritarr/api/Testing.kt`

**Steps:**
- [ ] Mirror `prioritarr/testing_api.py` exactly: `POST /api/v1/_testing/{reset,stale-heartbeat,inject-series-mapping}`, all returning `{"status":"ok"}`.
- [ ] Mount the router only if `settings.testMode == true`. Log a warning on mount (same log line text as Python).
- [ ] Integration test with `TEST_MODE=true`: POST /reset, verify tables are truncated; POST /inject-series-mapping then POST /api/plex-event with the injected key, verify `status=ok`.
- [ ] Commit: `feat(kotlin/api): gated test-mode router for contract-test state control`

---

## Task 12: Scheduler + background jobs

**Files:**
- Read: `prioritarr/main.py` lifespan + scheduler setup (lines 600–750).
- Create: `prioritarr-kotlin/src/main/kotlin/org/cquemin/prioritarr/reconcile/Reconcile.kt`, `sweep/Sweep.kt`
- Extend: `Main.kt`

**Steps:**
- [ ] Port `reconcile_client`, `run_backfill_sweep`, `run_cutoff_sweep` to suspend functions.
- [ ] In `Main.kt` wire a `supervisorScope` with five launched coroutines (heartbeat, reconcile, backfill, cutoff, refresh_mappings). Each uses `while (isActive) { work(); delay(interval) }`. Use `supervisorScope` so a crash in one job doesn't cascade.
- [ ] Spec §7: intentionally drop APScheduler's `misfire_grace_time` semantics — a skipped tick just runs on the next cycle.
- [ ] Integration test: override the interval to 100ms via test-mode settings, verify each job fires at least once in a 1s test window.
- [ ] Commit: `feat(kotlin/scheduler): coroutine-based background jobs`

---

## Task 13: `openapi.json` static serve

**Files:**
- Modify: `prioritarr-kotlin/build.gradle.kts` (copy `../openapi.json` to `src/main/resources/openapi.json` at build time)
- Modify: `Main.kt` (install route)

**Steps:**
- [ ] Add a Gradle task that copies `../openapi.json` → `src/main/resources/openapi.json` before `processResources`.
- [ ] Register a Ktor route `GET /openapi.json` that `call.respondBytes` the resource, with `ContentType.Application.Json`.
- [ ] Integration test: start the app, GET /openapi.json, assert body is byte-identical to the file at repo root.
- [ ] Commit: `feat(kotlin/api): serve committed openapi.json as static resource`

---

## Task 14: Dockerfile + release workflow

**Files:**
- Create: `prioritarr-kotlin/Dockerfile`
- Modify: `.github/workflows/release.yml` — add `kotlin-build` and `kotlin-contract-tests` jobs

**Steps:**
- [ ] Dockerfile: multi-stage — stage 1 `gradle:8-jdk21` runs `./gradlew shadowJar`, stage 2 `eclipse-temurin:21-jre-alpine` COPYs the fat jar. ENTRYPOINT `java -jar /app/prioritarr.jar`.
- [ ] Build locally with `docker build -t prioritarr-kotlin:dev prioritarr-kotlin/`, run with the same env vars as the Python container plus `PRIORITARR_TEST_MODE=true`, curl `/openapi.json` — must be byte-identical.
- [ ] Add `kotlin-build` CI job that runs `./gradlew test shadowJar` and uploads the jar as an artifact.
- [ ] Add `kotlin-contract-tests` CI job that clones the same mocks used by the Python contract-tests job (reuse `contract-tests/mocks/docker-compose.yml`), builds the kotlin image, starts it with `TEST_MODE=true`, and runs `pytest contract-tests/` against it. **Reuses the same contract suite — this is the whole point of Spec A.**
- [ ] Add drift check step: `docker run --rm --entrypoint cat prioritarr-kotlin:test /openapi.json > runtime.json && diff openapi.json runtime.json`. Identical to the Python drift check.
- [ ] Kotlin container publishes to `ghcr.io/cquemin/prioritarr-kotlin:latest` on main push (keep the Python image publishing unchanged during shadow).
- [ ] Commit: `ci(kotlin): build, contract-test, and publish image`

---

## Task 15: Shadow deploy

**Files:**
- Create: `docker/prioritarr-kotlin/docker-compose.yml`

**Steps:**
- [ ] Define a second service `prioritarr-kotlin` on port 8001, same network (`t2_proxy`), same Redis and `/config` volume as the Python one (both can safely share the SQLite file **read-only from Kotlin** during shadow — Kotlin must not write audit rows during shadow).
- [ ] Initially run with `PRIORITARR_DRY_RUN=true` and `PRIORITARR_TEST_MODE=false` (production-like).
- [ ] Add a bash script `scripts/shadow_diff.sh` that dual-sends the last N webhook payloads from the Sonarr audit log to both backends and diffs responses byte-by-byte. Run for 72h; zero diffs required before cutover.
- [ ] Commit: `chore(kotlin/shadow): side-by-side deploy config + diff script`

---

## Task 16: Cutover

**Files:**
- Modify: `media-stack-v3.yml` (or wherever Sonarr's `On Grab` connection URL is templated)

**Steps:**
- [ ] Only after §Task 15's 72h soak with zero diffs: flip Sonarr's OnGrab webhook URL from `http://prioritarr:8000/api/sonarr/on-grab` to `http://prioritarr-kotlin:8000/api/sonarr/on-grab`. Same for Tautulli's `/api/plex-event`.
- [ ] Keep the Python container `prioritarr` running (idle) for a further 7 days as rollback. Rollback is a one-line env change.
- [ ] After 7 days with zero regressions in the audit log: decommission the Python container, delete the `prioritarr/` Python package in a separate cleanup commit (*not* during cutover — one concern at a time).
- [ ] Final commit (post-soak): `chore: decommission python backend after kotlin cutover`

---

## Task 17: Final acceptance check

- [ ] Contract tests green against kotlin on both CI (mocked) and live (real upstreams).
- [ ] Drift check green.
- [ ] 24h job-cadence check — grep the Kotlin log for "reconcile completed", "backfill_sweep completed", "cutoff_sweep completed" counts and compare to Python's historical cadence.
- [ ] Memory steady-state ≤ 200MB, startup ≤ 10s (per Spec §9).
- [ ] Log format JSON-line parity verified by feeding Kotlin logs into the same `docker logs` grep patterns from README.

---

## Out of scope for this plan (tracked separately in Spec C)

New endpoints, RFC 7807 errors, SSE, auth, pagination, `/api/v1` prefix, UI, schema migrations. The kotlin port must not sneak any of these in — if a file touches behaviour outside the Python parity, revert and move it to Spec C.

---

## Plan self-review

- **Spec coverage check:** all 13 sections of Spec B map to tasks. §3 stack → Task 1; §4 layout → all tasks; §5 schemas → Task 8; §6 endpoints → Tasks 9–10; §7 jobs → Task 12; §8 risks → §8.1 solved by Task 13, §8.2 by Task 8, §8.3 by Task 7, §8.4 by Task 9 (+ test suite), §8.5 by Task 8; §9 acceptance → Task 17; §11 migration → Tasks 15–16.
- **Placeholder scan:** no "TBD", no vague "etc." except where explicitly calling out an enumeration ("same list of env vars as Python").
- **Commit cadence:** ~17 commits, each independently testable. Safe to bisect.
- **Biggest risk:** Task 8 (serialisation parity) + Task 14 (drift check). Both have a fast feedback loop; failures show up immediately in CI.
