# Search-Flood Control Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Throttle prioritarr's backfill search flood (congestion gate) and let it cancel in-flight backfill searches so time-sensitive P1/P2 episode searches run first.

**Architecture:** A pure helper layer over Sonarr's command queue (reusing the watchdog's `SonarrCommand`/`parseCommands`), a seam-injected `SearchQueueControl` (`isCongested` / `cancelBackfill`), a congestion gate that skips backfill Pass A2/B + cutoff when Sonarr already has ≥N searches pending, and a preemption step in the P1-fast sweep that cancels backfill (`SeriesSearch`/`SeasonSearch`/`CutoffUnmetSearch` — never `EpisodeSearch`) before firing P1/P2.

**Tech Stack:** Kotlin, ktor client, JUnit5 + `kotlin.test` + `kotlinx-coroutines-test`, Gradle. Spec: `docs/specs/2026-06-14-search-flood-control-design.md`.

**Build/test** (from `D:\git\prioritarr\prioritarr`): `./gradlew :backend:test --tests "<FQCN>"`; full: `./gradlew :backend:test`.

---

### Task 1: SonarrClient.cancelCommand

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/clients/Sonarr.kt`
- Test: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/clients/SonarrCancelCommandTest.kt` (create)

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

class SonarrCancelCommandTest {
    @Test fun cancelCommand_deletes_command_endpoint() = runTest {
        var path: String? = null
        var method: HttpMethod? = null
        val engine = MockEngine { req ->
            path = req.url.encodedPath; method = req.method
            respond("{}", HttpStatusCode.OK)
        }
        val sonarr = SonarrClient("http://sonarr:8989", "k", HttpClient(engine))

        sonarr.cancelCommand(42L)

        assertEquals(HttpMethod.Delete, method)
        assertEquals("/api/v3/command/42", path)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.clients.SonarrCancelCommandTest"`
Expected: FAIL — `Unresolved reference 'cancelCommand'`.

- [ ] **Step 3: Add the method** beside `getCommands`/`restartApp` (before the private helpers)

```kotlin
    /** Cancel a Sonarr command. Fire-and-forget; response body unused. */
    open suspend fun cancelCommand(id: Long) {
        http.delete("$root/api/v3/command/$id") {
            header("X-Api-Key", apiKey)
        }
    }
```

(`delete` from `io.ktor.client.request.delete` is already imported in this file; `header` too.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.clients.SonarrCancelCommandTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/clients/Sonarr.kt \
        prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/clients/SonarrCancelCommandTest.kt
git commit -m "feat(sonarr): cancelCommand (DELETE /api/v3/command/{id})"
```

---

### Task 2: Pure queue helpers

**Files:**
- Create: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/orchestration/SearchQueueControl.kt`
- Test: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/orchestration/SearchQueueHelpersTest.kt` (create)

Reuses `SonarrCommand` (already defined in `orchestration/SonarrWatchdogModel.kt`).

- [ ] **Step 1: Write the failing test**

```kotlin
package org.yoshiz.app.prioritarr.backend.orchestration

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchQueueHelpersTest {
    private fun cmd(id: Long, name: String, status: String) = SonarrCommand(id, name, status, null)

    private val sample = listOf(
        cmd(1, "SeriesSearch", "started"),
        cmd(2, "SeasonSearch", "queued"),
        cmd(3, "CutoffUnmetSearch", "queued"),
        cmd(4, "EpisodeSearch", "started"),   // P1/P2 — counts toward congestion, NOT cancellable
        cmd(5, "SeriesSearch", "completed"),  // not active
        cmd(6, "RefreshSeries", "started"),   // not a search
    )

    @Test fun pendingSearchCount_counts_active_searches_only() {
        // 1,2,3,4 active searches; 5 completed; 6 not a search
        assertEquals(4, pendingSearchCount(sample))
    }

    @Test fun cancellable_excludes_episodesearch_and_inactive() {
        // 1 (SeriesSearch started), 2 (SeasonSearch queued), 3 (CutoffUnmetSearch queued)
        assertEquals(listOf(1L, 2L, 3L), cancellableBackfillSearchIds(sample))
    }

    @Test fun cancellable_never_includes_a_queued_p1_episode_search() {
        val ids = cancellableBackfillSearchIds(listOf(cmd(9, "EpisodeSearch", "queued")))
        assertEquals(emptyList(), ids)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.orchestration.SearchQueueHelpersTest"`
Expected: FAIL — `Unresolved reference 'pendingSearchCount'`.

- [ ] **Step 3: Create the helpers** (start the new file with just these; the class is added in Task 3)

```kotlin
package org.yoshiz.app.prioritarr.backend.orchestration

/** Backfill search command types — cancellable. EpisodeSearch (P1/P2) is deliberately excluded. */
val BACKFILL_SEARCH_COMMANDS = setOf("SeriesSearch", "SeasonSearch", "CutoffUnmetSearch")

/** All search command types — counted for congestion. */
val ALL_SEARCH_COMMANDS = BACKFILL_SEARCH_COMMANDS + "EpisodeSearch"

private val ACTIVE_STATUSES = setOf("queued", "started")

/** Active (queued/started) search commands of any type — the congestion signal. */
fun pendingSearchCount(commands: List<SonarrCommand>): Int =
    commands.count { it.name in ALL_SEARCH_COMMANDS && it.status in ACTIVE_STATUSES }

/** Ids of active backfill searches (Series/Season/CutoffUnmet). Never an EpisodeSearch. */
fun cancellableBackfillSearchIds(commands: List<SonarrCommand>): List<Long> =
    commands.filter { it.name in BACKFILL_SEARCH_COMMANDS && it.status in ACTIVE_STATUSES }.map { it.id }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.orchestration.SearchQueueHelpersTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/orchestration/SearchQueueControl.kt \
        prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/orchestration/SearchQueueHelpersTest.kt
git commit -m "feat(search-control): pure queue helpers (count + cancellable ids)"
```

---

### Task 3: SearchQueueControl class

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/orchestration/SearchQueueControl.kt`
- Test: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/orchestration/SearchQueueControlTest.kt` (create)

- [ ] **Step 1: Write the failing test**

```kotlin
package org.yoshiz.app.prioritarr.backend.orchestration

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchQueueControlTest {
    private fun cmd(id: Long, name: String, status: String) = SonarrCommand(id, name, status, null)

    @Test fun isCongested_true_at_or_above_threshold() = runTest {
        val cmds = listOf(cmd(1, "SeriesSearch", "started"), cmd(2, "EpisodeSearch", "queued"), cmd(3, "SeasonSearch", "queued"))
        val c = SearchQueueControl(getCommands = { cmds }, cancelCommand = {}, threshold = { 3 }, dryRun = { false })
        assertEquals(true, c.isCongested())
    }

    @Test fun isCongested_false_below_threshold() = runTest {
        val cmds = listOf(cmd(1, "SeriesSearch", "started"))
        val c = SearchQueueControl(getCommands = { cmds }, cancelCommand = {}, threshold = { 3 }, dryRun = { false })
        assertEquals(false, c.isCongested())
    }

    @Test fun isCongested_false_on_error() = runTest {
        val c = SearchQueueControl(getCommands = { throw RuntimeException("down") }, cancelCommand = {}, threshold = { 1 }, dryRun = { false })
        assertEquals(false, c.isCongested())
    }

    @Test fun cancelBackfill_cancels_each_id_and_returns_count() = runTest {
        val cmds = listOf(cmd(1, "SeriesSearch", "started"), cmd(2, "CutoffUnmetSearch", "queued"), cmd(3, "EpisodeSearch", "started"))
        val cancelled = mutableListOf<Long>()
        val c = SearchQueueControl(getCommands = { cmds }, cancelCommand = { cancelled += it }, threshold = { 3 }, dryRun = { false })
        val n = c.cancelBackfill()
        assertEquals(2, n)
        assertEquals(listOf(1L, 2L), cancelled) // never the EpisodeSearch
    }

    @Test fun cancelBackfill_dry_run_cancels_nothing_but_returns_count() = runTest {
        val cmds = listOf(cmd(1, "SeriesSearch", "started"))
        val cancelled = mutableListOf<Long>()
        val c = SearchQueueControl(getCommands = { cmds }, cancelCommand = { cancelled += it }, threshold = { 3 }, dryRun = { true })
        assertEquals(1, c.cancelBackfill())
        assertEquals(emptyList(), cancelled)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.orchestration.SearchQueueControlTest"`
Expected: FAIL — `Unresolved reference 'SearchQueueControl'`.

- [ ] **Step 3: Append the class** to `SearchQueueControl.kt`

```kotlin
import org.slf4j.LoggerFactory

private val sqcLogger = LoggerFactory.getLogger("SearchQueueControl")

/**
 * Reads Sonarr's command queue to throttle/preempt backfill searches.
 * Seam-injected (no HTTP) so it is unit-testable. Fails safe: a command-poll
 * error reports not-congested and cancels nothing, so sweeps proceed as before.
 */
class SearchQueueControl(
    private val getCommands: suspend () -> List<SonarrCommand>,
    private val cancelCommand: suspend (Long) -> Unit,
    private val threshold: () -> Int,
    private val dryRun: () -> Boolean,
) {
    suspend fun isCongested(): Boolean = try {
        pendingSearchCount(getCommands()) >= threshold()
    } catch (e: Exception) {
        sqcLogger.warn("isCongested: command poll failed, treating as uncongested: {}", e.message)
        false
    }

    /** Cancel active backfill searches (never EpisodeSearch). Returns the count targeted. */
    suspend fun cancelBackfill(): Int {
        val ids = try {
            cancellableBackfillSearchIds(getCommands())
        } catch (e: Exception) {
            sqcLogger.warn("cancelBackfill: command poll failed: {}", e.message); return 0
        }
        if (ids.isEmpty()) return 0
        if (dryRun()) {
            sqcLogger.info("sonarr search control: would cancel {} backfill searches {} [DRY RUN]", ids.size, ids)
        } else {
            sqcLogger.info("sonarr search control: cancelling {} backfill searches {} to prioritise P1/P2", ids.size, ids)
            ids.forEach { runCatching { cancelCommand(it) }.onFailure { e -> sqcLogger.warn("cancel {} failed: {}", it, e.message) } }
        }
        return ids.size
    }
}
```

> Place the `import org.slf4j.LoggerFactory` line with the file's other imports at the top, not mid-file.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.orchestration.SearchQueueControlTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/orchestration/SearchQueueControl.kt \
        prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/orchestration/SearchQueueControlTest.kt
git commit -m "feat(search-control): SearchQueueControl (isCongested + cancelBackfill)"
```

---

### Task 4: Settings

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/config/Settings.kt`
- Test: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/config/SearchControlSettingsTest.kt` (create)

- [ ] **Step 1: Write the failing test**

```kotlin
package org.yoshiz.app.prioritarr.backend.config

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchControlSettingsTest {
    private fun base() = Settings(
        sonarrUrl = "x", sonarrApiKey = "x", tautulliUrl = "x", tautulliApiKey = "x",
        qbitUrl = "x", sabUrl = "x", sabApiKey = "x",
    )

    @Test fun defaults() {
        val s = base()
        assertEquals(3, s.intervals.searchCongestionThreshold)
        assertEquals(true, s.cancelBackfillForPriority)
    }

    @Test fun override_applies() {
        val merged = applySettingsOverride(
            base(),
            EditableSettings(searchCongestionThreshold = 5, cancelBackfillForPriority = false),
        )
        assertEquals(5, merged.intervals.searchCongestionThreshold)
        assertEquals(false, merged.cancelBackfillForPriority)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.config.SearchControlSettingsTest"`
Expected: FAIL — unresolved `searchCongestionThreshold`.

- [ ] **Step 3: Add the fields in the five Settings.kt locations** (mirror `tdarrPauseEnabled`/`tdarrPauseMinutes`)

3a. In `data class Intervals(...)` add:
```kotlin
    val searchCongestionThreshold: Int = 3,
```

3b. In `data class Settings(...)` (near `sonarrWatchdogEnabled`) add:
```kotlin
    val cancelBackfillForPriority: Boolean = true,
```

3c. In `data class EditableSettings(...)` add:
```kotlin
    val searchCongestionThreshold: Int? = null,
    val cancelBackfillForPriority: Boolean? = null,
```

3d. In `applySettingsOverride(...)` top-level body add:
```kotlin
    cancelBackfillForPriority = override.cancelBackfillForPriority ?: base.cancelBackfillForPriority,
```
and inside the `intervals = base.intervals.copy(...)` block add:
```kotlin
    searchCongestionThreshold = override.searchCongestionThreshold ?: base.intervals.searchCongestionThreshold,
```

3e. In the env loader (where `sonarrWatchdogEnabled` / `dockerProxyUrl` are read) add:
```kotlin
    cancelBackfillForPriority = (env("CANCEL_BACKFILL_FOR_PRIORITY", "true") ?: "true").lowercase() in TRUTHY,
```

3f. In the YAML loader `intervals = intervals.copy(...)` block add:
```kotlin
    searchCongestionThreshold = o.num("search_congestion_threshold") { it.toInt() } ?: intervals.searchCongestionThreshold,
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.config.SearchControlSettingsTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/config/Settings.kt \
        prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/config/SearchControlSettingsTest.kt
git commit -m "feat(settings): searchCongestionThreshold + cancelBackfillForPriority"
```

---

### Task 5: Congestion gate in runBackfillSweep

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/sweep/Sweep.kt`
- Test: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/sweep/BackfillCongestionGateTest.kt` (create)

The gate: after Pass A1 (P1/P2) completes, if `lowPriorityCongested` is true, return early — Pass A2 (P3/P4) and Pass B (P5) are skipped; Pass A1 always runs.

- [ ] **Step 1: Write the failing test** (FakeSonarr subclass, mirroring `FastP1SweepTest`)

```kotlin
package org.yoshiz.app.prioritarr.backend.sweep

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.config.BandwidthSettings
import org.yoshiz.app.prioritarr.backend.config.P5RatchetConfig
import org.yoshiz.app.prioritarr.backend.database.Database
import org.yoshiz.app.prioritarr.backend.priority.PriorityResult
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class BackfillCongestionGateTest {
    private fun freshDb(): Database {
        val tmp = Files.createTempFile("prio-gate", ".db"); tmp.toFile().deleteOnExit()
        return Database(tmp.toAbsolutePath().toString())
    }
    private fun rec(seriesId: Long, id: Long) = buildJsonObject {
        put("seriesId", JsonPrimitive(seriesId)); put("id", JsonPrimitive(id))
        put("airDateUtc", JsonPrimitive("2024-01-01T00:00:00Z")); put("seasonNumber", JsonPrimitive(1))
    }
    private class FakeSonarr(private val missing: JsonArray) : SonarrClient(
        "http://fake", "x",
        HttpClient(MockEngine { respond(ByteReadChannel("{}"), headers = headersOf("Content-Type", "application/json")) }) { install(ContentNegotiation) { json() } },
    ) {
        val seriesSearched = mutableListOf<Long>()
        override suspend fun getWantedMissing(pageSize: Int): JsonArray = missing
        override suspend fun getQueue(pageSize: Int): JsonArray = JsonArray(emptyList())
        override suspend fun triggerSeriesSearch(seriesId: Long): JsonObject { seriesSearched += seriesId; return buildJsonObject {} }
    }

    @Test fun congested_skips_p3p4_series_searches() = runTest {
        val missing = buildJsonArray { add(rec(2L, 21L)) } // a P3 series
        val fake = FakeSonarr(missing)
        runBackfillSweep(
            sonarr = fake, db = freshDb(),
            p5Ratchet = P5RatchetConfig(), bandwidth = BandwidthSettings(), telemetry = null,
            maxSearches = 10, delaySeconds = 0, dryRun = false,
            p1p2MaxPerSweep = 20, p1p2CooldownMinutes = 30,
            lowPriorityCongested = true,
            priorityForSeriesFn = { PriorityResult(3, "P3", "") },
        )
        assertEquals(emptyList(), fake.seriesSearched) // gated
    }

    @Test fun uncongested_runs_p3p4_series_searches() = runTest {
        val missing = buildJsonArray { add(rec(2L, 21L)) }
        val fake = FakeSonarr(missing)
        runBackfillSweep(
            sonarr = fake, db = freshDb(),
            p5Ratchet = P5RatchetConfig(), bandwidth = BandwidthSettings(), telemetry = null,
            maxSearches = 10, delaySeconds = 0, dryRun = false,
            p1p2MaxPerSweep = 20, p1p2CooldownMinutes = 30,
            lowPriorityCongested = false,
            priorityForSeriesFn = { PriorityResult(3, "P3", "") },
        )
        assertEquals(listOf(2L), fake.seriesSearched)
    }
}
```

> If `P5RatchetConfig()` / `BandwidthSettings()` require args, supply the same no-arg/defaulted construction used in `SweepIntegrationTest.kt` (it constructs both) — check that file and mirror it.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.sweep.BackfillCongestionGateTest"`
Expected: FAIL — `runBackfillSweep` has no `lowPriorityCongested` parameter.

- [ ] **Step 3: Add the parameter + early return.** In the lambda-based `runBackfillSweep` (the one with `priorityForSeriesFn`), add the parameter (default `false` so other callers/tests compile) just before `priorityForSeriesFn`:

```kotlin
    lowPriorityCongested: Boolean = false,
```

Then, immediately after the Pass A1 block computes `p1p2Fired` (right before the `// ---- Pass A2` comment), insert:

```kotlin
    if (lowPriorityCongested) {
        logger.info("[backfill] search queue congested; deferring P3/P4 + P5 passes this sweep")
        return p1p2Fired
    }
```

Then in the production overload `runBackfillSweep(sonarr, priorityService, ...)`, add `lowPriorityCongested: Boolean = false,` to its parameter list and pass `lowPriorityCongested = lowPriorityCongested` through to the delegate call.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.sweep.BackfillCongestionGateTest"`
Expected: PASS (2 tests). Also run the existing sweep suite to confirm no regression:
`./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.sweep.*"` → all pass.

- [ ] **Step 5: Commit**

```bash
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/sweep/Sweep.kt \
        prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/sweep/BackfillCongestionGateTest.kt
git commit -m "feat(backfill): congestion gate skips P3/P4 + P5 passes (A1 always runs)"
```

---

### Task 6: P1-fast preemption (cancel backfill before firing)

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/sweep/FastP1Sweep.kt`
- Test: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/sweep/FastP1PreemptTest.kt` (create)

`runFastP1Sweep` gains optional params; when it has candidates and `cancelBackfillForPriority` is set, it cancels backfill before firing.

- [ ] **Step 1: Write the failing test**

```kotlin
package org.yoshiz.app.prioritarr.backend.sweep

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.database.Database
import org.yoshiz.app.prioritarr.backend.orchestration.SearchQueueControl
import org.yoshiz.app.prioritarr.backend.orchestration.SonarrCommand
import org.yoshiz.app.prioritarr.backend.priority.PriorityResult
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class FastP1PreemptTest {
    private val now = 1704844800L
    private fun freshDb(): Database {
        val tmp = Files.createTempFile("prio-preempt", ".db"); tmp.toFile().deleteOnExit()
        return Database(tmp.toAbsolutePath().toString())
    }
    private fun rec(seriesId: Long, id: Long, air: String) = buildJsonObject {
        put("seriesId", JsonPrimitive(seriesId)); put("id", JsonPrimitive(id))
        put("airDateUtc", JsonPrimitive(air)); put("seasonNumber", JsonPrimitive(1))
    }
    private class FakeSonarr(private val missing: JsonArray) : SonarrClient(
        "http://fake", "x",
        HttpClient(MockEngine { respond(ByteReadChannel("{}"), headers = headersOf("Content-Type", "application/json")) }) { install(ContentNegotiation) { json() } },
    ) {
        val searched = mutableListOf<List<Long>>()
        override suspend fun getWantedMissing(pageSize: Int): JsonArray = missing
        override suspend fun getQueue(pageSize: Int): JsonArray = JsonArray(emptyList())
        override suspend fun triggerEpisodeSearch(episodeIds: List<Long>): JsonObject { searched += episodeIds; return buildJsonObject {} }
    }

    private fun controlOver(cmds: List<SonarrCommand>, cancelled: MutableList<Long>) =
        SearchQueueControl(getCommands = { cmds }, cancelCommand = { cancelled += it }, threshold = { 3 }, dryRun = { false })

    @Test fun cancels_backfill_before_firing_when_candidates_exist() = runTest {
        val missing = buildJsonArray { add(rec(1L, 11L, "2024-01-09T22:00:00Z")) } // P1 in window
        val fake = FakeSonarr(missing)
        val cancelled = mutableListOf<Long>()
        val control = controlOver(listOf(SonarrCommand(7, "SeriesSearch", "started", null)), cancelled)
        val fired = runFastP1Sweep(
            sonarr = fake, db = freshDb(),
            priorityForSeriesFn = { PriorityResult(1, "P1", "") },
            releaseDelayMinutes = 60, windowHours = 48, cooldownMinutes = 20,
            maxPerSweep = 10, dryRun = false, nowEpochSeconds = now,
            searchQueueControl = control, cancelBackfillForPriority = true,
        )
        assertEquals(1, fired)
        assertEquals(listOf(7L), cancelled)           // backfill cancelled
        assertEquals(listOf(listOf(11L)), fake.searched) // P1 then fired
    }

    @Test fun no_candidates_means_no_cancellation() = runTest {
        val missing = buildJsonArray { add(rec(2L, 21L, "2024-01-09T22:00:00Z")) } // P3 -> no P1 candidate
        val fake = FakeSonarr(missing)
        val cancelled = mutableListOf<Long>()
        val control = controlOver(listOf(SonarrCommand(7, "SeriesSearch", "started", null)), cancelled)
        val fired = runFastP1Sweep(
            sonarr = fake, db = freshDb(),
            priorityForSeriesFn = { PriorityResult(3, "P3", "") },
            releaseDelayMinutes = 60, windowHours = 48, cooldownMinutes = 20,
            maxPerSweep = 10, dryRun = false, nowEpochSeconds = now,
            searchQueueControl = control, cancelBackfillForPriority = true,
        )
        assertEquals(0, fired)
        assertEquals(emptyList(), cancelled)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.sweep.FastP1PreemptTest"`
Expected: FAIL — `runFastP1Sweep` has no `searchQueueControl` parameter.

- [ ] **Step 3: Add params + preemption.** In the lambda-based `runFastP1Sweep`, add two params (defaulted so the production overload + existing tests compile) after `nowEpochSeconds`:

```kotlin
    searchQueueControl: org.yoshiz.app.prioritarr.backend.orchestration.SearchQueueControl? = null,
    cancelBackfillForPriority: Boolean = false,
```

Then, between the `if (candidates.isEmpty()) return 0` line and the `return runPriorityEpisodePass(...)` call, insert:

```kotlin
    if (cancelBackfillForPriority && searchQueueControl != null) {
        searchQueueControl.cancelBackfill()
    }
```

Then in the production overload `runFastP1Sweep(sonarr, priorityService, ...)`, add the same two params (defaulted) and pass them through to the delegate call.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.sweep.FastP1PreemptTest"`
Expected: PASS (2 tests). Then `./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.sweep.*"` → all pass.

- [ ] **Step 5: Commit**

```bash
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/sweep/FastP1Sweep.kt \
        prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/sweep/FastP1PreemptTest.kt
git commit -m "feat(fast-p1): cancel backfill searches before firing P1/P2"
```

---

### Task 7: Wire into Main.kt

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/Main.kt`

No new unit test (wiring); verification is a green full build + suite.

- [ ] **Step 1: Construct SearchQueueControl** near the `sonarrWatchdog` construction (before the `Scheduler(...)`):

```kotlin
    val searchQueueControl = org.yoshiz.app.prioritarr.backend.orchestration.SearchQueueControl(
        getCommands = {
            org.yoshiz.app.prioritarr.backend.orchestration.parseCommands(sonarr.getCommands())
        },
        cancelCommand = { id -> sonarr.cancelCommand(id) },
        threshold = { liveSettings(db, settings).intervals.searchCongestionThreshold },
        dryRun = { liveSettings(db, settings).dryRun },
    )
```

- [ ] **Step 2: Gate BACKFILL_SWEEP.** In the `JobId.BACKFILL_SWEEP` job's `run`, pass the live congestion flag into `runBackfillSweep` by adding this argument to the call:

```kotlin
                        lowPriorityCongested = searchQueueControl.isCongested(),
```

- [ ] **Step 3: Gate CUTOFF_SWEEP.** Replace the `JobId.CUTOFF_SWEEP` job's `run` body with a congestion check:

```kotlin
                run = {
                    val s = liveSettings(db, settings)
                    if (searchQueueControl.isCongested()) {
                        org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome(summary = "skipped: sonarr search queue congested", noop = true)
                    } else {
                        runCutoffSweep(
                            sonarr, priorityService,
                            maxSearches = s.intervals.cutoffMaxSearchesPerSweep,
                            delaySeconds = s.intervals.backfillDelayBetweenSearchesSeconds,
                            dryRun = s.dryRun,
                        )
                        org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome()
                    }
                },
```

- [ ] **Step 4: Preempt in P1_FAST_SWEEP.** In the `JobId.P1_FAST_SWEEP` job's `run`, add these two arguments to the `runFastP1Sweep(...)` call:

```kotlin
                        searchQueueControl = searchQueueControl,
                        cancelBackfillForPriority = s.intervals.let { liveSettings(db, settings).cancelBackfillForPriority },
```

> Simpler: read once at the top of `run` as `val s = liveSettings(db, settings)` (already present) and pass `cancelBackfillForPriority = s.cancelBackfillForPriority` (it is a top-level Settings field, not under intervals).

- [ ] **Step 5: Build + full suite**

Run: `./gradlew :backend:test`
Expected: BUILD SUCCESSFUL; all tests pass (prior 245 + new ~14).

- [ ] **Step 6: Commit**

```bash
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/Main.kt
git commit -m "feat(search-control): wire congestion gate + P1/P2 preemption into scheduler"
```

---

### Task 8: Build + deploy

**Files:** none (deploy). prioritarr is now excluded from watchtower, so the local build won't be reverted.

- [ ] **Step 1: Build the image** (from `D:\git\prioritarr`):

```bash
docker build -f prioritarr/Dockerfile -t ghcr.io/cquemin/prioritarr:latest .
```
Expected: exit 0.

- [ ] **Step 2: Recreate prioritarr** (from `D:\docker`):

```bash
docker compose -f media-stack-v3.yml up -d --no-deps prioritarr
```
Expected: recreated, healthy.

- [ ] **Step 3: Verify image + behaviour**

Run: `docker inspect prioritarr --format '{{.Image}}'` matches the just-built image; `docker logs prioritarr --since 5m` shows no errors. After the next backfill/p1-fast tick, look for `search queue congested` or `cancelling N backfill searches` log lines when Sonarr is busy (and confirm Sonarr's command queue no longer accumulates a large `SeriesSearch` backlog).

---

## Self-Review

- **Spec coverage:** cancelCommand (T1), pure helpers incl. EpisodeSearch-never-cancellable invariant (T2), SearchQueueControl isCongested/cancelBackfill + fail-safe (T3), settings threshold+flag (T4), congestion gate skipping A2/B with A1 untouched (T5), P1-fast preemption gated on candidates (T6), wiring of backfill/cutoff gate + fast-p1 preempt (T7), deploy (T8). All covered.
- **Placeholder scan:** none — every code step is concrete. (Two `>` notes ask the implementer to mirror exact existing constructors/field placement; surrounding code is fully specified.)
- **Type consistency:** `SonarrCommand`/`parseCommands` reused from `orchestration`; `pendingSearchCount`, `cancellableBackfillSearchIds`, `SearchQueueControl(getCommands, cancelCommand, threshold, dryRun)` with `isCongested()`/`cancelBackfill()`; `runBackfillSweep(... lowPriorityCongested ...)`; `runFastP1Sweep(... searchQueueControl, cancelBackfillForPriority ...)`; settings `searchCongestionThreshold` (Intervals) + `cancelBackfillForPriority` (Settings) — consistent across tasks.
