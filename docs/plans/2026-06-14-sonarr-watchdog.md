# Sonarr Watchdog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect when Sonarr's command executor wedges (commands stuck in `started` state >30 min) and auto-recover via an escalating restart ladder (3× app-restart, then a scoped docker-socket-proxy container restart).

**Architecture:** A new stateful `SonarrWatchdog` reconcile job (LIGHT, ~5-min cadence) reads `/api/v3/command`, filters stuck commands with a pure helper, and drives a pure decision state-machine through confirm → app-restart ×3 → container-restart → cooldown. All decision logic is pure functions behind suspend seams (no HTTP in tests), mirroring `TdarrPlexPause`. The container fallback talks plain HTTP to a new `tecnativa/docker-socket-proxy`.

**Tech Stack:** Kotlin, ktor client (kotlinx.serialization `JsonElement`), JUnit5 + `kotlin.test` + `kotlinx-coroutines-test`, Gradle. Spec: `docs/specs/2026-06-14-sonarr-watchdog-design.md`.

**Build/test commands** (run from `D:\git\prioritarr\prioritarr`):
- Single test class: `./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.<pkg>.<Class>"`
- Full suite: `./gradlew :backend:test`

---

### Task 1: SonarrClient — getCommands + restartApp

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/clients/Sonarr.kt`
- Test: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/clients/SonarrCommandsTest.kt` (create)

- [ ] **Step 1: Write the failing test** (ktor MockEngine, matching existing client test style)

```kotlin
package org.yoshiz.app.prioritarr.backend.clients

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.test.Test
import kotlin.test.assertEquals

class SonarrCommandsTest {
    private fun client(handler: (io.ktor.client.request.HttpRequestData) -> io.ktor.client.engine.mock.MockRequestHandleScope.() -> io.ktor.client.request.HttpResponseData): Nothing = TODO()

    @Test fun getCommands_parses_command_array() = runTest {
        val body = """
          [{"id":1,"name":"SeriesSearch","status":"started","started":"2026-06-14T01:39:15Z"},
           {"id":2,"name":"RssSync","status":"queued"}]
        """.trimIndent()
        val engine = MockEngine { req ->
            assertEquals("/api/v3/command", req.url.encodedPath)
            respond(ByteReadChannel(body), HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json() } }
        val sonarr = SonarrClient("http://sonarr:8989", "k", http)

        val arr = sonarr.getCommands()

        assertEquals(2, arr.size)
        assertEquals("SeriesSearch", arr[0].jsonObject["name"]!!.jsonPrimitive.contentOrNull)
        assertEquals("started", arr[0].jsonObject["status"]!!.jsonPrimitive.contentOrNull)
    }

    @Test fun restartApp_posts_to_restart_endpoint() = runTest {
        var hitPath: String? = null
        val engine = MockEngine { req ->
            hitPath = req.url.encodedPath
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json() } }
        val sonarr = SonarrClient("http://sonarr:8989", "k", http)

        sonarr.restartApp()

        assertEquals("/api/v3/system/restart", hitPath)
    }
}
```

> Delete the stray `private fun client(...)` line before running — it was a scratch artifact. Final test should not contain it.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.clients.SonarrCommandsTest"`
Expected: FAIL — `Unresolved reference 'getCommands'` / `restartApp`.

- [ ] **Step 3: Add the two methods to `SonarrClient`** (place beside `getQueue`, before the private helpers)

```kotlin
    /** All Sonarr commands (queued/started/completed). Used by the wedge watchdog. */
    open suspend fun getCommands(): JsonArray =
        get("/api/v3/command").jsonArray

    /** Restart the Sonarr application process. Fire-and-forget: Sonarr drops the
     *  connection as it restarts, so we intentionally don't parse the response. */
    open suspend fun restartApp() {
        http.post("$root/api/v3/system/restart") {
            header("X-Api-Key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { })
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.clients.SonarrCommandsTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/clients/Sonarr.kt \
        prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/clients/SonarrCommandsTest.kt
git commit -m "feat(sonarr): getCommands + restartApp client methods"
```

---

### Task 2: Command model + parse + stuck filter (pure)

**Files:**
- Create: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/orchestration/SonarrWatchdogModel.kt`
- Test: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/orchestration/SonarrStuckFilterTest.kt` (create)

- [ ] **Step 1: Write the failing test**

