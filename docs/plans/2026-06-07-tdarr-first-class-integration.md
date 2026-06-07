# Tdarr First-Class Integration (Phase 1) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Promote prioritarr's existing env-only Tdarr pause integration to a first-class, UI-managed integration matching Sonarr/Plex/Trakt — Connections panel + test button, live-editable enable/cadence, optional API key, jobs-catalog entry, and README.

**Architecture:** No new architecture. Add a `TDARR` connection-test, extend the settings model with `tdarrApiKey` + live cadence `tdarrPauseMinutes`, expose/persist these through the existing settings routes, send an optional API-key header from `TdarrClient`, and surface everything in the React Connections + Background-jobs pages. The pause job behaviour is unchanged except its cadence becomes live-read.

**Tech Stack:** Kotlin (Ktor server + client, kotlinx.serialization), React + TypeScript (Vite), Gradle, Docker.

**Spec:** `docs/specs/2026-06-07-tdarr-first-class-integration-design.md`

**Branch:** `feat/tdarr-priority-orchestration` (already created; Phase-0 code committed there).

---

## File structure

| File | Change | Responsibility |
|------|--------|----------------|
| `backend/.../Constants.kt` | modify | Add `TDARR` to `ConnectionService` enum |
| `backend/.../connections/ConnectionTester.kt` | modify | Add `testTdarr()` |
| `backend/.../connections/ConnectionTesterTest.kt` | create/modify | Unit test for `testTdarr` |
| `backend/.../config/Settings.kt` | modify | `tdarrApiKey` + `Intervals.tdarrPauseMinutes` (+ editable/override/env) |
| `backend/.../clients/Tdarr.kt` | modify | Optional `apiKey` header |
| `backend/.../Main.kt` | modify | Pass `tdarrApiKey`; cadence reads `tdarrPauseMinutes` |
| `backend/.../schemas/V2.kt` | modify | `SettingsRedacted`: `tdarrUrl`, `tdarrApiKey`, `tdarrPauseEnabled` |
| `backend/.../api/v2/V2Routes.kt` | modify | TDARR test dispatch; expose + merge new settings |
| `frontend/src/pages/SettingsPage.tsx` | modify | Tdarr `ConnectionCard` |
| `frontend/src/lib/jobs.tsx` | modify | `tdarr-plex-pause` catalog entry |
| `README.md` | modify | Feature highlight + Background-jobs row |

Package root (abbreviated `.../` above):
`backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/`
Test root:
`backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/`

---

## Task 1: Add `TDARR` connection service + test

**Files:**
- Modify: `backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/Constants.kt`
- Modify: `backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/connections/ConnectionTester.kt`
- Test: `backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/connections/ConnectionTesterTdarrTest.kt`

- [ ] **Step 1: Add the enum value**

In `Constants.kt`, the `ConnectionService` enum currently ends with `TRAKT("trakt");`. Add `TDARR` before the `;`:

```kotlin
enum class ConnectionService(val wire: String) {
    SONARR("sonarr"),
    TAUTULLI("tautulli"),
    QBIT("qbit"),
    SAB("sab"),
    PLEX("plex"),
    TRAKT("trakt"),
    TDARR("tdarr");

    companion object {
        fun fromWire(s: String?): ConnectionService? =
            entries.firstOrNull { it.wire.equals(s, ignoreCase = true) }
    }
}
```

- [ ] **Step 2: Write the failing test**

Create `ConnectionTesterTdarrTest.kt`. This mirrors how the other connection tests are unit-tested, but since `testClient()` builds its own CIO client internally, we test against a tiny local stub server using Ktor's `embeddedServer` (Netty) on an ephemeral port — the same approach used elsewhere in the suite for outbound-HTTP tests. If the existing suite instead injects a `MockEngine`, follow that pattern instead.

