# P1/P2 Fast-Grab Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make P1 and P2 episodes go from "aired + missing" to "grabbed" as fast as the indexers permit, by (a) guaranteeing priorities are refreshed before any sweep, (b) using episode-targeted searches instead of series-wide searches for top tiers, (c) skipping queued episodes, (d) cooling down repeats, (e) giving P1/P2 their own search budget, and (f) following up immediately on grabs.

**Architecture:** Six discrete changes hung off the existing scheduler + sweep + on-grab webhook. New SQLDelight table `p1p2_search_attempts` holds per-episode cooldown state. A pure-function planner (`buildP1P2Candidates`) keeps the priority/queue/cooldown logic test-friendly; a thin runner (`runP1P2EpisodePass`) executes the plan against Sonarr. Boot ordering is enforced via an `AtomicBoolean` flipped in `priorities-refresh`'s `finally` block plus a one-line scheduler fix that retries prereq-failed jobs every minute (today they wait a full cadence).

**Tech Stack:** Kotlin + Ktor + SQLDelight (backend), React + TypeScript (frontend). Test framework: `kotlin.test` with `kotlinx.coroutines.test` (`runTest`); HTTP integration tests use `MockEngine`.

**Spec:** `docs/specs/2026-05-15-p1p2-fast-grab-design.md`

---

## File structure

**New files:**
- `backend/src/main/kotlin/.../sweep/P1P2EpisodeSearch.kt` — pure planner + runner for Pass A1
- `backend/src/test/kotlin/.../sweep/P1P2EpisodeSearchTest.kt` — planner + runner unit tests
- `backend/src/test/kotlin/.../sweep/SweepIntegrationTest.kt` — end-to-end three-pass orchestration
- `backend/src/test/kotlin/.../database/P1P2AttemptsRoundTripTest.kt` — DB layer round-trip
- `backend/src/test/kotlin/.../webhooks/OnGrabFollowupTest.kt` — on-grab follow-up coroutine

**Modified files:**
- `backend/src/main/sqldelight/.../Schema.sq` — `p1p2_search_attempts` table + 3 queries
- `backend/src/main/kotlin/.../database/Database.kt` — wrapper functions for the new queries
- `backend/src/main/kotlin/.../config/Settings.kt` — 3 new `Intervals` fields, override pass-through, YAML parser
- `backend/src/main/kotlin/.../api/v2/V2Routes.kt` — 3 patch fields + 2 view-build sites
- `backend/src/main/kotlin/.../schemas/V2.kt` — 3 fields on `IntervalsWire`
- `backend/src/main/kotlin/.../app/AppState.kt` — add `prioritiesPrimed: AtomicBoolean`
- `backend/src/main/kotlin/.../scheduler/Scheduler.kt` — `PREREQ_RETRY_MINUTES = 1`; use `min(cadence, retry)` on prereq fail
- `backend/src/main/kotlin/.../Main.kt` — `priorities-refresh` finally-sets-flag; `backfill-sweep` prereq; thread new settings into `runBackfillSweep`
- `backend/src/main/kotlin/.../sweep/Sweep.kt` — split Pass A into A1 (P1/P2 episode) + A2 (P3/P4 series); add `toEpisodeIdSet` helper
- `backend/src/main/kotlin/.../app/Module.kt` — Grab branch follow-up coroutine; Download branch clears cooldown
- `backend/src/test/kotlin/.../config/SettingsParserTest.kt` — new YAML keys load
- `backend/src/test/kotlin/.../scheduler/SchedulerTest.kt` — prereq-fail retry
- `frontend/src/lib/jobs.tsx` — three new settings rows on `backfill-sweep`
- `README.md` — three new YAML keys in config reference

---

## Task ordering rationale

1–3 are pure data plumbing (settings, schema, DTOs) — no behaviour changes, but they unblock the rest. 4 is the standalone scheduler fix. 5 wires the boot-ordering flag. 6–8 build P1/P2 search (planner → runner → sweep integration). 9–10 wire the webhook follow-up. 11–12 finish the frontend + docs.

Each task ends with a green test run and a commit. Tests use `kotlin.test` (`@Test`, `assertEquals`, `assertTrue`); coroutine tests use `kotlinx.coroutines.test.runTest`.

---

### Task 1: Settings plumbing for P1/P2 fields

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/config/Settings.kt`
- Modify: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/config/SettingsParserTest.kt`

- [ ] **Step 1: Write the failing test**

Append to `SettingsParserTest.kt` (or create it if missing — follow the pattern of any nearby parser test):

```kotlin
@Test
fun yaml_loads_backfill_p1p2_fields() {
    val yaml = """
        intervals:
          backfill_p1_p2_max_per_sweep: 25
          backfill_p1_p2_cooldown_minutes: 45
          backfill_p1_p2_followup_episodes: 3
    """.trimIndent()
    val settings = parseSettingsFromYamlString(yaml)
    assertEquals(25, settings.intervals.backfillP1P2MaxPerSweep)
    assertEquals(45, settings.intervals.backfillP1P2CooldownMinutes)
    assertEquals(3, settings.intervals.backfillP1P2FollowupEpisodes)
}

@Test
fun intervals_p1p2_fields_have_documented_defaults() {
    val intervals = org.yoshiz.app.prioritarr.backend.config.Intervals()
    assertEquals(20, intervals.backfillP1P2MaxPerSweep)
    assertEquals(30, intervals.backfillP1P2CooldownMinutes)
    assertEquals(2, intervals.backfillP1P2FollowupEpisodes)
}
```

If `parseSettingsFromYamlString` doesn't exist with that exact name, locate the existing test helper for YAML parsing (search for `intervals:` in the `config/` test directory) and call that instead. If no such helper exists, inline-instantiate the parser the same way the existing YAML test does.

- [ ] **Step 2: Run test to verify it fails**

Run from repo root:
```
./prioritarr/gradlew -p prioritarr/backend test --tests "*.SettingsParserTest" -i
```
Expected: FAIL — `backfillP1P2MaxPerSweep` unresolved reference.

- [ ] **Step 3: Add the three fields to `Intervals`**

In `config/Settings.kt`, locate `data class Intervals` (line ~62). Add three fields after `cutoffMaxSearchesPerSweep`:

```kotlin
data class Intervals(
    val reconcileMinutes: Int = 15,
    val backfillSweepHours: Int = 2,
    val cutoffSweepHours: Int = 24,
    val backfillMaxSearchesPerSweep: Int = 10,
    val backfillDelayBetweenSearchesSeconds: Int = 30,
    val cutoffMaxSearchesPerSweep: Int = 5,
    // P1/P2 fast-grab pass (see docs/specs/2026-05-15-p1p2-fast-grab-design.md)
    val backfillP1P2MaxPerSweep: Int = 20,
    val backfillP1P2CooldownMinutes: Int = 30,
    val backfillP1P2FollowupEpisodes: Int = 2,
    val refreshMappingsMinutes: Int = 60,
    val refreshSeriesCacheMinutes: Int = 5,
    val refreshEpisodeCacheMinutes: Int = 60,
    val refreshPrioritiesMinutes: Int = 30,
    val queueJanitorMinutes: Int = 30,
    val unmonitoredReaperMinutes: Int = 30,
    val traktTokenRefreshHours: Int = 24,
)
```

- [ ] **Step 4: Add override-pass-through and YAML parsing**

In the same file, add to `data class EditableSettings(...)` (line ~290), next to the existing `backfillMaxSearchesPerSweep: Int? = null`:

```kotlin
    val backfillP1P2MaxPerSweep: Int? = null,
    val backfillP1P2CooldownMinutes: Int? = null,
    val backfillP1P2FollowupEpisodes: Int? = null,
```

In `applySettingsOverride` (line ~333), inside the `intervals = base.intervals.copy(...)` block, add three lines next to `backfillMaxSearchesPerSweep = ...`:

```kotlin
        backfillP1P2MaxPerSweep = override.backfillP1P2MaxPerSweep ?: base.intervals.backfillP1P2MaxPerSweep,
        backfillP1P2CooldownMinutes = override.backfillP1P2CooldownMinutes ?: base.intervals.backfillP1P2CooldownMinutes,
        backfillP1P2FollowupEpisodes = override.backfillP1P2FollowupEpisodes ?: base.intervals.backfillP1P2FollowupEpisodes,
```

In the YAML parser block `(root["intervals"] as? Map<*, *>)?.let { o -> ... }` (line ~430), inside the `intervals.copy(...)`, add three lines:

```kotlin
        backfillP1P2MaxPerSweep = o.num("backfill_p1_p2_max_per_sweep") { it.toInt() } ?: intervals.backfillP1P2MaxPerSweep,
        backfillP1P2CooldownMinutes = o.num("backfill_p1_p2_cooldown_minutes") { it.toInt() } ?: intervals.backfillP1P2CooldownMinutes,
        backfillP1P2FollowupEpisodes = o.num("backfill_p1_p2_followup_episodes") { it.toInt() } ?: intervals.backfillP1P2FollowupEpisodes,
```

- [ ] **Step 5: Run test to verify it passes**

```
./prioritarr/gradlew -p prioritarr/backend test --tests "*.SettingsParserTest" -i
```
Expected: PASS.