```kotlin
package org.yoshiz.app.prioritarr.backend.orchestration

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class SonarrStuckFilterTest {
    private val now = Instant.parse("2026-06-14T10:00:00Z")

    private fun cmds() = buildJsonArray {
        addJsonObject { put("id", 1); put("name", "SeriesSearch"); put("status", "started"); put("started", "2026-06-14T09:00:00Z") } // 60m old
        addJsonObject { put("id", 2); put("name", "RssSync"); put("status", "started"); put("started", "2026-06-14T09:55:00Z") }     // 5m old
        addJsonObject { put("id", 3); put("name", "Backup"); put("status", "queued") }                                                 // no started
        addJsonObject { put("id", 4); put("name", "RefreshSeries"); put("status", "completed"); put("started", "2026-06-14T08:00:00Z") } // not started
    }

    @Test fun parses_started_timestamps() {
        val parsed = parseCommands(cmds())
        assertEquals(4, parsed.size)
        assertEquals(Instant.parse("2026-06-14T09:00:00Z"), parsed[0].started)
        assertEquals(null, parsed[2].started)
    }

    @Test fun only_started_commands_older_than_stall_are_stuck() {
        val stuck = stuckCommands(parseCommands(cmds()), now, stallMinutes = 30)
        assertEquals(listOf(1L), stuck.map { it.id })  // only #1 (60m, started); #2 too young; #3/#4 wrong status
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.orchestration.SonarrStuckFilterTest"`
Expected: FAIL — `Unresolved reference 'parseCommands'`.

- [ ] **Step 3: Create the model + pure helpers**

```kotlin
package org.yoshiz.app.prioritarr.backend.orchestration

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant

/** A Sonarr command as reported by GET /api/v3/command. */
data class SonarrCommand(
    val id: Long,
    val name: String,
    val status: String,
    val started: Instant?,
)

/** Parse the raw /api/v3/command array. Missing/blank `started` -> null. */
fun parseCommands(arr: JsonArray): List<SonarrCommand> = arr.mapNotNull { el ->
    val o = el.jsonObject
    val id = o["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
    val name = o["name"]?.jsonPrimitive?.contentOrNull ?: ""
    val status = o["status"]?.jsonPrimitive?.contentOrNull ?: ""
    val started = o["started"]?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Instant.parse(it) }.getOrNull() }
    SonarrCommand(id, name, status, started)
}

/** Commands that are actively running and have been for longer than [stallMinutes]. */
fun stuckCommands(commands: List<SonarrCommand>, now: Instant, stallMinutes: Int): List<SonarrCommand> =
    commands.filter { c ->
        c.status == "started" && c.started != null &&
            now.isAfter(c.started.plusSeconds(stallMinutes * 60L))
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.orchestration.SonarrStuckFilterTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/orchestration/SonarrWatchdogModel.kt \
        prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/orchestration/SonarrStuckFilterTest.kt
git commit -m "feat(watchdog): SonarrCommand model + stuck-command filter"
```

---

### Task 3: Watchdog decision state-machine (pure)

**Files:**
- Create: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/orchestration/SonarrWatchdogDecision.kt`
- Test: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/orchestration/SonarrWatchdogDecisionTest.kt` (create)

- [ ] **Step 1: Write the failing test**

```kotlin
package org.yoshiz.app.prioritarr.backend.orchestration

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class SonarrWatchdogDecisionTest {
    private val cfg = WatchdogConfig(stallMinutes = 30, confirmChecks = 2, graceMinutes = 10, cooldownMinutes = 120)
    private val t0 = Instant.parse("2026-06-14T10:00:00Z")
    private fun at(min: Long) = t0.plusSeconds(min * 60)

    @Test fun healthy_resets_all_state() {
        val dirty = WatchdogState(confirmStreak = 2, appRestartCount = 2, lastActionAt = t0)
        val d = decideWatchdog(wedged = false, containerAvailable = true, state = dirty, now = t0, cfg = cfg)
        assertEquals(WatchdogAction.NONE, d.action)
        assertEquals(WatchdogState(), d.state)
    }

    @Test fun first_wedge_tick_confirms_only() {
        val d = decideWatchdog(true, true, WatchdogState(), t0, cfg)
        assertEquals(WatchdogAction.NONE, d.action)
        assertEquals(1, d.state.confirmStreak)
    }

    @Test fun second_wedge_tick_fires_first_app_restart() {
        val s1 = decideWatchdog(true, true, WatchdogState(), t0, cfg).state
        val d = decideWatchdog(true, true, s1, at(5), cfg)
        assertEquals(WatchdogAction.APP_RESTART, d.action)
        assertEquals(1, d.state.appRestartCount)
        assertEquals(at(5), d.state.lastActionAt)
    }

    @Test fun grace_blocks_a_second_restart_too_soon() {
        var s = WatchdogState(confirmStreak = 2, appRestartCount = 1, lastActionAt = t0)
        val d = decideWatchdog(true, true, s, at(5), cfg) // only 5m < 10m grace
        assertEquals(WatchdogAction.NONE, d.action)
        assertEquals(1, d.state.appRestartCount)
    }

    @Test fun escalates_app_x3_then_container() {
        var s = WatchdogState(confirmStreak = 2)
        val actions = mutableListOf<WatchdogAction>()
        var clock = 0L
        repeat(4) {
            val d = decideWatchdog(true, true, s, at(clock), cfg)
            actions += d.action
            s = d.state
            clock += 15 // beyond grace each time
        }
        assertEquals(
            listOf(WatchdogAction.APP_RESTART, WatchdogAction.APP_RESTART, WatchdogAction.APP_RESTART, WatchdogAction.CONTAINER_RESTART),
            actions,
        )
    }

    @Test fun container_unavailable_goes_straight_to_exhausted_after_three_app_restarts() {
        val s = WatchdogState(confirmStreak = 2, appRestartCount = 3)
        val d = decideWatchdog(true, containerAvailable = false, state = s, now = at(30), cfg = cfg)
        assertEquals(WatchdogAction.EXHAUSTED, d.action)
        assertEquals(at(30).plusSeconds(120 * 60), d.state.cooldownUntil)
    }

    @Test fun exhausted_then_cooldown_blocks_further_action() {
        val cooled = WatchdogState(confirmStreak = 2, cooldownUntil = at(120))
        val d = decideWatchdog(true, true, cooled, at(60), cfg) // still inside cooldown
        assertEquals(WatchdogAction.NONE, d.action)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.orchestration.SonarrWatchdogDecisionTest"`