```kotlin
package org.yoshiz.app.prioritarr.backend.connections

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.http.ContentType
import kotlinx.coroutines.runBlocking
import org.yoshiz.app.prioritarr.backend.ConnectionTestStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectionTesterTdarrTest {
    @Test
    fun `connected when cruddb returns a json array`() = runBlocking {
        val server = embeddedServer(Netty, port = 0) {
            routing {
                post("/api/v2/cruddb") {
                    call.respondText("""[{"_id":"globalsettings","pauseAllNodes":false}]""", ContentType.Application.Json)
                }
            }
        }.start(wait = false)
        val port = server.engineConfig.connectors.first().port
        val result = testTdarr("http://localhost:$port", apiKey = null)
        server.stop(0, 0)
        assertTrue(result.ok)
        assertEquals(ConnectionTestStatus.CONNECTED.wire, result.status)
    }

    @Test
    fun `version-failed when response is not a json array`() = runBlocking {
        val server = embeddedServer(Netty, port = 0) {
            routing { post("/api/v2/cruddb") { call.respondText("not json", ContentType.Text.Plain) } }
        }.start(wait = false)
        val port = server.engineConfig.connectors.first().port
        val result = testTdarr("http://localhost:$port", apiKey = null)
        server.stop(0, 0)
        assertEquals(ConnectionTestStatus.VERSION_FAILED.wire, result.status)
    }

    @Test
    fun `connection-failed when host unreachable`() = runBlocking {
        // Port 1 is never listening — forces a transport error.
        val result = testTdarr("http://localhost:1", apiKey = null)
        assertEquals(ConnectionTestStatus.CONNECTION_FAILED.wire, result.status)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :backend:test --tests "*ConnectionTesterTdarrTest*"`
Expected: FAIL — `testTdarr` is unresolved (does not compile yet).

- [ ] **Step 4: Implement `testTdarr`**

Add to `ConnectionTester.kt` after `testPlex` (it needs imports already present in the file: `post`, `setBody`, `contentType`, `JsonArray`). Add these imports at the top if missing:

```kotlin
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
```

Then the function:

```kotlin
/**
 * Tdarr: POST /api/v2/cruddb reading global settings. Tdarr has no
 * dedicated status endpoint and (by default) no auth, so a successful
 * read that parses as a JSON array confirms we're talking to Tdarr.
 * An optional apiKey is sent as a header for setups that enable auth.
 */
suspend fun testTdarr(rawUrl: String, apiKey: String?): ConnectionTestResult = testClient().use { http ->
    val url = "${normalize(rawUrl)}/api/v2/cruddb"
    val resp: HttpResponse = try {
        http.post(url) {
            contentType(ContentType.Application.Json)
            if (!apiKey.isNullOrBlank()) header("x-api-key", apiKey)
            setBody("""{"data":{"collection":"SettingsGlobalJSONDB","mode":"getAll"}}""")
        }
    } catch (e: Throwable) { return@use connectionFailure(e) }
    when (resp.status.value) {
        in 200..299 -> {
            val body = try { resp.body<JsonArray>() }
            catch (_: Throwable) {
                return@use ConnectionTestResult(false, ConnectionTestStatus.VERSION_FAILED.wire,
                    "Reached upstream but the response wasn't a JSON array — wrong URL path or not a Tdarr server.")
            }
            if (body.isNotEmpty()) ConnectionTestResult(true, ConnectionTestStatus.CONNECTED.wire)
            else ConnectionTestResult(false, ConnectionTestStatus.VERSION_FAILED.wire, "Tdarr returned an empty settings collection.")
        }
        401, 403 -> ConnectionTestResult(false, ConnectionTestStatus.AUTH_FAILED.wire, "Tdarr rejected the request (HTTP ${resp.status.value}). If Tdarr auth is on, set an API key.")
        else -> ConnectionTestResult(false, ConnectionTestStatus.VERSION_FAILED.wire, "Unexpected HTTP ${resp.status.value}.")
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :backend:test --tests "*ConnectionTesterTdarrTest*"`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/Constants.kt \
        backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/connections/ConnectionTester.kt \
        backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/connections/ConnectionTesterTdarrTest.kt