- [ ] **Step 6: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/config/Settings.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/config/SettingsParserTest.kt
git commit -m "feat(config): add backfillP1P2MaxPerSweep/Cooldown/Followup intervals"
```

---

### Task 2: V2 API wire schema + patch route

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/schemas/V2.kt`
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/api/v2/V2Routes.kt`

This task has no new test of its own — the wire shape is exercised through the existing settings API tests, and Task 1's compilation enforces the rest.

- [ ] **Step 1: Extend `IntervalsWire`**

In `schemas/V2.kt` (line ~182), add three fields with the same defaults as `Intervals`:

```kotlin
@Serializable
data class IntervalsWire(
    val reconcileMinutes: Int = 15,
    val backfillSweepHours: Int = 2,
    val cutoffSweepHours: Int = 24,
    val backfillMaxSearchesPerSweep: Int = 10,
    val backfillDelayBetweenSearchesSeconds: Int = 30,
    val cutoffMaxSearchesPerSweep: Int = 5,
    val backfillP1P2MaxPerSweep: Int = 20,
    val backfillP1P2CooldownMinutes: Int = 30,
    val backfillP1P2FollowupEpisodes: Int = 2,
    val refreshMappingsMinutes: Int = 60,
    val refreshSeriesCacheMinutes: Int = 5,
    val refreshEpisodeCacheMinutes: Int = 60,
    val refreshPrioritiesMinutes: Int = 30,
    val queueJanitorMinutes: Int = 30,
    val unmonitoredReaperMinutes: Int = 30,
    val traktTokenRefreshHours: Int = 24,
)
```

- [ ] **Step 2: Thread fields through the settings patch route**

In `api/v2/V2Routes.kt`, find `mergeSettingsPatch` (line ~155). Add three lines next to `backfillMaxSearchesPerSweep`:

```kotlin
    backfillP1P2MaxPerSweep = patch.backfillP1P2MaxPerSweep ?: existing.backfillP1P2MaxPerSweep,
    backfillP1P2CooldownMinutes = patch.backfillP1P2CooldownMinutes ?: existing.backfillP1P2CooldownMinutes,
    backfillP1P2FollowupEpisodes = patch.backfillP1P2FollowupEpisodes ?: existing.backfillP1P2FollowupEpisodes,
```

In the same file, find each `IntervalsWire(...)` construction (lines ~815 and ~907 in the GET view-building sites). Add three field mappings at each site:

```kotlin
backfillP1P2MaxPerSweep = s.intervals.backfillP1P2MaxPerSweep,
backfillP1P2CooldownMinutes = s.intervals.backfillP1P2CooldownMinutes,
backfillP1P2FollowupEpisodes = s.intervals.backfillP1P2FollowupEpisodes,
```

- [ ] **Step 3: Add fields to the patch DTO**

Locate the `EditableSettings` patch wire type (or its sibling DTO used by the patch route — search `V2Routes.kt` for `patch.backfillMaxSearchesPerSweep` to find the type, then add the three new nullable fields). The repo currently sends patches as `EditableSettings`-shaped JSON, so this typically just requires adding the three `Int? = null` fields in the same place where Task 1 added them — verify both backend deserialization and the route compile.

- [ ] **Step 4: Compile-check**

```
./prioritarr/gradlew -p prioritarr/backend compileKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run all existing tests as smoke**

```
./prioritarr/gradlew -p prioritarr/backend test
```
Expected: all green (no behaviour change yet).

- [ ] **Step 6: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/schemas/V2.kt prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/api/v2/V2Routes.kt
git commit -m "feat(api): wire backfillP1P2 fields through IntervalsWire + settings patch"
```

---

### Task 3: SQLDelight schema + Database wrappers for `p1p2_search_attempts`

**Files:**
- Modify: `prioritarr/backend/src/main/sqldelight/org/yoshiz/app/prioritarr/backend/database/Schema.sq`
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/database/Database.kt`
- Test: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/database/P1P2AttemptsRoundTripTest.kt` *(new)*

- [ ] **Step 1: Write the failing test**

Create `P1P2AttemptsRoundTripTest.kt`:

```kotlin
package org.yoshiz.app.prioritarr.backend.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import java.nio.file.Files

class P1P2AttemptsRoundTripTest {

    private fun freshDb(): Database {
        val tmp = Files.createTempFile("prioritarr-p1p2-test", ".db")
        tmp.toFile().deleteOnExit()
        return Database(tmp.toAbsolutePath().toString())
    }

    @Test fun upsert_then_list_round_trip() {
        val db = freshDb()
        db.upsertP1P2Attempt(episodeId = 101L, lastAttemptedAt = 1_000_000L)
        val ids = db.listP1P2AttemptedSince(0L)
        assertEquals(listOf(101L), ids)
    }

    @Test fun upsert_overwrites_timestamp_and_bumps_count() {
        val db = freshDb()
        db.upsertP1P2Attempt(episodeId = 1L, lastAttemptedAt = 1_000L)
        db.upsertP1P2Attempt(episodeId = 1L, lastAttemptedAt = 2_000L)
        // Both rows must collapse to a single episode with the later timestamp.
        assertEquals(listOf(1L), db.listP1P2AttemptedSince(1_500L))
        assertEquals(listOf(1L), db.listP1P2AttemptedSince(0L))
    }

    @Test fun listAttemptedSince_excludes_older_rows() {
        val db = freshDb()
        db.upsertP1P2Attempt(1L, 1_000L)
        db.upsertP1P2Attempt(2L, 2_000L)
        db.upsertP1P2Attempt(3L, 3_000L)
        assertEquals(setOf(2L, 3L), db.listP1P2AttemptedSince(2_000L).toSet())
    }