Expected: FAIL — `Unresolved reference 'decideWatchdog'`.

- [ ] **Step 3: Implement the decision**

```kotlin
package org.yoshiz.app.prioritarr.backend.orchestration

import java.time.Instant

/** Tunables for the watchdog decision. */
data class WatchdogConfig(
    val stallMinutes: Int,
    val confirmChecks: Int,
    val graceMinutes: Int,
    val cooldownMinutes: Int,
)

/** State carried between ticks by the reconciler. */
data class WatchdogState(
    val confirmStreak: Int = 0,
    val appRestartCount: Int = 0,
    val containerRestartDone: Boolean = false,
    val lastActionAt: Instant? = null,
    val cooldownUntil: Instant? = null,
)

enum class WatchdogAction { NONE, APP_RESTART, CONTAINER_RESTART, EXHAUSTED }

data class WatchdogDecision(val action: WatchdogAction, val state: WatchdogState, val reason: String)

/**
 * Pure escalation decision. Pause-fast / resume-slow analogue: act only on a
 * *confirmed* wedge, escalate app-restart x3 then container-restart, then hold
 * for a cooldown. See docs/specs/2026-06-14-sonarr-watchdog-design.md.
 */
fun decideWatchdog(
    wedged: Boolean,
    containerAvailable: Boolean,
    state: WatchdogState,
    now: Instant,
    cfg: WatchdogConfig,
): WatchdogDecision {
    if (!wedged) {
        return WatchdogDecision(WatchdogAction.NONE, WatchdogState(), "healthy")
    }
    val confirmStreak = minOf(state.confirmStreak + 1, cfg.confirmChecks)
    val s = state.copy(confirmStreak = confirmStreak)

    if (s.cooldownUntil != null && now.isBefore(s.cooldownUntil)) {
        return WatchdogDecision(WatchdogAction.NONE, s, "ladder exhausted; cooldown until ${s.cooldownUntil}")
    }
    if (confirmStreak < cfg.confirmChecks) {
        return WatchdogDecision(WatchdogAction.NONE, s, "wedge seen; confirming (streak=$confirmStreak)")
    }
    if (s.lastActionAt != null && now.isBefore(s.lastActionAt.plusSeconds(cfg.graceMinutes * 60L))) {
        return WatchdogDecision(WatchdogAction.NONE, s, "waiting for last restart to take effect")
    }
    if (s.appRestartCount < 3) {
        val n = s.appRestartCount + 1
        return WatchdogDecision(
            WatchdogAction.APP_RESTART,
            s.copy(appRestartCount = n, lastActionAt = now),
            "app-restart #$n",
        )
    }
    if (!s.containerRestartDone && containerAvailable) {
        return WatchdogDecision(
            WatchdogAction.CONTAINER_RESTART,
            s.copy(containerRestartDone = true, lastActionAt = now),
            "container-restart fallback",
        )
    }
    // exhausted: 3 app-restarts + container (or container unavailable), still wedged
    val cooldownUntil = now.plusSeconds(cfg.cooldownMinutes * 60L)
    val reset = s.copy(appRestartCount = 0, containerRestartDone = false, lastActionAt = null, cooldownUntil = cooldownUntil)
    val why = if (containerAvailable) "ladder exhausted" else "app-restarts exhausted; no container fallback configured"
    return WatchdogDecision(WatchdogAction.EXHAUSTED, reset, "$why; cooldown until $cooldownUntil")
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.orchestration.SonarrWatchdogDecisionTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/orchestration/SonarrWatchdogDecision.kt \
        prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/orchestration/SonarrWatchdogDecisionTest.kt
git commit -m "feat(watchdog): pure escalation decision state-machine"
```

---

### Task 4: SonarrWatchdog reconciler (stateful, seams)