git commit -m "feat(tdarr): add TDARR connection service + test"
```

---

## Task 2: Settings model — `tdarrApiKey` + live cadence

**Files:**
- Modify: `backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/config/Settings.kt`

Note: `tdarrUrl` and `tdarrPauseEnabled` already exist (Phase 0). This task adds `tdarrApiKey` and `Intervals.tdarrPauseMinutes`.

- [ ] **Step 1: Add `tdarrApiKey` to `Settings`**

In the `Settings` data class, after the existing `tdarrPauseEnabled` line:

```kotlin
    val tdarrUrl: String? = null,
    val tdarrApiKey: String? = null,
    val tdarrPauseEnabled: Boolean = false,
```

- [ ] **Step 2: Add `tdarrPauseMinutes` to `Intervals`**

In the `Intervals` data class, after `traktTokenRefreshHours`:

```kotlin
    val traktTokenRefreshHours: Int = 24,
    /** Cadence of the Plex-aware Tdarr pause job. */
    val tdarrPauseMinutes: Int = 1,
```

- [ ] **Step 3: Add fields to `EditableSettings`**

In `EditableSettings`, the Tdarr block currently has `tdarrUrl` + `tdarrPauseEnabled`. Make it:

```kotlin
    val tdarrUrl: String? = null,
    val tdarrApiKey: String? = null,
    val tdarrPauseEnabled: Boolean? = null,
    val tdarrPauseMinutes: Int? = null,
```

- [ ] **Step 4: Apply in `applySettingsOverride`**

In the `intervals = base.intervals.copy(...)` block, add after `traktTokenRefreshHours`:

```kotlin
        traktTokenRefreshHours = override.traktTokenRefreshHours ?: base.intervals.traktTokenRefreshHours,
        tdarrPauseMinutes = override.tdarrPauseMinutes ?: base.intervals.tdarrPauseMinutes,
```

And in the top-level `base.copy(...)`, the Tdarr line currently sets `tdarrUrl` + `tdarrPauseEnabled`. Add `tdarrApiKey`:

```kotlin
    tdarrUrl = override.tdarrUrl ?: base.tdarrUrl,
    tdarrApiKey = override.tdarrApiKey ?: base.tdarrApiKey,
    tdarrPauseEnabled = override.tdarrPauseEnabled ?: base.tdarrPauseEnabled,
```

- [ ] **Step 5: Env loading + YAML overlay**

In `loadSettingsFrom`, the Tdarr env block currently reads `tdarrUrl` + `tdarrPauseEnabled`. Add the API key after `tdarrUrl = env("TDARR_URL"),`:

```kotlin
        tdarrUrl = env("TDARR_URL"),
        tdarrApiKey = env("TDARR_API_KEY"),
        tdarrPauseEnabled = (env("TDARR_PAUSE_ENABLED", "false") ?: "false").lowercase() in TRUTHY,
```

In the YAML `intervals` overlay block (where `p1StallMinutes` etc. are read), add:

```kotlin
                p1StallMinutes = o.num("p1_stall_minutes") { it.toInt() } ?: intervals.p1StallMinutes,
                tdarrPauseMinutes = o.num("tdarr_pause_minutes") { it.toInt() } ?: intervals.tdarrPauseMinutes,