    @Test fun clear_removes_a_single_episode() {
        val db = freshDb()
        db.upsertP1P2Attempt(1L, 1_000L)
        db.upsertP1P2Attempt(2L, 1_000L)
        db.clearP1P2Attempt(1L)
        assertEquals(listOf(2L), db.listP1P2AttemptedSince(0L))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
./prioritarr/gradlew -p prioritarr/backend test --tests "*.P1P2AttemptsRoundTripTest" -i
```
Expected: FAIL — `upsertP1P2Attempt` unresolved reference.

- [ ] **Step 3: Add schema + queries**

In `Schema.sq`, after the `p5_sweep_attempts` table block (around line 63), add the new table:

```sql
-- Per-episode attempt log for the P1/P2 fast-grab pass (see
-- docs/specs/2026-05-15-p1p2-fast-grab-design.md). Mirrors
-- p5_sweep_attempts in spirit but keyed per episode (not per season)
-- because Pass A1 fires Sonarr's EpisodeSearch with a list of IDs.
CREATE TABLE IF NOT EXISTS p1p2_search_attempts (
    episode_id INTEGER PRIMARY KEY,
    last_attempted_at INTEGER NOT NULL CHECK (last_attempted_at > 0),
    attempts_count INTEGER NOT NULL DEFAULT 1 CHECK (attempts_count >= 1)
);
```

At the bottom of the file (after the `p5_sweep_attempts` query section), add the three queries:

```sql
-- ------------------------------------------------------------------
-- p1p2_search_attempts
-- ------------------------------------------------------------------

upsertP1P2Attempt:
INSERT INTO p1p2_search_attempts (episode_id, last_attempted_at, attempts_count)
VALUES (?, ?, 1)
ON CONFLICT(episode_id) DO UPDATE SET
    last_attempted_at = excluded.last_attempted_at,
    attempts_count = attempts_count + 1;

listP1P2AttemptedSince:
SELECT episode_id FROM p1p2_search_attempts
WHERE last_attempted_at >= ?
ORDER BY episode_id;

clearP1P2Attempt:
DELETE FROM p1p2_search_attempts WHERE episode_id = ?;
```

- [ ] **Step 4: Add Database wrappers**

In `database/Database.kt`, after the `deleteP5AttemptsForSeries` function (line ~199), add:

```kotlin
    // ------------------------------------------------------------------
    // p1p2_search_attempts
    // ------------------------------------------------------------------

    fun upsertP1P2Attempt(episodeId: Long, lastAttemptedAt: Long) {
        q.upsertP1P2Attempt(
            episode_id = episodeId,
            last_attempted_at = lastAttemptedAt,
        )
    }

    fun listP1P2AttemptedSince(thresholdEpochSeconds: Long): List<Long> =
        q.listP1P2AttemptedSince(thresholdEpochSeconds).executeAsList()

    fun clearP1P2Attempt(episodeId: Long) {
        q.clearP1P2Attempt(episodeId)
    }
```

- [ ] **Step 5: Run test to verify it passes**

```
./prioritarr/gradlew -p prioritarr/backend test --tests "*.P1P2AttemptsRoundTripTest" -i
```
Expected: PASS (4 tests green). If SQLDelight code generation fails, run `./prioritarr/gradlew -p prioritarr/backend generateMainOrgYoshizAppPrioritarrBackendDatabaseInterface` or the equivalent task surfaced by the build error.

- [ ] **Step 6: Commit**

```
git add prioritarr/backend/src/main/sqldelight/org/yoshiz/app/prioritarr/backend/database/Schema.sq prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/database/Database.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/database/P1P2AttemptsRoundTripTest.kt
git commit -m "feat(db): p1p2_search_attempts table + Database wrappers"
```

---

### Task 4: Scheduler prereq-retry fix

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/scheduler/Scheduler.kt`
- Test: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/scheduler/SchedulerTest.kt` *(new if absent — verify with `ls`)*

Today the scheduler reschedules a prereq-failed job by its full cadence (`Scheduler.kt:161`). For `backfill-sweep` (2 h cadence) that delays the post-boot first run by 2 hours when its prereq starts false. Fix: cap the retry at 1 minute.

- [ ] **Step 1: Write the failing test**

Create `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/scheduler/SchedulerTest.kt` if it doesn't exist; otherwise append:

```kotlin
package org.yoshiz.app.prioritarr.backend.scheduler

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchedulerPrereqRetryTest {

    @Test
    fun prereq_false_reschedules_within_one_minute_not_full_cadence() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val runCount = AtomicInteger(0)
        var prereqReady = false
        val outcomes = mutableListOf<Triple<String, String, String?>>()

        val job = JobDefinition(
            id = "test-job",
            cadenceMinutes = { 120L },           // 2 hours
            prerequisites = { prereqReady },
            weight = JobWeight.LIGHT,
            firstRunDelayMinutes = 0,
            run = {
                runCount.incrementAndGet()
                JobOutcome(summary = "ok")
            },
        )

        val scheduler = Scheduler(
            db = NullDatabase(),
            jobs = listOf(job),
            outcomeWriter = { id, _, _, status, summary, _ ->
                outcomes += Triple(id, status, summary)
            },
        )
        // Inspect internal state via reflection — assert reschedule
        // window is ≤ 1 minute when prereq fails.
        val nextDueField = scheduler.javaClass.getDeclaredField("nextDue").also { it.isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val nextDue = nextDueField.get(scheduler) as java.util.concurrent.ConcurrentHashMap<String, Instant>

        // Drive one tick directly (bypassing the 15s start delay).
        val tickFn = scheduler.javaClass.getDeclaredMethod("tick", CoroutineScope::class.java)
            .also { it.isAccessible = true }
        tickFn.invoke(scheduler, scope)

        val due = nextDue["test-job"]!!
        val secondsAhead = java.time.Duration.between(Instant.now(), due).toSeconds()
        assertTrue(secondsAhead in 0..70L, "expected ≤1min reschedule, got ${secondsAhead}s")
        assertEquals(0, runCount.get(), "job must not have run while prereq was false")
        scope.cancel()
    }

    /** Minimal stub satisfying Scheduler's Database needs (recordJobRun is unused via outcomeWriter override). */
    private class NullDatabase : org.yoshiz.app.prioritarr.backend.database.Database("/dev/null") {
        // The Database type requires a path; tests use the outcomeWriter override
        // so no real DB work happens. If construction fails on /dev/null, switch
        // to a temp-file path the same way P1P2AttemptsRoundTripTest does.
    }
}
```

**Note:** If `Database("/dev/null")` errors on Windows or in CI, switch the test to use `Files.createTempFile("prio-sched", ".db")` like the round-trip tests do.

- [ ] **Step 2: Run test to verify it fails**

```
./prioritarr/gradlew -p prioritarr/backend test --tests "*.SchedulerPrereqRetryTest" -i
```
Expected: FAIL — `secondsAhead` is ~7200 (full 2h cadence), not ≤70.

- [ ] **Step 3: Apply the fix**

In `Scheduler.kt`, near the top of the file (after `HEAVY_PER_TICK`, line ~95), add:

```kotlin
/** Cap on how far we push a prereq-failed job before re-evaluating. */
private const val PREREQ_RETRY_MINUTES = 1L
```

Then in the `tick` function (line ~157-163), replace:

```kotlin
            if (!ready) {
                // Push due forward by one cadence so we don't tight-
                // loop on this prereq every minute. The job will
                // re-evaluate on the next interval.
                rescheduleAfter(job, now)
                continue
            }
```

with:

```kotlin
            if (!ready) {
                // Retry within ~1 minute rather than waiting a full
                // cadence — many prereqs (toggle flips, sibling-job
                // completion) become true within seconds.
                val cadence = job.cadenceMinutes().coerceAtLeast(1L)
                val retry = minOf(cadence, PREREQ_RETRY_MINUTES)
                nextDue[job.id] = now.plus(java.time.Duration.ofMinutes(retry))
                continue
            }
```

- [ ] **Step 4: Run test to verify it passes**

```
./prioritarr/gradlew -p prioritarr/backend test --tests "*.SchedulerPrereqRetryTest" -i
```
Expected: PASS.

- [ ] **Step 5: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/scheduler/Scheduler.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/scheduler/SchedulerTest.kt
git commit -m "fix(scheduler): retry prereq-failed jobs in 1min, not full cadence"
```

---

### Task 5: Boot-ordering flag (prioritiesPrimed)

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/app/AppState.kt`
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/Main.kt`

This task only changes wiring — no new isolated test (the behaviour is exercised end-to-end by Task 7's `SweepIntegrationTest` and the existing scheduler test in Task 4 already covers the retry mechanic).

- [ ] **Step 1: Add `prioritiesPrimed` to `AppState`**

In `app/AppState.kt`, add the import and a new field at the end of the data class:

```kotlin
import java.util.concurrent.atomic.AtomicBoolean
```

```kotlin
data class AppState(
    // …all existing fields…
    val httpClients: List<HttpClient>,
    /**
     * Boot-ordering flag — set true the first time [refreshAllPriorities]
     * runs (success OR failure, via a finally block in the priorities-
     * refresh job in Main.kt). Backfill sweep gates on this so cold-boot
     * sweeps don't run against an empty priority cache.
     */
    val prioritiesPrimed: AtomicBoolean = AtomicBoolean(false),
)
```

- [ ] **Step 2: Wrap priorities-refresh in try/finally**

In `Main.kt`, find the `REFRESH_PRIORITIES` job registration (line ~409-417):

```kotlin
            add(org.yoshiz.app.prioritarr.backend.scheduler.JobDefinition(
                id = JobId.REFRESH_PRIORITIES,
                cadenceMinutes = { liveSettings(db, settings).intervals.refreshPrioritiesMinutes.toLong() },
                weight = org.yoshiz.app.prioritarr.backend.scheduler.JobWeight.HEAVY,
                run = {
                    org.yoshiz.app.prioritarr.backend.priority.refreshAllPriorities(sonarr, priorityService)
                    org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome()
                },
            ))
```

Replace the `run = { … }` body with:

```kotlin
                run = {
                    try {
                        org.yoshiz.app.prioritarr.backend.priority.refreshAllPriorities(sonarr, priorityService)
                        org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome()
                    } finally {
                        state.prioritiesPrimed.set(true)
                    }
                },
```

The `finally` guarantees backfill unblocks even if a single Sonarr request 500s mid-refresh.

- [ ] **Step 3: Gate `BACKFILL_SWEEP` on the flag**

In the same file, find the `BACKFILL_SWEEP` job registration (line ~512-531) and add a `prerequisites` line:

```kotlin
            add(org.yoshiz.app.prioritarr.backend.scheduler.JobDefinition(
                id = JobId.BACKFILL_SWEEP,
                cadenceMinutes = { liveSettings(db, settings).intervals.backfillSweepHours.toLong() * 60L },
                prerequisites = { state.prioritiesPrimed.get() },
                weight = org.yoshiz.app.prioritarr.backend.scheduler.JobWeight.LIGHT,
                run = {
                    // …unchanged body…
                },
            ))
```

- [ ] **Step 4: Compile-check + run all backend tests**

```
./prioritarr/gradlew -p prioritarr/backend test
```
Expected: all green.

- [ ] **Step 5: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/app/AppState.kt prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/Main.kt
git commit -m "feat(scheduler): gate backfill-sweep on priorities-refresh first-run"
```

---

### Task 6: P1/P2 pure planner (`buildP1P2Candidates`)

**Files:**
- Create: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/sweep/P1P2EpisodeSearch.kt`
- Create: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/sweep/P1P2EpisodeSearchTest.kt`

The planner is a pure function — same shape as `buildP5RatchetPlan` — so it can be exhaustively tested without HTTP or DB.

- [ ] **Step 1: Write the failing tests**

Create `P1P2EpisodeSearchTest.kt`:

```kotlin
package org.yoshiz.app.prioritarr.backend.sweep

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class P1P2EpisodeSearchPlannerTest {

    private fun rec(seriesId: Long, episodeId: Long, airDate: String, season: Int = 1): JsonObject =
        buildJsonObject {
            put("seriesId", JsonPrimitive(seriesId))
            put("id", JsonPrimitive(episodeId))
            put("airDateUtc", JsonPrimitive(airDate))
            put("seasonNumber", JsonPrimitive(season))
        }

    private fun records(vararg objs: JsonObject): JsonArray = buildJsonArray { objs.forEach { add(it) } }

    @Test fun groups_by_series_takes_5_oldest() {
        val recs = records(
            rec(1L, 11L, "2024-01-05"),
            rec(1L, 12L, "2024-01-03"),
            rec(1L, 13L, "2024-01-01"),
            rec(1L, 14L, "2024-01-04"),
            rec(1L, 15L, "2024-01-02"),
            rec(1L, 16L, "2024-01-06"),     // 6th — should be dropped
        )
        val out = buildP1P2Candidates(
            records = recs,
            priorityBySeriesId = mapOf(1L to 1),
            queuedEpisodeIds = emptySet(),
            cooldownEpisodeIds = emptySet(),
            perSeriesCap = 5,
        )
        assertEquals(1, out.size)
        val c = out.single()
        assertEquals(1L, c.seriesId)
        assertEquals(1, c.priority)
        assertEquals(listOf(13L, 15L, 12L, 14L, 11L), c.episodes.map { it.episodeId })
    }

    @Test fun outer_sort_is_priority_then_oldest() {
        val recs = records(
            rec(1L, 11L, "2024-01-01"),     // P2 oldest later
            rec(1L, 12L, "2024-02-01"),
            rec(2L, 21L, "2023-12-01"),     // P1 oldest earlier — wins
            rec(2L, 22L, "2024-03-01"),
            rec(3L, 31L, "2020-01-01"),     // P2 oldest very early — beats P2 series 1
        )
        val out = buildP1P2Candidates(
            records = recs,
            priorityBySeriesId = mapOf(1L to 2, 2L to 1, 3L to 2),
            queuedEpisodeIds = emptySet(),
            cooldownEpisodeIds = emptySet(),
            perSeriesCap = 5,
        )
        assertEquals(listOf(2L, 3L, 1L), out.map { it.seriesId })
    }

    @Test fun queue_skip_drops_matching_episode_ids() {
        val recs = records(
            rec(1L, 11L, "2024-01-01"),
            rec(1L, 12L, "2024-01-02"),
            rec(1L, 13L, "2024-01-03"),
        )
        val out = buildP1P2Candidates(
            records = recs,
            priorityBySeriesId = mapOf(1L to 1),
            queuedEpisodeIds = setOf(12L),
            cooldownEpisodeIds = emptySet(),
            perSeriesCap = 5,
        )
        assertEquals(listOf(11L, 13L), out.single().episodes.map { it.episodeId })
    }

    @Test fun cooldown_skip_drops_matching_episode_ids() {
        val recs = records(
            rec(1L, 11L, "2024-01-01"),
            rec(1L, 12L, "2024-01-02"),
        )
        val out = buildP1P2Candidates(
            records = recs,
            priorityBySeriesId = mapOf(1L to 2),
            queuedEpisodeIds = emptySet(),
            cooldownEpisodeIds = setOf(11L),
            perSeriesCap = 5,
        )
        assertEquals(listOf(12L), out.single().episodes.map { it.episodeId })
    }

    @Test fun series_with_all_episodes_filtered_drops_from_output() {
        val recs = records(
            rec(1L, 11L, "2024-01-01"),
            rec(2L, 21L, "2024-01-01"),
        )
        val out = buildP1P2Candidates(
            records = recs,
            priorityBySeriesId = mapOf(1L to 1, 2L to 2),
            queuedEpisodeIds = setOf(11L),
            cooldownEpisodeIds = setOf(21L),
            perSeriesCap = 5,
        )
        assertTrue(out.isEmpty())
    }

    @Test fun non_p1p2_series_are_ignored() {
        val recs = records(
            rec(1L, 11L, "2024-01-01"),       // P3 — ignore
            rec(2L, 21L, "2024-01-01"),       // P2 — keep
            rec(3L, 31L, "2024-01-01"),       // P5 — ignore
        )
        val out = buildP1P2Candidates(
            records = recs,
            priorityBySeriesId = mapOf(1L to 3, 2L to 2, 3L to 5),
            queuedEpisodeIds = emptySet(),
            cooldownEpisodeIds = emptySet(),
            perSeriesCap = 5,
        )
        assertEquals(listOf(2L), out.map { it.seriesId })
    }

    @Test fun missing_priority_treats_series_as_non_p1p2() {
        val recs = records(rec(1L, 11L, "2024-01-01"))
        val out = buildP1P2Candidates(
            records = recs,
            priorityBySeriesId = emptyMap(),  // priority unknown
            queuedEpisodeIds = emptySet(),
            cooldownEpisodeIds = emptySet(),
            perSeriesCap = 5,
        )
        assertTrue(out.isEmpty())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
./prioritarr/gradlew -p prioritarr/backend test --tests "*.P1P2EpisodeSearchPlannerTest" -i
```
Expected: FAIL — `buildP1P2Candidates` unresolved reference.

- [ ] **Step 3: Implement the planner**

Create `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/sweep/P1P2EpisodeSearch.kt`:

```kotlin
package org.yoshiz.app.prioritarr.backend.sweep

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.database.Database

private val logger = LoggerFactory.getLogger("org.yoshiz.app.prioritarr.backend.sweep.p1p2")

/** One missing episode from Sonarr's /wanted/missing, parsed for the P1/P2 planner. */
internal data class P1P2Episode(
    val episodeId: Long,
    val airDateUtc: String,
    val seasonNumber: Int,
)

/** One P1 or P2 series' worth of candidates, in firing order. */
internal data class P1P2Candidate(
    val seriesId: Long,
    val priority: Int,                        // 1 or 2
    val oldestAirDate: String,
    val episodes: List<P1P2Episode>,          // already sorted by airDateUtc asc and capped
)

/**
 * Pure planner — given the raw missing-episode records, a priority lookup,
 * and exclusion sets, return one [P1P2Candidate] per P1/P2 series whose
 * top-N oldest episodes are not already queued or cooling down. Outer sort
 * is (priority asc, oldestAirDate asc) so P1 wins over P2, oldest wins
 * within each priority.
 */
internal fun buildP1P2Candidates(
    records: JsonArray,
    priorityBySeriesId: Map<Long, Int>,
    queuedEpisodeIds: Set<Long>,
    cooldownEpisodeIds: Set<Long>,
    perSeriesCap: Int,
): List<P1P2Candidate> {
    // Group raw rows by seriesId, keep only P1/P2 series.
    val grouped = mutableMapOf<Long, MutableList<P1P2Episode>>()
    for (row in records) {
        val obj = row.jsonObject
        val sid = obj["seriesId"]?.jsonPrimitive?.longOrNull ?: continue
        val priority = priorityBySeriesId[sid] ?: continue
        if (priority !in 1..2) continue
        val episodeId = obj["id"]?.jsonPrimitive?.longOrNull ?: continue
        if (episodeId in queuedEpisodeIds) continue
        if (episodeId in cooldownEpisodeIds) continue
        val airDate = obj["airDateUtc"]?.jsonPrimitive?.contentOrNull ?: "9999"
        val season = obj["seasonNumber"]?.jsonPrimitive?.intOrNull ?: 0
        grouped.getOrPut(sid) { mutableListOf() }.add(P1P2Episode(episodeId, airDate, season))
    }

    return grouped.entries.mapNotNull { (sid, eps) ->
        val sorted = eps.sortedBy { it.airDateUtc }.take(perSeriesCap)
        if (sorted.isEmpty()) return@mapNotNull null
        val priority = priorityBySeriesId.getValue(sid)
        P1P2Candidate(
            seriesId = sid,
            priority = priority,
            oldestAirDate = sorted.first().airDateUtc,
            episodes = sorted,
        )
    }.sortedWith(compareBy({ it.priority }, { it.oldestAirDate }))
}
```

- [ ] **Step 4: Run tests to verify they pass**

```
./prioritarr/gradlew -p prioritarr/backend test --tests "*.P1P2EpisodeSearchPlannerTest" -i
```
Expected: PASS (7 tests green).

- [ ] **Step 5: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/sweep/P1P2EpisodeSearch.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/sweep/P1P2EpisodeSearchTest.kt
git commit -m "feat(sweep): pure P1/P2 episode-search candidate planner"
```

---

### Task 7: P1/P2 runner (`runP1P2EpisodePass`)

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/sweep/P1P2EpisodeSearch.kt`
- Modify: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/sweep/P1P2EpisodeSearchTest.kt`

The runner is impure (calls Sonarr + DB), but its decision tree is small. Test it with a fake `SonarrClient` and a real in-memory `Database`.

Check whether the codebase has an existing fake `SonarrClient` pattern — look at `sweep/P5RatchetTest.kt` and the `clients/` test directory. If a `FakeSonarrClient` already exists, reuse it. Otherwise, the test below defines a minimal one inline.

- [ ] **Step 1: Write the failing tests**

Append to `P1P2EpisodeSearchTest.kt`:

```kotlin
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.database.Database

class P1P2EpisodeSearchRunnerTest {

    private fun freshDb(): Database {
        val tmp = Files.createTempFile("prio-p1p2-runner", ".db")
        tmp.toFile().deleteOnExit()
        return Database(tmp.toAbsolutePath().toString())
    }

    /**
     * Minimal SonarrClient fake. We only need triggerEpisodeSearch to
     * record calls and optionally throw. If SonarrClient has a sealed/
     * abstract surface, adapt by extending it directly or by adding an
     * interface — but DO NOT introduce a new abstraction just for this
     * test; if needed, mark this class `open` rather than refactoring.
     */
    private class FakeSonarr(
        private val throwOnCall: Long? = null,
    ) : SonarrClient(baseUrl = "http://fake", apiKey = "x", urlBase = null) {
        val calls = mutableListOf<List<Long>>()
        override suspend fun triggerEpisodeSearch(episodeIds: List<Long>): kotlinx.serialization.json.JsonObject {
            calls += episodeIds
            if (throwOnCall != null && episodeIds.contains(throwOnCall)) error("fake failure")
            return buildJsonObject {}
        }
    }

    private fun candidate(seriesId: Long, vararg episodeIds: Long, priority: Int = 1) = P1P2Candidate(
        seriesId = seriesId,
        priority = priority,
        oldestAirDate = "2024-01-01",
        episodes = episodeIds.map { P1P2Episode(it, "2024-01-01", 1) },
    )

    @Test fun fires_episode_search_in_order_records_cooldown() = runTest {
        val db = freshDb()
        val fake = FakeSonarr()
        val now = 1_700_000_000L

        val fired = runP1P2EpisodePass(
            candidates = listOf(
                candidate(seriesId = 1L, 11L, 12L, 13L),
                candidate(seriesId = 2L, 21L),
            ),
            sonarr = fake,
            db = db,
            budget = 10,
            delaySeconds = 0,
            dryRun = false,
            nowEpochSeconds = now,
        )
        assertEquals(2, fired)
        assertEquals(listOf(listOf(11L, 12L, 13L), listOf(21L)), fake.calls)
        // Cooldown rows written for all four episodes:
        val cooldownIds = db.listP1P2AttemptedSince(0L).toSet()
        assertEquals(setOf(11L, 12L, 13L, 21L), cooldownIds)
    }

    @Test fun respects_budget() = runTest {
        val db = freshDb()
        val fake = FakeSonarr()
        val fired = runP1P2EpisodePass(
            candidates = listOf(
                candidate(1L, 11L),
                candidate(2L, 21L),
                candidate(3L, 31L),
            ),
            sonarr = fake, db = db,
            budget = 2, delaySeconds = 0, dryRun = false, nowEpochSeconds = 1L,
        )
        assertEquals(2, fired)
        assertEquals(2, fake.calls.size)
    }

    @Test fun dry_run_does_not_call_sonarr_or_record_cooldown() = runTest {
        val db = freshDb()
        val fake = FakeSonarr()
        val fired = runP1P2EpisodePass(
            candidates = listOf(candidate(1L, 11L, 12L)),
            sonarr = fake, db = db,
            budget = 10, delaySeconds = 0, dryRun = true, nowEpochSeconds = 1L,
        )
        assertEquals(1, fired)            // counts as "fired" for telemetry
        assertTrue(fake.calls.isEmpty())
        assertTrue(db.listP1P2AttemptedSince(0L).isEmpty())
    }

    @Test fun failure_breaks_and_does_not_record_cooldown_for_failed_call() = runTest {
        val db = freshDb()
        val fake = FakeSonarr(throwOnCall = 21L)
        val fired = runP1P2EpisodePass(
            candidates = listOf(
                candidate(1L, 11L),       // succeeds
                candidate(2L, 21L),       // fails
                candidate(3L, 31L),       // never attempted
            ),
            sonarr = fake, db = db,
            budget = 10, delaySeconds = 0, dryRun = false, nowEpochSeconds = 1L,
        )
        assertEquals(1, fired)
        // 11L recorded; 21L NOT recorded (so we retry next sweep); 31L never attempted
        assertEquals(listOf(11L), db.listP1P2AttemptedSince(0L))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
./prioritarr/gradlew -p prioritarr/backend test --tests "*.P1P2EpisodeSearchRunnerTest" -i
```
Expected: FAIL — `runP1P2EpisodePass` unresolved reference.

- [ ] **Step 3: Implement the runner**

Append to `P1P2EpisodeSearch.kt`:

```kotlin
/**
 * Execute [candidates] in order, calling Sonarr's EpisodeSearch and
 * recording per-episode cooldown rows. Each candidate counts as 1
 * against [budget] regardless of how many episode IDs are in its list
 * (one Sonarr command per series). On failure, break and DO NOT
 * record cooldown for the failed call so we retry next sweep.
 *
 * [nowEpochSeconds] is injected so tests can use a frozen clock.
 */
internal suspend fun runP1P2EpisodePass(
    candidates: List<P1P2Candidate>,
    sonarr: SonarrClient,
    db: Database,
    budget: Int,
    delaySeconds: Int,
    dryRun: Boolean,
    nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
): Int {
    var fired = 0
    for (c in candidates) {
        if (fired >= budget) break
        val ids = c.episodes.map { it.episodeId }
        if (dryRun) {
            logger.info("[backfill-p1p2] DRY RUN: would EpisodeSearch series {} eps {}", c.seriesId, ids)
        } else {
            try {
                sonarr.triggerEpisodeSearch(ids)
            } catch (e: Exception) {
                logger.warn("[backfill-p1p2] EpisodeSearch failed for series {}: {}", c.seriesId, e.message)
                break
            }
            ids.forEach { db.upsertP1P2Attempt(it, nowEpochSeconds) }
            logger.info("[backfill-p1p2] triggered: series {} eps {} (P{})", c.seriesId, ids, c.priority)
            if (delaySeconds > 0) delay(delaySeconds * 1_000L)
        }
        fired++
    }
    return fired
}
```

- [ ] **Step 4: Run tests to verify they pass**

```
./prioritarr/gradlew -p prioritarr/backend test --tests "*.P1P2EpisodeSearchRunnerTest" -i
```
Expected: PASS.

**If `FakeSonarr extends SonarrClient` fails** because `SonarrClient` is a `final class` or has constructor args that don't accept arbitrary URLs: open `clients/Sonarr.kt`, mark `SonarrClient` `open class`, and mark `triggerEpisodeSearch` `open`. This is a minimal, surgical relax — do not refactor the whole client. Commit this change as part of this task.

- [ ] **Step 5: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/sweep/P1P2EpisodeSearch.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/sweep/P1P2EpisodeSearchTest.kt
# Include clients/Sonarr.kt if marked open in step 4
git commit -m "feat(sweep): P1/P2 episode-search runner with cooldown writes"
```

---

### Task 8: Wire P1/P2 pass into `runBackfillSweep`

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/sweep/Sweep.kt`
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/Main.kt`
- Test: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/sweep/SweepIntegrationTest.kt` *(new)*

This restructures `runBackfillSweep` from "Pass A then Pass B" to "Pass A1 (P1/P2 episode) → Pass A2 (P3/P4 series) → Pass B (P5)". It also extracts a `JsonArray.toEpisodeIdSet()` helper that's reused by Task 9.

- [ ] **Step 1: Write the failing integration test**

Create `SweepIntegrationTest.kt`:

```kotlin
package org.yoshiz.app.prioritarr.backend.sweep

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.config.BandwidthSettings
import org.yoshiz.app.prioritarr.backend.config.P5RatchetConfig
import org.yoshiz.app.prioritarr.backend.database.Database
import org.yoshiz.app.prioritarr.backend.priority.PriorityService
import org.yoshiz.app.prioritarr.backend.priority.PriorityResult

class SweepIntegrationTest {

    private fun freshDb(): Database {
        val tmp = Files.createTempFile("prio-sweep-integ", ".db")
        tmp.toFile().deleteOnExit()
        return Database(tmp.toAbsolutePath().toString())
    }

    private class FakeSonarr(
        private val missing: JsonArray,
        private val queue: JsonArray = JsonArray(emptyList()),
    ) : SonarrClient(baseUrl = "http://fake", apiKey = "x", urlBase = null) {
        val episodeSearches = mutableListOf<List<Long>>()
        val seriesSearches = mutableListOf<Long>()
        override suspend fun getWantedMissing(): JsonArray = missing
        override suspend fun getQueue(pageSize: Int): JsonArray = queue
        override suspend fun triggerEpisodeSearch(episodeIds: List<Long>): JsonObject {
            episodeSearches += episodeIds; return buildJsonObject {}
        }
        override suspend fun triggerSeriesSearch(seriesId: Long): JsonObject {
            seriesSearches += seriesId; return buildJsonObject {}
        }
    }

    /** Stub PriorityService — returns a fixed priority per series. */
    private class FakePriority(private val byId: Map<Long, Int>) : PriorityService(/* match real ctor; see note */) {
        override suspend fun priorityForSeries(seriesId: Long): PriorityResult =
            PriorityResult(priority = byId[seriesId] ?: 5, label = "P${byId[seriesId] ?: 5}", reason = "")
    }

    private fun rec(seriesId: Long, episodeId: Long, airDate: String, season: Int = 1): JsonObject =
        buildJsonObject {
            put("seriesId", JsonPrimitive(seriesId))
            put("id", JsonPrimitive(episodeId))
            put("airDateUtc", JsonPrimitive(airDate))
            put("seasonNumber", JsonPrimitive(season))
        }

    @Test fun three_pass_orchestration_fires_correct_commands() = runTest {
        val records = buildJsonArray {
            // P1 series 1 — should be Pass A1 (EpisodeSearch)
            add(rec(1L, 11L, "2024-01-01"))
            add(rec(1L, 12L, "2024-01-02"))
            // P2 series 2 — Pass A1
            add(rec(2L, 21L, "2024-02-01"))
            // P3 series 3 — Pass A2 (SeriesSearch)
            add(rec(3L, 31L, "2024-01-01"))
            // P4 series 4 — Pass A2
            add(rec(4L, 41L, "2024-01-01"))
            // P5 series 5 — Pass B
            add(rec(5L, 51L, "2024-01-01", season = 1))
        }
        val sonarr = FakeSonarr(records)
        val db = freshDb()
        val priorityService = FakePriority(mapOf(1L to 1, 2L to 2, 3L to 3, 4L to 4, 5L to 5))

        val fired = runBackfillSweep(
            sonarr = sonarr,
            priorityService = priorityService,
            db = db,
            p5Ratchet = P5RatchetConfig(enabled = false),
            bandwidth = BandwidthSettings(),
            telemetry = null,
            maxSearches = 10,
            delaySeconds = 0,
            dryRun = false,
            p1p2MaxPerSweep = 20,
            p1p2CooldownMinutes = 30,
        )

        // Pass A1: one EpisodeSearch per P1/P2 series in priority order
        assertEquals(2, sonarr.episodeSearches.size)
        assertEquals(listOf(11L, 12L), sonarr.episodeSearches[0])    // P1 series 1
        assertEquals(listOf(21L), sonarr.episodeSearches[1])         // P2 series 2

        // Pass A2: SeriesSearch for P3 + P4 only
        assertEquals(listOf(3L, 4L), sonarr.seriesSearches)

        // Pass B: P5 series 5 also got a SeriesSearch (ratchet inactive → fallback)
        assertTrue(5L in sonarr.seriesSearches || fired > 4)

        // Cooldown rows written for P1/P2 episodes
        assertEquals(setOf(11L, 12L, 21L), db.listP1P2AttemptedSince(0L).toSet())
    }

    @Test fun queue_skip_blocks_p1p2_episode_search() = runTest {
        val records = buildJsonArray { add(rec(1L, 11L, "2024-01-01")) }
        val queue = buildJsonArray {
            add(buildJsonObject {
                put("episodeId", JsonPrimitive(11L))
                put("seriesId", JsonPrimitive(1L))
            })
        }
        val sonarr = FakeSonarr(records, queue)
        val db = freshDb()
        val priorityService = FakePriority(mapOf(1L to 1))

        runBackfillSweep(
            sonarr = sonarr, priorityService = priorityService, db = db,
            p5Ratchet = P5RatchetConfig(enabled = false), bandwidth = BandwidthSettings(),
            telemetry = null, maxSearches = 10, delaySeconds = 0, dryRun = false,
            p1p2MaxPerSweep = 20, p1p2CooldownMinutes = 30,
        )
        assertTrue(sonarr.episodeSearches.isEmpty(), "queued episode should be skipped")
        assertTrue(db.listP1P2AttemptedSince(0L).isEmpty())
    }

    @Test fun cooldown_blocks_p1p2_episode_search() = runTest {
        val records = buildJsonArray { add(rec(1L, 11L, "2024-01-01")) }
        val sonarr = FakeSonarr(records)
        val db = freshDb()
        val now = System.currentTimeMillis() / 1000L
        db.upsertP1P2Attempt(11L, now - 60)        // 1 minute ago, well inside 30-min cooldown
        val priorityService = FakePriority(mapOf(1L to 1))

        runBackfillSweep(
            sonarr = sonarr, priorityService = priorityService, db = db,
            p5Ratchet = P5RatchetConfig(enabled = false), bandwidth = BandwidthSettings(),
            telemetry = null, maxSearches = 10, delaySeconds = 0, dryRun = false,
            p1p2MaxPerSweep = 20, p1p2CooldownMinutes = 30,
        )
        assertTrue(sonarr.episodeSearches.isEmpty())
    }
}
```

**Note on `FakePriority`:** if `PriorityService`'s constructor takes parameters that can't be stubbed trivially, drop `class FakePriority` and instead build a lightweight test-only seam — e.g., expose a `priorityFor(Long) -> PriorityResult` lambda parameter on `runBackfillSweep` for tests. If that's needed, do the seam in this task; keep it small.

- [ ] **Step 2: Run tests to verify they fail**

```
./prioritarr/gradlew -p prioritarr/backend test --tests "*.SweepIntegrationTest" -i
```
Expected: FAIL — `runBackfillSweep` signature mismatch (new `p1p2*` params missing).

- [ ] **Step 3: Modify `Sweep.kt`**

Replace the body of `runBackfillSweep` (line ~53). The new function:

```kotlin
suspend fun runBackfillSweep(
    sonarr: SonarrClient,
    priorityService: PriorityService,
    db: Database,
    p5Ratchet: org.yoshiz.app.prioritarr.backend.config.P5RatchetConfig,
    bandwidth: org.yoshiz.app.prioritarr.backend.config.BandwidthSettings,
    telemetry: org.yoshiz.app.prioritarr.backend.enforcement.DownloadTelemetry?,
    maxSearches: Int,
    delaySeconds: Int,
    dryRun: Boolean,
    p1p2MaxPerSweep: Int,
    p1p2CooldownMinutes: Int,
): Int {
    val records = try { sonarr.getWantedMissing() } catch (e: Exception) {
        logger.warn("[backfill] fetch failed: {}", e.message); return 0
    }
    if (records.isEmpty()) {
        logger.info("[backfill] nothing to search")
        return 0
    }

    val order = buildSweepOrder(records, priorityService)
    val priorityBySeriesId = order.associate { it.seriesId to it.priority }
    val queueArr = try { sonarr.getQueue() } catch (_: Exception) { JsonArray(emptyList()) }
    val queuedIds = queueArr.toEpisodeIdSet()
    val nowSec = System.currentTimeMillis() / 1000L
    val cooldownIds = db.listP1P2AttemptedSince(nowSec - p1p2CooldownMinutes * 60L).toSet()

    logger.info(
        "[backfill] {} records / {} series; queue={}, cooldown={}, p1p2_budget={}, p3p4_budget={}",
        records.size, order.size, queuedIds.size, cooldownIds.size, p1p2MaxPerSweep, maxSearches,
    )

    // ---- Pass A1: P1/P2 episode-level ----
    val p1p2 = buildP1P2Candidates(
        records = records,
        priorityBySeriesId = priorityBySeriesId,
        queuedEpisodeIds = queuedIds,
        cooldownEpisodeIds = cooldownIds,
        perSeriesCap = 5,
    )
    val p1p2Fired = if (p1p2MaxPerSweep > 0) {
        runP1P2EpisodePass(
            candidates = p1p2,
            sonarr = sonarr, db = db,
            budget = p1p2MaxPerSweep,
            delaySeconds = delaySeconds,
            dryRun = dryRun,
            nowEpochSeconds = nowSec,
        )
    } else 0

    // ---- Pass A2: P3/P4 series-level ----
    var fired = 0
    val passA2 = order.filter { it.priority in 3..4 }
    for (entry in passA2) {
        if (fired >= maxSearches) {
            logger.info("[backfill] hit max searches ({}), deferring rest", maxSearches)
            break
        }
        if (dryRun) {
            logger.info("[backfill] DRY RUN: would series-search {} ({})", entry.seriesId, entry.label)
        } else {
            try { sonarr.triggerSeriesSearch(entry.seriesId) }
            catch (e: Exception) {
                logger.warn("[backfill] series-search failed for {}: {}", entry.seriesId, e.message); break
            }
            logger.info("[backfill] triggered: series {} ({})", entry.seriesId, entry.label)
            if (delaySeconds > 0) delay(delaySeconds * 1_000L)
        }
        fired++
    }

    // ---- Pass B: P5 ratchet (unchanged) ----
    if (fired >= maxSearches) return p1p2Fired + fired
    val p5Entries = order.filter { it.priority == 5 }
    if (p5Entries.isEmpty()) return p1p2Fired + fired

    val ratchetActive = p5Ratchet.enabled && run {
        if (bandwidth.maxMbps <= 0) return@run false
        val totalBps = telemetry?.observedPeakTotalBps() ?: 0L
        val ratchetBandwidth = p5Ratchet.bandwidthThresholdPct?.let {
            bandwidth.copy(utilisationThresholdPct = it)
        } ?: bandwidth
        org.yoshiz.app.prioritarr.backend.enforcement.BandwidthPolicy
            .utilisationExceedsThreshold(ratchetBandwidth, totalBps)
    }
    val p5SeriesIds = p5Entries.map { it.seriesId }.toSet()
    val p5Records: List<MissingRecord> = records.mapNotNull { el ->
        val o = el.jsonObject
        val sid = o["seriesId"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        if (sid !in p5SeriesIds) return@mapNotNull null
        val season = o["seasonNumber"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
        val episodeId = o["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        val air = o["airDateUtc"]?.jsonPrimitive?.contentOrNull ?: "9999"
        MissingRecord(sid, season, episodeId, air, priority = 5)
    }.distinctBy { it.episodeId }
    val queueHits: Set<QueueSeasonHit> = queueArr.mapNotNull {
        val o = it.jsonObject
        val sid = o["seriesId"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        val season = o["episode"]?.jsonObject?.get("seasonNumber")?.jsonPrimitive?.intOrNull
            ?: o["seasonNumber"]?.jsonPrimitive?.intOrNull
            ?: return@mapNotNull null
        QueueSeasonHit(sid, season)
    }.toSet()
    val planInputs = P5RatchetInputs(
        p5Records = p5Records, queueHits = queueHits,
        cooldownRows = db.listP5Attempts(),
        nowEpochSeconds = nowSec, cfg = p5Ratchet,
        budgetRemaining = maxSearches - fired, ratchetActive = ratchetActive,
    )
    val plan = buildP5RatchetPlan(planInputs)
    fired += runP5SeasonRatchet(plan = plan, sonarr = sonarr, db = db, delaySeconds = delaySeconds, dryRun = dryRun)
    return p1p2Fired + fired
}

/** Pull episode IDs out of Sonarr's /queue payload (covers both shapes). */
internal fun JsonArray.toEpisodeIdSet(): Set<Long> = mapNotNull {
    val o = it.jsonObject
    o["episode"]?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull
        ?: o["episodeId"]?.jsonPrimitive?.longOrNull
}.toSet()
```

- [ ] **Step 4: Update `Main.kt` to pass the new arguments**

In `Main.kt`, the `BACKFILL_SWEEP` job's `run` block (line ~516):

```kotlin
                run = {
                    val s = liveSettings(db, settings)
                    org.yoshiz.app.prioritarr.backend.sweep.runBackfillSweep(
                        sonarr = sonarr,
                        priorityService = priorityService,
                        db = db,
                        p5Ratchet = state.p5RatchetSource.current(),
                        bandwidth = state.bandwidthSource.current(),
                        telemetry = state.downloadTelemetry,
                        maxSearches = s.intervals.backfillMaxSearchesPerSweep,
                        delaySeconds = s.intervals.backfillDelayBetweenSearchesSeconds,
                        dryRun = s.dryRun,
                        p1p2MaxPerSweep = s.intervals.backfillP1P2MaxPerSweep,
                        p1p2CooldownMinutes = s.intervals.backfillP1P2CooldownMinutes,
                    )
                    org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome()
                },
```

- [ ] **Step 5: Run tests to verify they pass**

```
./prioritarr/gradlew -p prioritarr/backend test --tests "*.SweepIntegrationTest" -i
```
Expected: PASS.

Then run the full backend test suite to catch regressions:
```
./prioritarr/gradlew -p prioritarr/backend test
```
Expected: all green. **Pay particular attention to existing `SweepTest`/`P5RatchetTest` files** — they may need their `runBackfillSweep` call sites updated for the new parameters. If they break, the fix is to add the same two new arguments (or pull them from the defaults you may add for backwards compatibility; if so, document why).

- [ ] **Step 6: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/sweep/Sweep.kt prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/Main.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/sweep/SweepIntegrationTest.kt
git commit -m "feat(sweep): three-pass orchestration with P1/P2 episode-search"
```

---

### Task 9: On-grab follow-up search

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/app/Module.kt`
- Test: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/webhooks/OnGrabFollowupTest.kt` *(new)*

The follow-up logic lives inside the `Grab` branch of the `/api/sonarr/on-grab` route. We extract it into a top-level suspend function so the test can call it directly without spinning up a Ktor server.

- [ ] **Step 1: Write the failing test**

Create `OnGrabFollowupTest.kt`:

```kotlin
package org.yoshiz.app.prioritarr.backend.webhooks

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.database.Database

class OnGrabFollowupTest {

    private fun freshDb(): Database {
        val tmp = Files.createTempFile("prio-followup", ".db")
        tmp.toFile().deleteOnExit()
        return Database(tmp.toAbsolutePath().toString())
    }

    private class FakeSonarr(
        private val missing: JsonArray,
        private val queue: JsonArray = JsonArray(emptyList()),
    ) : SonarrClient(baseUrl = "http://fake", apiKey = "x", urlBase = null) {
        val episodeSearches = mutableListOf<List<Long>>()
        override suspend fun getWantedMissing(): JsonArray = missing
        override suspend fun getQueue(pageSize: Int): JsonArray = queue
        override suspend fun triggerEpisodeSearch(episodeIds: List<Long>): JsonObject {
            episodeSearches += episodeIds; return buildJsonObject {}
        }
    }

    private fun rec(seriesId: Long, episodeId: Long, airDate: String): JsonObject =
        buildJsonObject {
            put("seriesId", JsonPrimitive(seriesId))
            put("id", JsonPrimitive(episodeId))
            put("airDateUtc", JsonPrimitive(airDate))
            put("seasonNumber", JsonPrimitive(1))
        }

    @Test fun follow_up_fires_for_p1_grab_with_next_2_episodes() = runTest {
        val missing = buildJsonArray {
            // S04E07 and S04E08 are next; S04E06 was just grabbed
            add(rec(7L, 707L, "2024-04-07"))
            add(rec(7L, 708L, "2024-04-08"))
            add(rec(7L, 709L, "2024-04-09"))   // 3rd, dropped by cap=2
        }
        val sonarr = FakeSonarr(missing)
        val db = freshDb()
        val event = OnGrabEvent(
            seriesId = 7L, seriesTitle = "Slime", tvdbId = 0L,
            episodeIds = listOf(706L), downloadClient = "sab", downloadId = "x", airDate = null,
        )

        runOnGrabFollowup(
            event = event, priority = 1, sonarr = sonarr, db = db,
            followupCap = 2, cooldownSeconds = 1800L, nowEpochSeconds = 1_700_000_000L,
        )

        assertEquals(listOf(listOf(707L, 708L)), sonarr.episodeSearches)
        assertEquals(setOf(707L, 708L), db.listP1P2AttemptedSince(0L).toSet())
    }

    @Test fun follow_up_does_not_fire_for_p3() = runTest {
        val sonarr = FakeSonarr(buildJsonArray { add(rec(7L, 707L, "2024-04-07")) })
        val db = freshDb()
        runOnGrabFollowup(
            event = OnGrabEvent(7L, "x", 0L, listOf(706L), "sab", "x", null),
            priority = 3, sonarr = sonarr, db = db,
            followupCap = 2, cooldownSeconds = 1800L, nowEpochSeconds = 1L,
        )
        assertTrue(sonarr.episodeSearches.isEmpty())
    }

    @Test fun follow_up_excludes_queued_and_cooldown_and_just_grabbed() = runTest {
        val missing = buildJsonArray {
            add(rec(7L, 707L, "2024-04-07"))     // in queue → skip
            add(rec(7L, 708L, "2024-04-08"))     // in cooldown → skip
            add(rec(7L, 709L, "2024-04-09"))     // OK
            add(rec(7L, 710L, "2024-04-10"))     // OK
        }
        val queue = buildJsonArray {
            add(buildJsonObject {
                put("episodeId", JsonPrimitive(707L))
                put("seriesId", JsonPrimitive(7L))
            })
        }
        val sonarr = FakeSonarr(missing, queue)
        val db = freshDb()
        val now = 1_700_000_000L
        db.upsertP1P2Attempt(708L, now - 60)     // recent, inside cooldown

        runOnGrabFollowup(
            event = OnGrabEvent(7L, "x", 0L, listOf(706L), "sab", "x", null),
            priority = 2, sonarr = sonarr, db = db,
            followupCap = 2, cooldownSeconds = 1800L, nowEpochSeconds = now,
        )
        assertEquals(listOf(listOf(709L, 710L)), sonarr.episodeSearches)
    }

    @Test fun follow_up_no_op_when_cap_is_zero() = runTest {
        val sonarr = FakeSonarr(buildJsonArray { add(rec(7L, 707L, "2024-04-07")) })
        val db = freshDb()
        runOnGrabFollowup(
            event = OnGrabEvent(7L, "x", 0L, listOf(706L), "sab", "x", null),
            priority = 1, sonarr = sonarr, db = db,
            followupCap = 0, cooldownSeconds = 1800L, nowEpochSeconds = 1L,
        )
        assertTrue(sonarr.episodeSearches.isEmpty())
    }

    @Test fun follow_up_filters_other_series() = runTest {
        val missing = buildJsonArray {
            add(rec(7L, 707L, "2024-04-07"))     // our series
            add(rec(9L, 909L, "2024-04-07"))     // different series
        }
        val sonarr = FakeSonarr(missing)
        val db = freshDb()
        runOnGrabFollowup(
            event = OnGrabEvent(7L, "x", 0L, listOf(706L), "sab", "x", null),
            priority = 1, sonarr = sonarr, db = db,
            followupCap = 2, cooldownSeconds = 1800L, nowEpochSeconds = 1L,
        )
        assertEquals(listOf(listOf(707L)), sonarr.episodeSearches)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
./prioritarr/gradlew -p prioritarr/backend test --tests "*.OnGrabFollowupTest" -i
```
Expected: FAIL — `runOnGrabFollowup` unresolved reference.

- [ ] **Step 3: Implement `runOnGrabFollowup`**

Create a new file `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/webhooks/OnGrabFollowup.kt`:

```kotlin
package org.yoshiz.app.prioritarr.backend.webhooks

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.database.Database
import org.yoshiz.app.prioritarr.backend.sweep.toEpisodeIdSet

private val logger = LoggerFactory.getLogger("org.yoshiz.app.prioritarr.backend.webhooks.followup")

/**
 * Side effect of an On-Grab event for a P1/P2 series — searches the
 * next [followupCap] oldest missing episodes of the same series,
 * skipping the just-grabbed episode IDs, anything already in Sonarr's
 * queue, and anything inside the P1/P2 cooldown window. Records cooldown
 * + audit row for every episode it fires. Never throws — failures are
 * warn-logged because the webhook response has already been sent.
 */
suspend fun runOnGrabFollowup(
    event: OnGrabEvent,
    priority: Int,
    sonarr: SonarrClient,
    db: Database,
    followupCap: Int,
    cooldownSeconds: Long,
    nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
) {
    if (priority !in 1..2) return
    if (followupCap <= 0) return
    try {
        val missing = sonarr.getWantedMissing()
        val queueIds = runCatching { sonarr.getQueue().toEpisodeIdSet() }.getOrDefault(emptySet())
        val cooldownIds = db.listP1P2AttemptedSince(nowEpochSeconds - cooldownSeconds).toSet()
        val grabbedIds = event.episodeIds.toSet()

        val candidates = missing.mapNotNull { row ->
            val o = row.jsonObject
            val sid = o["seriesId"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            if (sid != event.seriesId) return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            if (id in grabbedIds || id in queueIds || id in cooldownIds) return@mapNotNull null
            val air = o["airDateUtc"]?.jsonPrimitive?.contentOrNull ?: "9999"
            id to air
        }
            .sortedBy { it.second }
            .take(followupCap)
            .map { it.first }

        if (candidates.isEmpty()) return

        sonarr.triggerEpisodeSearch(candidates)
        candidates.forEach { db.upsertP1P2Attempt(it, nowEpochSeconds) }
        db.appendAudit(
            action = "ongrab_followup",
            seriesId = event.seriesId,
            client = null, clientId = null,
            details = buildJsonObject {
                put("episode_ids", buildJsonArray { candidates.forEach { add(it) } })
                put("priority", priority)
            },
        )
        logger.info("[ongrab-followup] series {} fired EpisodeSearch for {}", event.seriesId, candidates)
    } catch (e: Exception) {
        logger.warn("[ongrab-followup] series {} failed: {}", event.seriesId, e.message)
    }
}
```

- [ ] **Step 4: Wire the call into the Grab branch**

In `app/Module.kt`, find the `"Grab"` branch (line ~172). After the `if (processed) { call.respond(...) } else { call.respond(...) }` block, BEFORE leaving the `"Grab" ->` arm, add:

```kotlin
                    if (processed) {
                        val s = liveSettings(state.db, state.settings)
                        application.launch {
                            org.yoshiz.app.prioritarr.backend.webhooks.runOnGrabFollowup(
                                event = event,
                                priority = priorityResult.priority,
                                sonarr = state.sonarr,
                                db = state.db,
                                followupCap = s.intervals.backfillP1P2FollowupEpisodes,
                                cooldownSeconds = s.intervals.backfillP1P2CooldownMinutes * 60L,
                            )
                        }
                    }
```

`application` is the Ktor `Application` reference available inside a route handler; it implements `CoroutineScope`. The launched coroutine runs on the supervisor scope, so a thrown exception inside `runOnGrabFollowup` (already caught internally) will not propagate.

Add the import at the top of `Module.kt`:

```kotlin
import io.ktor.server.application.application
import kotlinx.coroutines.launch
```

(If they're already imported, skip.)

- [ ] **Step 5: Run tests to verify they pass**

```
./prioritarr/gradlew -p prioritarr/backend test --tests "*.OnGrabFollowupTest" -i
```
Expected: PASS (5 tests green).

Then run the full backend suite:
```
./prioritarr/gradlew -p prioritarr/backend test
```
Expected: all green.

- [ ] **Step 6: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/webhooks/OnGrabFollowup.kt prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/app/Module.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/webhooks/OnGrabFollowupTest.kt
git commit -m "feat(webhooks): fire EpisodeSearch on P1/P2 grab for next missing"
```

---

### Task 10: Clear cooldown on Sonarr `Download` webhook

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/app/Module.kt`

When Sonarr imports an episode, drop its cooldown row so a future re-monitor (rare) starts fresh. No new test — the behaviour is covered by Task 3's `clear_removes_a_single_episode` for the wrapper and is too small to integrate-test independently.

- [ ] **Step 1: Add the clear call**

In `Module.kt`'s `"Download"` branch (line ~190-205), after `state.db.invalidatePriorityCache(seriesId)`:

```kotlin
                "Download" -> {
                    val seriesId = (payload["series"] as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    val episodes = (payload["episodes"] as? kotlinx.serialization.json.JsonArray).orEmpty()
                        .mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull?.toLongOrNull() }
                    if (seriesId != null) {
                        state.db.invalidatePriorityCache(seriesId)
                        episodes.forEach { state.db.clearP1P2Attempt(it) }
                        state.eventBus.publish(
                            "episode-imported",
                            kotlinx.serialization.json.Json.parseToJsonElement(
                                """{"series_id":$seriesId,"episode_ids":${episodes}}""",
                            ),
                        )
                        logger.info("[sonarr-webhook] Download: series {} episodes {}", seriesId, episodes)
                    }
                    call.respond(OnGrabIgnored(eventType = eventType))
                }
```

- [ ] **Step 2: Compile + run full backend tests**

```
./prioritarr/gradlew -p prioritarr/backend test
```
Expected: all green.

- [ ] **Step 3: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/app/Module.kt
git commit -m "feat(webhooks): clear P1P2 cooldown on Download/import"
```

---

### Task 11: Frontend — surface the three new settings

**Files:**
- Modify: `prioritarr/frontend/src/lib/jobs.tsx`

The settings panel is driven by the declarative `settings` array on each job entry — no React work needed.

- [ ] **Step 1: Add the rows**

In `jobs.tsx`, find the `backfill-sweep` block (line ~224-236) and replace its `settings` array:

```tsx
    settings: [
      { key: 'backfillMaxSearchesPerSweep', label: 'Max searches per sweep (P3/P4)', type: 'number', min: 1, step: 1 },
      { key: 'backfillDelayBetweenSearchesSeconds', label: 'Delay between searches (sec)', type: 'number', min: 0, step: 1 },
      { key: 'backfillP1P2MaxPerSweep', label: 'P1/P2 max per sweep', type: 'number', min: 0, step: 1 },
      { key: 'backfillP1P2CooldownMinutes', label: 'P1/P2 cooldown (min)', type: 'number', min: 1, step: 1 },
      { key: 'backfillP1P2FollowupEpisodes', label: 'P1/P2 on-grab follow-up (eps)', type: 'number', min: 0, step: 1 },
    ],
```

- [ ] **Step 2: Verify the frontend builds**

```
cd prioritarr/frontend && npm run build
```
Expected: BUILD SUCCESSFUL. If a `TypeScript` error references a missing field on the Intervals DTO, check `frontend/src/lib/api.ts` (or wherever the Intervals shape is declared) and add the three field names with `number` type.

- [ ] **Step 3: Smoke-test the UI**

If `npm run dev` works in this environment, run it and verify the three new rows appear under Settings → Background jobs → Backfill sweep with editable number inputs.

- [ ] **Step 4: Commit**

```
git add prioritarr/frontend/src/lib/jobs.tsx
# Plus any api.ts changes from Step 2
git commit -m "feat(ui): surface P1/P2 backfill settings in backfill-sweep panel"
```

---

### Task 12: README YAML reference

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add the new keys**

In `README.md`, find the `intervals:` YAML block under "Configuration reference" (line ~256-263) and extend:

```yaml
intervals:
  reconcile_minutes: 15
  backfill_sweep_hours: 2
  cutoff_sweep_hours: 24
  backfill_max_searches_per_sweep: 10
  cutoff_max_searches_per_sweep: 5
  backfill_delay_between_searches_seconds: 30
  backfill_p1_p2_max_per_sweep: 20          # P1/P2 episode-search budget per sweep
  backfill_p1_p2_cooldown_minutes: 30       # min gap between P1/P2 episode searches
  backfill_p1_p2_followup_episodes: 2       # on-grab follow-up search size
```

Then add a short paragraph in the "Background jobs" or "Queue enforcement" section noting the P1/P2 fast-grab behaviour, linking to the spec.

- [ ] **Step 2: Commit**

```
git add README.md
git commit -m "docs(readme): document P1/P2 fast-grab YAML keys"
```

---

## Self-review

**Spec coverage check:**
- §1a Boot ordering — Tasks 4 (scheduler fix) + 5 (flag wiring). ✓
- §1b Pass A1 episode-search — Tasks 6 (planner) + 7 (runner) + 8 (sweep integration). ✓
- §1c Queue-skip — Task 8 (`toEpisodeIdSet` + plumb into planner). ✓
- §1d Per-episode cooldown — Task 3 (schema + wrappers); read+write in Tasks 7, 8, 9. ✓
- §1e Dedicated P1/P2 budget — Tasks 1 (settings) + 8 (consumed by sweep). ✓
- §1f On-grab follow-up — Task 9 (logic) + Task 10 (cooldown clear on import). ✓
- Settings & config plumbing — Tasks 1, 2, 11, 12. ✓
- Testing — every backend task includes failing-test → passing-test loop; integration coverage in Task 8 + Task 9. ✓

**Placeholder scan:**
- "If `parseSettingsFromYamlString` doesn't exist" (Task 1) — concrete fallback given.
- "Check whether the codebase has an existing fake `SonarrClient`" (Task 7) — concrete fallback given.
- "Note on `FakePriority`" (Task 8) — explicit seam option described.
- No TBDs, all step bodies contain runnable code or exact instructions.

**Type consistency:**
- `buildP1P2Candidates(records, priorityBySeriesId, queuedEpisodeIds, cooldownEpisodeIds, perSeriesCap)` — same signature across Tasks 6, 8.
- `runP1P2EpisodePass(candidates, sonarr, db, budget, delaySeconds, dryRun, nowEpochSeconds)` — same across Tasks 7, 8.
- `runOnGrabFollowup(event, priority, sonarr, db, followupCap, cooldownSeconds, nowEpochSeconds)` — Task 9.
- DB wrappers `upsertP1P2Attempt(Long, Long)`, `listP1P2AttemptedSince(Long): List<Long>`, `clearP1P2Attempt(Long)` — consistent in Tasks 3, 7, 8, 9, 10.
- `Intervals.backfillP1P2MaxPerSweep` / `backfillP1P2CooldownMinutes` / `backfillP1P2FollowupEpisodes` — consistent in Tasks 1, 2, 8, 9, 11, 12.