**Files:**
- Create: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/orchestration/SonarrWatchdog.kt`
- Test: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/orchestration/SonarrWatchdogReconcilerTest.kt` (create)

- [ ] **Step 1: Write the failing test**

```kotlin
package org.yoshiz.app.prioritarr.backend.orchestration

import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SonarrWatchdogReconcilerTest {
    private val cfg = WatchdogConfig(30, confirmChecks = 2, graceMinutes = 10, cooldownMinutes = 120)
    private val t0 = Instant.parse("2026-06-14T10:00:00Z")
    private fun stuck(id: Long) = SonarrCommand(id, "SeriesSearch", "started", t0.minusSeconds(3600))

    @Test fun escalates_app_app_app_container_across_ticks() = runTest {
        var clock = t0
        val wedged = ArrayDeque(listOf(true, true, true, true, true)) // always wedged
        val appCalls = mutableListOf<Instant>()
        val containerCalls = mutableListOf<Instant>()
        val w = SonarrWatchdog(
            getStuckCommands = { if (wedged.removeFirst()) listOf(stuck(1)) else emptyList() },
            appRestart = { appCalls += clock },
            containerRestart = { containerCalls += clock },
            now = { clock },
            cfg = cfg,
            dryRun = { false },
        )
        // tick1 confirm; tick2..4 app x3; tick5 container. Advance 15m between to clear grace.
        repeat(5) { w.reconcile(); clock = clock.plusSeconds(15 * 60) }
        assertEquals(3, appCalls.size)
        assertEquals(1, containerCalls.size)
    }

    @Test fun dry_run_invokes_no_seam_but_advances() = runTest {
        var clock = t0
        var appCalled = false
        val w = SonarrWatchdog(
            getStuckCommands = { listOf(stuck(1)) },
            appRestart = { appCalled = true },
            containerRestart = null,
            now = { clock },
            cfg = cfg,
            dryRun = { true },
        )
        w.reconcile(); clock = clock.plusSeconds(5 * 60)
        val out = w.reconcile() // confirmed -> would app-restart
        assertEquals(false, appCalled)
        assertTrue(out.summary!!.contains("app-restart"))
    }

    @Test fun unreachable_sonarr_is_noop_no_state_change() = runTest {
        var clock = t0
        val appCalls = mutableListOf<Instant>()
        val w = SonarrWatchdog(
            getStuckCommands = { throw RuntimeException("connection refused") },
            appRestart = { appCalls += clock },
            containerRestart = null,
            now = { clock },
            cfg = cfg,
            dryRun = { false },
        )
        val out = w.reconcile()
        assertTrue(out.noop)
        assertEquals(0, appCalls.size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.orchestration.SonarrWatchdogReconcilerTest"`
Expected: FAIL — `Unresolved reference 'SonarrWatchdog'`.

- [ ] **Step 3: Implement the reconciler**

```kotlin
package org.yoshiz.app.prioritarr.backend.orchestration

import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome
import java.time.Instant

private val logger = LoggerFactory.getLogger("SonarrWatchdog")

/**
 * Detects a wedged Sonarr command executor and recovers it via an escalating
 * restart ladder. Stateful across ticks (carries the escalation counters);
 * construct once and call [reconcile] each tick. Dependencies are narrow
 * suspend seams so the loop is unit-testable without HTTP.
 *
 * @param containerRestart null when no docker proxy is configured — the ladder
 *        then stops after the app-restarts (graceful degradation).
 */
class SonarrWatchdog(
    private val getStuckCommands: suspend () -> List<SonarrCommand>,
    private val appRestart: suspend () -> Unit,
    private val containerRestart: (suspend () -> Unit)?,
    private val now: () -> Instant,
    private val cfg: WatchdogConfig,
    private val dryRun: () -> Boolean,
) {
    private var state = WatchdogState()

    suspend fun reconcile(): JobOutcome {
        val stuck = try {
            getStuckCommands()
        } catch (e: Exception) {
            // Likely Sonarr mid-restart / briefly unreachable — skip without touching state.
            logger.warn("sonarr-watchdog: command poll failed, skipping: {}", e.message)
            return JobOutcome(summary = "sonarr unreachable: ${e.message}", noop = true)
        }

        val decision = decideWatchdog(
            wedged = stuck.isNotEmpty(),
            containerAvailable = containerRestart != null,
            state = state,
            now = now(),
            cfg = cfg,
        )
        state = decision.state

        return when (decision.action) {
            WatchdogAction.NONE -> JobOutcome(summary = decision.reason, noop = true)

            WatchdogAction.APP_RESTART -> {
                val ids = stuck.joinToString(",") { it.id.toString() }
                if (dryRun()) {
                    logger.info("sonarr-watchdog: would {} (stuck commands={}) [DRY RUN]", decision.reason, ids)
                } else {
                    logger.warn("sonarr-watchdog: {} (stuck commands={})", decision.reason, ids)
                    appRestart()
                }
                JobOutcome(summary = decision.reason)
            }

            WatchdogAction.CONTAINER_RESTART -> {
                if (dryRun()) {
                    logger.info("sonarr-watchdog: would container-restart sonarr [DRY RUN]")
                } else {
                    logger.warn("sonarr-watchdog: {}", decision.reason)
                    containerRestart!!()
                }
                JobOutcome(summary = decision.reason)
            }

            WatchdogAction.EXHAUSTED -> {
                logger.error("sonarr-watchdog: {}", decision.reason)
                JobOutcome(summary = decision.reason)
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.orchestration.SonarrWatchdogReconcilerTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/orchestration/SonarrWatchdog.kt \
        prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/orchestration/SonarrWatchdogReconcilerTest.kt
git commit -m "feat(watchdog): stateful SonarrWatchdog reconciler"
```