```

- [ ] **Step 6: Compile**

Run: `./gradlew :backend:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/config/Settings.kt
git commit -m "feat(tdarr): add tdarrApiKey setting + live tdarrPauseMinutes cadence"
```

---

## Task 3: `TdarrClient` API-key header + live cadence wiring

**Files:**
- Modify: `backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/clients/Tdarr.kt`
- Modify: `backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/Main.kt`

- [ ] **Step 1: Add optional `apiKey` to `TdarrClient`**

Change the constructor and the `post` helper. Add `import io.ktor.client.request.header`. New constructor + helper:

```kotlin
class TdarrClient(
    private val baseUrl: String,
    private val http: HttpClient,
    private val apiKey: String? = null,
) {
    private val root: String = baseUrl.trimEnd('/')
```

And in `post`:

```kotlin
    private suspend fun post(jsonBody: String): String =
        http.post("$root/api/v2/cruddb") {
            contentType(ContentType.Application.Json)
            if (!apiKey.isNullOrBlank()) header("x-api-key", apiKey)
            setBody(jsonBody)
        }.bodyAsText()
```

- [ ] **Step 2: Pass the key in `Main.kt`**

In `Main.kt`, the `tdarrClient` construction currently is:

```kotlin
    val tdarrClient: org.yoshiz.app.prioritarr.backend.clients.TdarrClient? =
        if (!settings.tdarrUrl.isNullOrBlank()) {
            org.yoshiz.app.prioritarr.backend.clients.TdarrClient(settings.tdarrUrl, tdarrHttp)
        } else null
```

Change the constructor call to:

```kotlin
            org.yoshiz.app.prioritarr.backend.clients.TdarrClient(settings.tdarrUrl, tdarrHttp, settings.tdarrApiKey)
```

- [ ] **Step 3: Make the job cadence live-read**

In `Main.kt`, the `JobId.TDARR_PLEX_PAUSE` job currently has `cadenceMinutes = { 1L }`. Change it to:

```kotlin
                cadenceMinutes = { liveSettings(db, settings).intervals.tdarrPauseMinutes.toLong() },
```

- [ ] **Step 4: Compile**

Run: `./gradlew :backend:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/clients/Tdarr.kt \
        backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/Main.kt
git commit -m "feat(tdarr): optional api-key header + live pause cadence"
```

---

## Task 4: Expose + persist settings; wire connection-test dispatch

**Files:**
- Modify: `backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/schemas/V2.kt`
- Modify: `backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/api/v2/V2Routes.kt`

- [ ] **Step 1: Add fields to `SettingsRedacted`**

In `schemas/V2.kt`, the `SettingsRedacted` data class has `plexUrl: String?` / `plexToken: String?`. Add after `plexToken`:

```kotlin
    val plexUrl: String?,
    val plexToken: String?,
    val tdarrUrl: String?,
    val tdarrApiKey: String?,
    val tdarrPauseEnabled: Boolean?,
```

(The cadence `tdarrPauseMinutes` is exposed via the existing nested `intervals` object in `SettingsRedacted` — adding it to the `Intervals` data class in Task 2 makes it appear automatically. If `SettingsRedacted` copies intervals into a separate redacted type rather than embedding `Intervals`, add `tdarrPauseMinutes` there too.)

- [ ] **Step 2: Populate in BOTH `SettingsRedacted(...)` builders**

`V2Routes.kt` constructs `SettingsRedacted(...)` twice (the `GET /settings` handler ~line 810 and the `POST /settings` response ~line 904). In **both**, after the `plexToken = redactSecret(s.plexToken),` line add:

```kotlin
            plexUrl = s.plexUrl,
            plexToken = redactSecret(s.plexToken),
            tdarrUrl = s.tdarrUrl,
            tdarrApiKey = redactSecret(s.tdarrApiKey),
            tdarrPauseEnabled = s.tdarrPauseEnabled,
```

- [ ] **Step 3: Merge in `mergeEditable`**

In `mergeEditable` (~line 144), after `plexToken = patch.plexToken ?: existing.plexToken,` add:

```kotlin
    plexUrl = patch.plexUrl ?: existing.plexUrl,
    plexToken = patch.plexToken ?: existing.plexToken,
    tdarrUrl = patch.tdarrUrl ?: existing.tdarrUrl,
    tdarrApiKey = patch.tdarrApiKey ?: existing.tdarrApiKey,
    tdarrPauseEnabled = patch.tdarrPauseEnabled ?: existing.tdarrPauseEnabled,
    tdarrPauseMinutes = patch.tdarrPauseMinutes ?: existing.tdarrPauseMinutes,
```

- [ ] **Step 4: Add the TDARR test dispatch**

In the `/connections/{service}/test` `when (service)` block in `V2Routes.kt`, after the `TRAKT ->` branch add:

```kotlin
            org.yoshiz.app.prioritarr.backend.ConnectionService.TDARR -> org.yoshiz.app.prioritarr.backend.connections.testTdarr(
                rawUrl = field("tdarrUrl", s.tdarrUrl),
                apiKey = s.tdarrApiKey,
            )
```

(`field(name, current)` is the existing helper that prefers the draft value from the request body, falling back to the saved setting. Use it for `tdarrUrl`. The API key is optional, so pass the saved value directly.)

- [ ] **Step 5: Compile**

Run: `./gradlew :backend:compileKotlin`
Expected: BUILD SUCCESSFUL. If `field(...)` requires non-null, wrap with `?: ""`.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/schemas/V2.kt \
        backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/api/v2/V2Routes.kt
git commit -m "feat(tdarr): expose/persist tdarr settings + connection-test dispatch"
```

---

## Task 5: Frontend — Tdarr Connections card

**Files:**
- Modify: `frontend/src/pages/SettingsPage.tsx`

- [ ] **Step 1: Add the card**

In `ConnectionsSection`, after the Plex `<ConnectionCard>` (the one with `service="plex"`) and before the closing of the section, add:

```tsx
      <ConnectionCard
        title="Tdarr"
        service="tdarr"
        current={s}
        fields={[
          { key: 'tdarrUrl', label: 'URL', type: 'url' },
          { key: 'tdarrApiKey', label: 'API key (optional)', secret: true },
        ]}
      />
```

- [ ] **Step 2: Type-check + build**

Run: `cd frontend && npm run build`
Expected: build succeeds (TypeScript compiles). `s` already carries the new fields because the settings response type is generated from / mirrors `SettingsRedacted`; if the frontend has a hand-written type for the settings response, add `tdarrUrl`, `tdarrApiKey`, `tdarrPauseEnabled`, and `intervals.tdarrPauseMinutes` to it.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/pages/SettingsPage.tsx
git commit -m "feat(tdarr): tdarr connections card"
```

---

## Task 6: Frontend — jobs catalog entry

**Files:**
- Modify: `frontend/src/lib/jobs.tsx`

- [ ] **Step 1: Add an icon import**

Add `Pause` to the `lucide-react` import line at the top:

```tsx
import {
  RefreshCw, Database, Search, Activity, Trash2, ListChecks, KeyRound, Cog,
  Webhook, ArrowLeftRight, Box, FileSearch, Pause,
} from 'lucide-react'
```

- [ ] **Step 2: Add the catalog entry**

Add this object to the `JOBS` array (place it after the `health-monitor` entry, near the other infra jobs):

```tsx
  {
    id: 'tdarr-plex-pause',
    name: 'Tdarr pause (Plex-aware)',
    icon: <Pause size={18} />,
    trigger: 'auto',
    short: 'Pause Tdarr transcoding while Plex is streaming.',
    description:
      'Every cycle, checks Plex for active playback sessions. When anything is streaming, it sets Tdarr’s global pauseAllNodes flag so background transcoding stops; when playback ends, it resumes Tdarr. Idempotent — only writes when the desired state differs.',
    why:
      'This host has no GPU, so a Plex software-transcode and a Tdarr encode fight over the same CPU and the stream buffers. Pausing Tdarr for the duration of a stream keeps playback smooth, then lets the conversion backlog resume automatically.',
    cadence: { key: 'intervals.tdarrPauseMinutes', unit: 'minutes', min: 1 },
    settings: [
      { key: 'tdarrPauseEnabled', label: 'Enabled', type: 'boolean', hint: 'Off by default. Requires a Tdarr URL in Connections.' },
    ],
    relatedSettings: [
      { section: 'connections', sectionLabel: 'Connections', field: 'Tdarr' },
    ],
  },
```

- [ ] **Step 3: Type-check + build**

Run: `cd frontend && npm run build`
Expected: build succeeds.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/lib/jobs.tsx
git commit -m "feat(tdarr): background-jobs catalog entry"
```

---

## Task 7: README documentation

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add a feature highlight**

In the feature-highlights bullet list near the top, add a bullet:

```markdown
- **Tdarr coordination** — pauses Tdarr's CPU transcoding while Plex is streaming (no-GPU hosts), resuming automatically when idle.
```

- [ ] **Step 2: Add a Background-jobs table row**

In the Background-jobs table, add:

```markdown
| **Tdarr pause (Plex-aware)** | 1 min (configurable) | Pauses Tdarr's global transcoding while any Plex session is active; resumes when idle. Configure under Connections → Tdarr and enable in Background jobs. Off by default. |
```

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs(tdarr): document Plex-aware pause integration"
```

---

## Task 8: Build, deploy, verify

**Files:** none (build + deploy + manual verification)

- [ ] **Step 1: Full backend build + tests**

Run: `./gradlew :backend:test`
Expected: BUILD SUCCESSFUL, all tests pass (including the new `ConnectionTesterTdarrTest`).

- [ ] **Step 2: Frontend build**

Run: `cd frontend && npm run build`
Expected: succeeds with no type errors.

- [ ] **Step 3: Build the image**

Run (from repo root): `./scripts/build-image.sh`
Expected: `Built ghcr.io/cquemin/prioritarr:<version> and :latest`.

- [ ] **Step 4: Tag rollback + deploy**

```bash
docker tag ghcr.io/cquemin/prioritarr:latest ghcr.io/cquemin/prioritarr:rollback-phase1
docker compose -f D:/docker/media-stack-v3.yml up -d --no-deps prioritarr
```
Expected: prioritarr recreated.

- [ ] **Step 5: Verify health + feature**

```bash
docker inspect -f '{{.State.Health.Status}}' prioritarr   # expect: healthy
```
Then in the UI (`https://<domain>/prioritarr` → Settings):
- **Connections → Tdarr** shows URL + API-key fields and **Test connection** returns *Connected*.
- **Background jobs → Tdarr pause (Plex-aware)** shows the Enabled toggle + cadence; toggling/saving persists (reload shows the new value).
- Functional check (from Phase 0): manually set Tdarr `pauseAllNodes=true`; within one cadence interval prioritarr logs `tdarr resumed (plex sessions=0)` and the flag returns to false.

Expected: all pass. On failure, roll back:
```bash
docker tag ghcr.io/cquemin/prioritarr:rollback-phase1 ghcr.io/cquemin/prioritarr:latest
docker compose -f D:/docker/media-stack-v3.yml up -d --no-deps prioritarr
```

- [ ] **Step 6: Final commit (if any verification fixups were needed)**

```bash
git add -A && git commit -m "fix(tdarr): phase 1 verification fixups"
```

---

## Self-review notes

- **Spec coverage:** connection test (Task 1), API key (Tasks 2–4), live cadence (Tasks 2–3, 6), settings exposure/persistence (Task 4), Connections UI (Task 5), jobs catalog (Task 6), README (Task 7), build/deploy/verify with rollback (Task 8). All spec sections covered.
- **Type consistency:** `testTdarr(rawUrl, apiKey)` signature is used identically in Task 1 (def) and Task 4 (call). `tdarrPauseMinutes` / `tdarrApiKey` / `tdarrPauseEnabled` names are consistent across Settings, EditableSettings, SettingsRedacted, mergeEditable, env, and the frontend cadence key `intervals.tdarrPauseMinutes`.
- **Open assumption to verify during execution:** the exact connection-test unit-test harness (embedded server vs MockEngine) — Task 1 Step 2 says to match whatever the existing suite uses. And whether the frontend settings response is a generated type or hand-written (Task 5 Step 2).