---

### Task 5: DockerRestartClient

**Files:**
- Create: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/clients/DockerRestart.kt`
- Test: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/clients/DockerRestartClientTest.kt` (create)

- [ ] **Step 1: Write the failing test**

```kotlin
package org.yoshiz.app.prioritarr.backend.clients

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DockerRestartClientTest {
    @Test fun restartContainer_posts_to_docker_restart_endpoint() = runTest {
        var path: String? = null
        var method: HttpMethod? = null
        val engine = MockEngine { req ->
            path = req.url.encodedPath
            method = req.method
            respond("", HttpStatusCode.NoContent)
        }
        val client = DockerRestartClient("http://dockerproxy:2375", HttpClient(engine))

        client.restartContainer("sonarr")

        assertEquals(HttpMethod.Post, method)
        assertEquals("/containers/sonarr/restart", path)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.clients.DockerRestartClientTest"`
Expected: FAIL — `Unresolved reference 'DockerRestartClient'`.

- [ ] **Step 3: Implement**

```kotlin
package org.yoshiz.app.prioritarr.backend.clients

import io.ktor.client.HttpClient
import io.ktor.client.request.post

/**
 * Restarts containers through a scoped docker-socket-proxy (HTTP Docker API).
 * Only ever used for the Sonarr watchdog container-restart fallback.
 */
class DockerRestartClient(
    baseUrl: String,
    private val http: HttpClient,
) {
    private val root: String = baseUrl.trimEnd('/')

    /** POST /containers/{name}/restart — Docker Engine API restart. */
    suspend fun restartContainer(name: String) {
        http.post("$root/containers/$name/restart")
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.clients.DockerRestartClientTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/clients/DockerRestart.kt \
        prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/clients/DockerRestartClientTest.kt
git commit -m "feat(docker): DockerRestartClient via socket proxy"
```

---

### Task 6: Settings plumbing

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/config/Settings.kt`
- Test: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/config/SonarrWatchdogSettingsTest.kt` (create)

Mirror the `tdarrPauseEnabled` / `tdarrPauseMinutes` plumbing for these fields:
`sonarrWatchdogEnabled` (Settings, bool, false), `dockerProxyUrl` (Settings, String?, null),
`sonarrContainerName` (Settings, String, "sonarr"); and in `Intervals`:
`sonarrWatchdogIntervalMinutes` (5), `sonarrWatchdogStallMinutes` (30),
`sonarrWatchdogRestartGraceMinutes` (10), `sonarrWatchdogCooldownMinutes` (120).

- [ ] **Step 1: Write the failing test**

```kotlin
package org.yoshiz.app.prioritarr.backend.config

import kotlin.test.Test
import kotlin.test.assertEquals

class SonarrWatchdogSettingsTest {
    @Test fun defaults_are_safe_and_disabled() {
        val s = Settings(sonarrUrl = "http://sonarr", sonarrApiKey = "k")
        assertEquals(false, s.sonarrWatchdogEnabled)
        assertEquals("sonarr", s.sonarrContainerName)
        assertEquals(null, s.dockerProxyUrl)
        assertEquals(5, s.intervals.sonarrWatchdogIntervalMinutes)
        assertEquals(30, s.intervals.sonarrWatchdogStallMinutes)
        assertEquals(10, s.intervals.sonarrWatchdogRestartGraceMinutes)
        assertEquals(120, s.intervals.sonarrWatchdogCooldownMinutes)
    }

    @Test fun override_applies_enable_and_threshold() {
        val base = Settings(sonarrUrl = "http://sonarr", sonarrApiKey = "k")
        val merged = applySettingsOverride(
            base,
            EditableSettings(sonarrWatchdogEnabled = true, sonarrWatchdogStallMinutes = 45),
        )
        assertEquals(true, merged.sonarrWatchdogEnabled)
        assertEquals(45, merged.intervals.sonarrWatchdogStallMinutes)
    }
}
```

> If the `Settings(...)` constructor requires more non-default args than `sonarrUrl`/`sonarrApiKey`, fill them from the existing defaults — check the data class. Adjust the test constructor call to match the real required params (do NOT add new required params).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.config.SonarrWatchdogSettingsTest"`
Expected: FAIL — unresolved `sonarrWatchdogEnabled` (and the EditableSettings args).

- [ ] **Step 3: Add fields in the five Settings.kt locations**

3a. In `data class Intervals(...)` add:
```kotlin
    val sonarrWatchdogIntervalMinutes: Int = 5,
    val sonarrWatchdogStallMinutes: Int = 30,
    val sonarrWatchdogRestartGraceMinutes: Int = 10,
    val sonarrWatchdogCooldownMinutes: Int = 120,
```

3b. In `data class Settings(...)` add (near `tdarrPauseEnabled`):
```kotlin
    val sonarrWatchdogEnabled: Boolean = false,
    val dockerProxyUrl: String? = null,
    val sonarrContainerName: String = "sonarr",
```

3c. In `data class EditableSettings(...)` add:
```kotlin
    val sonarrWatchdogEnabled: Boolean? = null,
    val sonarrWatchdogIntervalMinutes: Int? = null,
    val sonarrWatchdogStallMinutes: Int? = null,
    val sonarrWatchdogRestartGraceMinutes: Int? = null,
    val sonarrWatchdogCooldownMinutes: Int? = null,
```

3d. In `applySettingsOverride(...)` add to the `base.copy(...)` body:
```kotlin
    sonarrWatchdogEnabled = override.sonarrWatchdogEnabled ?: base.sonarrWatchdogEnabled,
```
and inside the `intervals = base.intervals.copy(...)` block:
```kotlin
    sonarrWatchdogIntervalMinutes = override.sonarrWatchdogIntervalMinutes ?: base.intervals.sonarrWatchdogIntervalMinutes,
    sonarrWatchdogStallMinutes = override.sonarrWatchdogStallMinutes ?: base.intervals.sonarrWatchdogStallMinutes,
    sonarrWatchdogRestartGraceMinutes = override.sonarrWatchdogRestartGraceMinutes ?: base.intervals.sonarrWatchdogRestartGraceMinutes,
    sonarrWatchdogCooldownMinutes = override.sonarrWatchdogCooldownMinutes ?: base.intervals.sonarrWatchdogCooldownMinutes,
```

3e. In the env loader (`loadSettingsFrom`, where `tdarrPauseEnabled`/`tdarrUrl` are read) add:
```kotlin
    sonarrWatchdogEnabled = (env("SONARR_WATCHDOG_ENABLED", "false") ?: "false").lowercase() in TRUTHY,
    dockerProxyUrl = env("DOCKER_PROXY_URL")?.takeIf { it.isNotBlank() },
    sonarrContainerName = env("SONARR_CONTAINER_NAME", "sonarr") ?: "sonarr",
```
and where `intervals = intervals.copy(...)` reads env ints (mirror `tdarrPauseMinutes`):
```kotlin
    sonarrWatchdogIntervalMinutes = env("SONARR_WATCHDOG_INTERVAL_MINUTES")?.toIntOrNull() ?: intervals.sonarrWatchdogIntervalMinutes,
    sonarrWatchdogStallMinutes = env("SONARR_WATCHDOG_STALL_MINUTES")?.toIntOrNull() ?: intervals.sonarrWatchdogStallMinutes,
    sonarrWatchdogRestartGraceMinutes = env("SONARR_WATCHDOG_GRACE_MINUTES")?.toIntOrNull() ?: intervals.sonarrWatchdogRestartGraceMinutes,
    sonarrWatchdogCooldownMinutes = env("SONARR_WATCHDOG_COOLDOWN_MINUTES")?.toIntOrNull() ?: intervals.sonarrWatchdogCooldownMinutes,
```

> Match the exact `env(...)` helper signature already in the file (with/without default arg). The `TRUTHY` set and `env` helper already exist (used by `tdarrPauseEnabled`). If env ints are parsed elsewhere via `o.num`, mirror that instead.

3f. In the YAML loader (`(root["intervals"] as? Map<*, *>)?.let { o -> intervals = intervals.copy(...)`) add:
```kotlin
    sonarrWatchdogIntervalMinutes = o.num("sonarr_watchdog_interval_minutes") { it.toInt() } ?: intervals.sonarrWatchdogIntervalMinutes,
    sonarrWatchdogStallMinutes = o.num("sonarr_watchdog_stall_minutes") { it.toInt() } ?: intervals.sonarrWatchdogStallMinutes,
    sonarrWatchdogRestartGraceMinutes = o.num("sonarr_watchdog_grace_minutes") { it.toInt() } ?: intervals.sonarrWatchdogRestartGraceMinutes,
    sonarrWatchdogCooldownMinutes = o.num("sonarr_watchdog_cooldown_minutes") { it.toInt() } ?: intervals.sonarrWatchdogCooldownMinutes,
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.config.SonarrWatchdogSettingsTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/config/Settings.kt \
        prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/config/SonarrWatchdogSettingsTest.kt
git commit -m "feat(settings): sonarr watchdog config (enable, thresholds, docker proxy)"
```

---

### Task 7: Wire into Constants + Main.kt

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/Constants.kt`
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/Main.kt`

No new unit test (wiring); verification is a green full build + suite.

- [ ] **Step 1: Add the JobId** in `Constants.kt` `object JobId`:
```kotlin
    const val SONARR_WATCHDOG = "sonarr-watchdog"
```

- [ ] **Step 2: Construct clients + watchdog in Main.kt** (near the `tdarrPlexPause` construction, before the `Scheduler(...)`):
```kotlin
    // Sonarr command-queue watchdog: detect wedged "started" commands and
    // recover via escalating restarts. Stateful, so built once. Container
    // fallback only when a docker proxy URL is configured.
    val dockerRestartClient: org.yoshiz.app.prioritarr.backend.clients.DockerRestartClient? =
        settings.dockerProxyUrl?.let {
            org.yoshiz.app.prioritarr.backend.clients.DockerRestartClient(it, tdarrHttp)
        }
    val sonarrWatchdog: org.yoshiz.app.prioritarr.backend.orchestration.SonarrWatchdog? =
        if (sonarrClient != null) {
            org.yoshiz.app.prioritarr.backend.orchestration.SonarrWatchdog(
                getStuckCommands = {
                    val s = liveSettings(db, settings)
                    org.yoshiz.app.prioritarr.backend.orchestration.stuckCommands(
                        org.yoshiz.app.prioritarr.backend.orchestration.parseCommands(sonarrClient.getCommands()),
                        java.time.Instant.now(),
                        s.intervals.sonarrWatchdogStallMinutes,
                    )
                },
                appRestart = { sonarrClient.restartApp() },
                containerRestart = dockerRestartClient?.let { dc -> { dc.restartContainer(liveSettings(db, settings).sonarrContainerName) } },
                now = { java.time.Instant.now() },
                cfg = org.yoshiz.app.prioritarr.backend.orchestration.WatchdogConfig(
                    stallMinutes = settings.intervals.sonarrWatchdogStallMinutes,
                    confirmChecks = 2,
                    graceMinutes = settings.intervals.sonarrWatchdogRestartGraceMinutes,
                    cooldownMinutes = settings.intervals.sonarrWatchdogCooldownMinutes,
                ),
                dryRun = { liveSettings(db, settings).dryRun },
            )
        } else {
            null
        }
```

> `tdarrHttp` is the existing short-timeout ktor client built near the Tdarr client; reuse it for the docker proxy (a hung proxy must not stall a tick). If its name differs, use whichever short-timeout `HttpClient` the Tdarr client uses.

- [ ] **Step 3: Register the job** in the scheduler `buildList { ... }` (after the `TDARR_PLEX_PAUSE` job):
```kotlin
            add(org.yoshiz.app.prioritarr.backend.scheduler.JobDefinition(
                id = JobId.SONARR_WATCHDOG,
                cadenceMinutes = { liveSettings(db, settings).intervals.sonarrWatchdogIntervalMinutes.toLong() },
                prerequisites = {
                    liveSettings(db, settings).sonarrWatchdogEnabled && sonarrWatchdog != null
                },
                weight = org.yoshiz.app.prioritarr.backend.scheduler.JobWeight.LIGHT,
                run = { sonarrWatchdog!!.reconcile() },
            ))
```

- [ ] **Step 4: Build + full suite**

Run: `./gradlew :backend:test`
Expected: BUILD SUCCESSFUL; all tests pass (existing 225 + new ~17).

- [ ] **Step 5: Commit**

```bash
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/Constants.kt \
        prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/Main.kt
git commit -m "feat(watchdog): register sonarr-watchdog job"
```

---

### Task 8: Compose infra — docker-socket-proxy

**Files:**
- Modify: `D:\docker\media-stack-v3.yml`

- [ ] **Step 1: Add the `dockerproxy` service** (under the INFRA section, near autoheal):
```yaml
#>> docker-socket-proxy - scoped Docker API for prioritarr's Sonarr watchdog
#   Grants ONLY container operations (restart) over HTTP on the socket_proxy
#   net, so prioritarr never touches the raw socket. Read-only sock mount.
  dockerproxy:
    image: tecnativa/docker-socket-proxy:latest
    container_name: dockerproxy
    hostname: dockerproxy
    restart: unless-stopped
    environment:
      CONTAINERS: 1      # allow /containers/* (needed for restart)
      POST: 1            # allow POST (restart is a POST); default-deny otherwise
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock:ro
    networks:
      - socket_proxy
```

- [ ] **Step 2: Attach prioritarr to `socket_proxy`** — in the `prioritarr` service `networks:` list add `- socket_proxy` (keep its existing networks). If prioritarr has no explicit `networks:` block, add one listing its current default network plus `socket_proxy`. Verify the `socket_proxy` network is defined at the top of the file (it is).

- [ ] **Step 3: Add prioritarr env** — in the `prioritarr` service `environment:` block:
```yaml
      # Sonarr watchdog: detect wedged command queue, restart Sonarr.
      PRIORITARR_SONARR_WATCHDOG_ENABLED: "true"
      PRIORITARR_DOCKER_PROXY_URL: http://dockerproxy:2375
      PRIORITARR_SONARR_CONTAINER_NAME: sonarr
```

- [ ] **Step 4: Validate compose**

Run: `docker compose -f media-stack-v3.yml config >NUL` (PowerShell: `docker compose -f media-stack-v3.yml config | Out-Null`)
Expected: no error (valid compose). 

- [ ] **Step 5: Commit** (in the D:\docker repo)

```bash
cd /d/docker && git add media-stack-v3.yml
git commit -m "feat(stack): add scoped docker-socket-proxy + enable prioritarr sonarr-watchdog"
```

---

### Task 9: Build, deploy, enable live, verify

**Files:** none (deploy)

- [ ] **Step 1: Build prioritarr image**

Run (from `D:\git\prioritarr`): `docker build -f prioritarr/Dockerfile -t ghcr.io/cquemin/prioritarr:latest .`
Expected: exit 0, image tagged.

- [ ] **Step 2: Start the proxy + recreate prioritarr** (from `D:\docker`)

```bash
docker compose -f media-stack-v3.yml up -d dockerproxy
docker compose -f media-stack-v3.yml up -d --no-deps prioritarr
```
Expected: `dockerproxy` created/running; `prioritarr` recreated; both healthy.

- [ ] **Step 3: Verify the proxy is reachable from prioritarr and scoped**

Run: `docker exec prioritarr sh -c "wget -qO- http://dockerproxy:2375/version || curl -s http://dockerproxy:2375/version"`
Expected: JSON Docker version (proxy reachable). A GET to `/containers/json` may be denied (CONTAINERS gates it) — that's fine; restart is POST.

- [ ] **Step 4: Confirm the watchdog job is live (enabled, running, healthy, not acting)**

Run: `docker logs prioritarr --since 6m | grep -i "sonarr-watchdog"` (no action lines expected while Sonarr is healthy — the healthy path is a silent noop). Then confirm the job is registered & enabled by checking it has a recorded run via the jobs API or by temporarily lowering cadence. Minimal check: `docker exec prioritarr sh -c "wget -qO- http://localhost:8000/health"` returns healthy and no errors in startup logs.

- [ ] **Step 5: Live-fire validation (optional but recommended)**

To prove end-to-end without waiting for a real wedge, temporarily set `PRIORITARR_DRY_RUN=true` and `PRIORITARR_SONARR_WATCHDOG_STALL_MINUTES=0` (every started command counts as "stuck"), recreate prioritarr, trigger a Sonarr search so a `started` command exists, and confirm the logs show the confirm→`would app-restart [DRY RUN]` escalation **without** restarting Sonarr. Then revert both env vars (stall back to 30, DRY_RUN back to its prior value) and recreate prioritarr so it runs live.

- [ ] **Step 6: Final state**

Confirm `media-stack-v3.yml` has `PRIORITARR_SONARR_WATCHDOG_ENABLED: "true"`, stall back at 30, DRY_RUN at its production value, and prioritarr running the new image. Report the deployed image SHA and that the watchdog is enabled + live.

---

## Self-Review

- **Spec coverage:** detection (T2), escalation ladder incl. confirm/grace/cooldown/exhausted (T3), app-restart (T1), container-restart via proxy (T5,T8), reconciler + dry-run + unreachable (T4), settings incl. graceful degradation when proxy absent (T6, T4 container null), job registration (T7), infra (T8), rollout test-first then live (T9). All covered.
- **Placeholders:** none — every code step is complete. (Two `>` notes ask the implementer to match exact existing helper signatures in `Settings.kt`/`Main.kt`; the surrounding code is fully specified.)
- **Type consistency:** `WatchdogConfig(stallMinutes, confirmChecks, graceMinutes, cooldownMinutes)`, `WatchdogState`, `WatchdogAction{NONE,APP_RESTART,CONTAINER_RESTART,EXHAUSTED}`, `decideWatchdog(...)`, `SonarrCommand(id,name,status,started)`, `parseCommands`, `stuckCommands`, `SonarrWatchdog(getStuckCommands,appRestart,containerRestart,now,cfg,dryRun)`, `SonarrClient.getCommands()/restartApp()`, `DockerRestartClient.restartContainer(name)` — names consistent across tasks.
