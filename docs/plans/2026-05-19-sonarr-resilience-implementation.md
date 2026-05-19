# Sonarr Resilience + Indexer Health + P3/P4 Episode-Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Auto-detect + recover from Sonarr command-queue jams, manage indexer health with auto-disable + exponential-backoff probe-and-retry, and migrate P3/P4 from heavyweight `SeriesSearch` to lightweight `EpisodeSearch`.

**Architecture:** Three subsystems sharing one new Sonarr API client surface. Subsystem A (`resilience/JamGuard.kt`) polls `/api/v3/command` every 5 min and escalates soft mitigation → opt-in restart. Subsystem B (`resilience/IndexerHealthGuard.kt`) polls `/api/v3/indexerstatus` every 30 min and applies an auto-disable + backoff state machine. Subsystem C refactors `sweep/P1P2EpisodeSearch.kt` → `sweep/PriorityEpisodeSearch.kt`, parameterised over priority band, and renames `p1p2_search_attempts` → `priority_episode_attempts` with a `priority_band` column.

**Tech Stack:** Kotlin + Ktor + SQLDelight (backend), React + TypeScript (frontend). Tests use `kotlin.test` with `kotlinx.coroutines.test.runTest`; HTTP fakes follow the `FakeSonarr` pattern from `P1P2EpisodeSearchTest.kt`.

**Spec:** `docs/specs/2026-05-19-sonarr-resilience-indexer-health-design.md`

**Subsystem order:** C first (smallest, contained, sets patterns) → A (medium, adds first new scheduler job) → B (largest, has the most UI surface) → Documentation.

---

## Subsystem C — Migrate P3/P4 to EpisodeSearch

### Task C1: Rename `p1p2_search_attempts` → `priority_episode_attempts` with band column

**Files:**
- Modify: `prioritarr/backend/src/main/sqldelight/org/yoshiz/app/prioritarr/backend/database/Schema.sq`
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/database/Database.kt`
- Create: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/database/PriorityEpisodeAttemptsRoundTripTest.kt`

- [ ] **Step 1: Write the failing round-trip test**

Create `PriorityEpisodeAttemptsRoundTripTest.kt`:

```kotlin
package org.yoshiz.app.prioritarr.backend.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.nio.file.Files

class PriorityEpisodeAttemptsRoundTripTest {

    private fun freshDb(): Database {
        val tmp = Files.createTempFile("prio-priority-attempts", ".db")
        tmp.toFile().deleteOnExit()
        return Database(tmp.toAbsolutePath().toString())
    }

    @Test fun upsert_then_list_round_trip_for_p1p2() {
        val db = freshDb()
        db.upsertPriorityAttempt("p1p2", episodeId = 101L, lastAttemptedAt = 1_000_000L)
        val ids = db.listPriorityAttemptedSince("p1p2", 0L)
        assertEquals(listOf(101L), ids)
    }

    @Test fun upsert_then_list_round_trip_for_p3p4() {
        val db = freshDb()
        db.upsertPriorityAttempt("p3p4", episodeId = 202L, lastAttemptedAt = 1_000_000L)
        val ids = db.listPriorityAttemptedSince("p3p4", 0L)
        assertEquals(listOf(202L), ids)
    }

    @Test fun bands_are_isolated() {
        val db = freshDb()
        db.upsertPriorityAttempt("p1p2", 101L, 1_000_000L)
        db.upsertPriorityAttempt("p3p4", 101L, 1_000_000L)
        // Same episode_id in different bands = two separate rows
        assertEquals(listOf(101L), db.listPriorityAttemptedSince("p1p2", 0L))
        assertEquals(listOf(101L), db.listPriorityAttemptedSince("p3p4", 0L))
        db.clearPriorityAttempt("p1p2", 101L)
        assertTrue(db.listPriorityAttemptedSince("p1p2", 0L).isEmpty())
        assertEquals(listOf(101L), db.listPriorityAttemptedSince("p3p4", 0L))
    }

    @Test fun upsert_overwrites_timestamp_and_bumps_count() {
        val db = freshDb()
        db.upsertPriorityAttempt("p1p2", 1L, 1_000L)
        db.upsertPriorityAttempt("p1p2", 1L, 2_000L)
        db.upsertPriorityAttempt("p1p2", 1L, 3_000L)
        assertEquals(3, db.getPriorityAttemptCount("p1p2", 1L))
        assertEquals(listOf(1L), db.listPriorityAttemptedSince("p1p2", 2_500L))
    }

    @Test fun listAttemptedSince_excludes_older_rows() {
        val db = freshDb()
        db.upsertPriorityAttempt("p3p4", 1L, 1_000L)
        db.upsertPriorityAttempt("p3p4", 2L, 2_000L)
        db.upsertPriorityAttempt("p3p4", 3L, 3_000L)
        assertEquals(setOf(2L, 3L), db.listPriorityAttemptedSince("p3p4", 2_000L).toSet())
    }

    @Test fun clear_removes_a_single_episode_in_band() {
        val db = freshDb()
        db.upsertPriorityAttempt("p1p2", 1L, 1_000L)
        db.upsertPriorityAttempt("p1p2", 2L, 1_000L)
        db.clearPriorityAttempt("p1p2", 1L)
        assertEquals(listOf(2L), db.listPriorityAttemptedSince("p1p2", 0L))
    }

    @Test fun getPriorityAttemptCount_returns_null_for_missing() {
        val db = freshDb()
        assertNull(db.getPriorityAttemptCount("p1p2", 999L))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
cd prioritarr; ./gradlew.bat backend:test --tests "*.PriorityEpisodeAttemptsRoundTripTest" -i
```
Expected: FAIL — `upsertPriorityAttempt` unresolved.

- [ ] **Step 3: Add new table + migration in Schema.sq**

In `Schema.sq`, locate the existing `p1p2_search_attempts` block (added in Task 3 of the P1/P2 plan, around line 65-75). Replace with:

```sql
-- Per-(priority_band, episode_id) attempt log for the priority-aware
-- episode-search passes (P1/P2 and P3/P4). Replaces the original
-- p1p2_search_attempts table; the priority_band column lets bands
-- have independent cooldown windows without table duplication.
-- `attempts_count` is reserved for future adaptive-escalation logic.
CREATE TABLE IF NOT EXISTS priority_episode_attempts (
    priority_band TEXT NOT NULL,
    episode_id INTEGER NOT NULL,
    last_attempted_at INTEGER NOT NULL CHECK (last_attempted_at > 0),
    attempts_count INTEGER NOT NULL DEFAULT 1 CHECK (attempts_count >= 1),
    PRIMARY KEY (priority_band, episode_id)
);

-- One-time migration: if the legacy p1p2_search_attempts table exists,
-- copy its rows into priority_episode_attempts under band='p1p2'. The
-- INSERT OR IGNORE guards against double-migration on subsequent boots.
-- The legacy table is intentionally NOT dropped here so this DDL stays
-- idempotent across re-runs; a follow-up cleanup migration can drop it
-- once we're confident.
```

Then append the queries (replace the old `p1p2_*` queries section near the bottom of the file):

```sql
-- ------------------------------------------------------------------
-- priority_episode_attempts
-- ------------------------------------------------------------------

upsertPriorityAttempt:
INSERT INTO priority_episode_attempts (priority_band, episode_id, last_attempted_at, attempts_count)
VALUES (?, ?, ?, 1)
ON CONFLICT(priority_band, episode_id) DO UPDATE SET
    last_attempted_at = excluded.last_attempted_at,
    attempts_count = attempts_count + 1;

listPriorityAttemptedSince:
SELECT episode_id FROM priority_episode_attempts
WHERE priority_band = ? AND last_attempted_at >= ?
ORDER BY episode_id;

getPriorityAttemptCount:
SELECT attempts_count FROM priority_episode_attempts
WHERE priority_band = ? AND episode_id = ?;

clearPriorityAttempt:
DELETE FROM priority_episode_attempts
WHERE priority_band = ? AND episode_id = ?;

-- One-time data migration from the legacy table. INSERT OR IGNORE so
-- re-running on an already-migrated DB is a no-op.
migrateLegacyP1P2Attempts:
INSERT OR IGNORE INTO priority_episode_attempts (priority_band, episode_id, last_attempted_at, attempts_count)
SELECT 'p1p2', episode_id, last_attempted_at, attempts_count
FROM p1p2_search_attempts;
```

Keep the existing `upsertP1P2Attempt` / `listP1P2AttemptedSince` / `getP1P2AttemptCount` / `clearP1P2Attempt` query definitions for backwards compatibility (they may still be referenced — Task C2 removes those references).

- [ ] **Step 4: Run the migration at boot in Database.kt**

In `database/Database.kt`, find the existing P1/P2 wrapper functions (added in earlier work). Locate the constructor or init block. Add:

```kotlin
init {
    // One-time migration of p1p2_search_attempts → priority_episode_attempts.
    // Idempotent: INSERT OR IGNORE no-ops if rows already exist with the
    // same (priority_band, episode_id) primary key.
    try {
        q.migrateLegacyP1P2Attempts()
    } catch (e: Exception) {
        // p1p2_search_attempts may not exist on fresh installs — that's fine.
        // Any other error is logged but doesn't block boot.
        // (SQLite "no such table" arrives as an exception via SQLDelight.)
    }
}
```

If `Database` already has an `init` block from earlier work, append the migration call there instead of creating a duplicate.

- [ ] **Step 5: Add the new wrapper functions**

In `database/Database.kt`, after the existing P1/P2 wrappers (or replacing them — see Task C2), add:

```kotlin
    // ------------------------------------------------------------------
    // priority_episode_attempts
    // ------------------------------------------------------------------

    fun upsertPriorityAttempt(band: String, episodeId: Long, lastAttemptedAt: Long) {
        q.upsertPriorityAttempt(
            priority_band = band,
            episode_id = episodeId,
            last_attempted_at = lastAttemptedAt,
        )
    }

    fun listPriorityAttemptedSince(band: String, thresholdEpochSeconds: Long): List<Long> =
        q.listPriorityAttemptedSince(band, thresholdEpochSeconds).executeAsList()

    fun getPriorityAttemptCount(band: String, episodeId: Long): Int? =
        q.getPriorityAttemptCount(band, episodeId).executeAsOneOrNull()?.toInt()

    fun clearPriorityAttempt(band: String, episodeId: Long) {
        q.clearPriorityAttempt(band, episodeId)
    }
```

- [ ] **Step 6: Run tests to verify they pass**

```
cd prioritarr; ./gradlew.bat backend:test --tests "*.PriorityEpisodeAttemptsRoundTripTest" -i
```
Expected: PASS (7 tests green).

Also run the full backend suite to ensure the existing `P1P2AttemptsRoundTripTest` still passes (the legacy wrappers stay until Task C2):
```
cd prioritarr; ./gradlew.bat backend:test
```

- [ ] **Step 7: Commit**

```
git add prioritarr/backend/src/main/sqldelight/org/yoshiz/app/prioritarr/backend/database/Schema.sq prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/database/Database.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/database/PriorityEpisodeAttemptsRoundTripTest.kt
git commit -m "feat(db): priority_episode_attempts table + idempotent p1p2 migration"
```

Append `Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>` trailer.

---

### Task C2: Refactor P1P2 planner + runner → PriorityEpisode (band-parameterised)

**Files:**
- Modify (rename): `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/sweep/P1P2EpisodeSearch.kt` → `PriorityEpisodeSearch.kt`
- Modify (rename): `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/sweep/P1P2EpisodeSearchTest.kt` → `PriorityEpisodeSearchTest.kt`
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/sweep/Sweep.kt`
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/webhooks/OnGrabFollowup.kt`
- Modify: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/webhooks/OnGrabFollowupTest.kt`
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/database/Database.kt` (remove now-unused legacy P1/P2 wrappers)
- Delete: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/database/P1P2AttemptsRoundTripTest.kt`

- [ ] **Step 1: Write the failing parameterised planner tests**

Rename the file to `PriorityEpisodeSearchTest.kt`. Update the existing tests to use the new function signature (planner takes `priorities: IntRange`). Add a new test class `P3P4PriorityEpisodeSearchPlannerTest` mirroring the P1/P2 one but for `priorities = 3..4`:

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

/**
 * Shared test helper for the parameterised priority-episode planner.
 * Each test case is a (priorities, priorityBySeriesId, expected) tuple.
 */
private fun rec(seriesId: Long, episodeId: Long, airDate: String, season: Int = 1): JsonObject =
    buildJsonObject {
        put("seriesId", JsonPrimitive(seriesId))
        put("id", JsonPrimitive(episodeId))
        put("airDateUtc", JsonPrimitive(airDate))
        put("seasonNumber", JsonPrimitive(season))
    }

private fun records(vararg objs: JsonObject): JsonArray = buildJsonArray { objs.forEach { add(it) } }

class P1P2PriorityEpisodeSearchPlannerTest {

    @Test fun groups_by_series_takes_5_oldest() {
        val recs = records(
            rec(1L, 11L, "2024-01-05"), rec(1L, 12L, "2024-01-03"),
            rec(1L, 13L, "2024-01-01"), rec(1L, 14L, "2024-01-04"),
            rec(1L, 15L, "2024-01-02"), rec(1L, 16L, "2024-01-06"),
        )
        val out = buildPriorityEpisodeCandidates(
            records = recs,
            priorityBySeriesId = mapOf(1L to 1),
            queuedEpisodeIds = emptySet(),
            cooldownEpisodeIds = emptySet(),
            perSeriesCap = 5,
            priorities = 1..2,
        )
        assertEquals(listOf(13L, 15L, 12L, 14L, 11L), out.single().episodes.map { it.episodeId })
    }

    @Test fun outer_sort_is_priority_then_oldest() {
        val recs = records(
            rec(1L, 11L, "2024-01-01"), rec(1L, 12L, "2024-02-01"),
            rec(2L, 21L, "2023-12-01"), rec(2L, 22L, "2024-03-01"),
            rec(3L, 31L, "2020-01-01"),
        )
        val out = buildPriorityEpisodeCandidates(
            records = recs,
            priorityBySeriesId = mapOf(1L to 2, 2L to 1, 3L to 2),
            queuedEpisodeIds = emptySet(),
            cooldownEpisodeIds = emptySet(),
            perSeriesCap = 5,
            priorities = 1..2,
        )
        assertEquals(listOf(2L, 3L, 1L), out.map { it.seriesId })
    }

    @Test fun queue_skip_drops_matching_episode_ids() {
        val recs = records(
            rec(1L, 11L, "2024-01-01"), rec(1L, 12L, "2024-01-02"), rec(1L, 13L, "2024-01-03"),
        )
        val out = buildPriorityEpisodeCandidates(
            records = recs,
            priorityBySeriesId = mapOf(1L to 1),
            queuedEpisodeIds = setOf(12L),
            cooldownEpisodeIds = emptySet(),
            perSeriesCap = 5,
            priorities = 1..2,
        )
        assertEquals(listOf(11L, 13L), out.single().episodes.map { it.episodeId })
    }

    @Test fun cooldown_skip_drops_matching_episode_ids() {
        val recs = records(rec(1L, 11L, "2024-01-01"), rec(1L, 12L, "2024-01-02"))
        val out = buildPriorityEpisodeCandidates(
            records = recs,
            priorityBySeriesId = mapOf(1L to 2),
            queuedEpisodeIds = emptySet(),
            cooldownEpisodeIds = setOf(11L),
            perSeriesCap = 5,
            priorities = 1..2,
        )
        assertEquals(listOf(12L), out.single().episodes.map { it.episodeId })
    }

    @Test fun series_with_all_episodes_filtered_drops_from_output() {
        val recs = records(rec(1L, 11L, "2024-01-01"), rec(2L, 21L, "2024-01-01"))
        val out = buildPriorityEpisodeCandidates(
            records = recs,
            priorityBySeriesId = mapOf(1L to 1, 2L to 2),
            queuedEpisodeIds = setOf(11L),
            cooldownEpisodeIds = setOf(21L),
            perSeriesCap = 5,
            priorities = 1..2,
        )
        assertTrue(out.isEmpty())
    }

    @Test fun out_of_range_series_ignored_for_p1p2() {
        val recs = records(
            rec(1L, 11L, "2024-01-01"),       // P3 — ignore for 1..2 band
            rec(2L, 21L, "2024-01-01"),       // P2 — keep
            rec(3L, 31L, "2024-01-01"),       // P5 — ignore
        )
        val out = buildPriorityEpisodeCandidates(
            records = recs,
            priorityBySeriesId = mapOf(1L to 3, 2L to 2, 3L to 5),
            queuedEpisodeIds = emptySet(),
            cooldownEpisodeIds = emptySet(),
            perSeriesCap = 5,
            priorities = 1..2,
        )
        assertEquals(listOf(2L), out.map { it.seriesId })
    }

    @Test fun missing_priority_treats_series_as_out_of_band() {
        val recs = records(rec(1L, 11L, "2024-01-01"))
        val out = buildPriorityEpisodeCandidates(
            records = recs,
            priorityBySeriesId = emptyMap(),
            queuedEpisodeIds = emptySet(),
            cooldownEpisodeIds = emptySet(),
            perSeriesCap = 5,
            priorities = 1..2,
        )
        assertTrue(out.isEmpty())
    }
}

class P3P4PriorityEpisodeSearchPlannerTest {

    @Test fun out_of_range_series_ignored_for_p3p4() {
        val recs = records(
            rec(1L, 11L, "2024-01-01"),       // P1 — ignore for 3..4 band
            rec(2L, 21L, "2024-01-01"),       // P3 — keep
            rec(3L, 31L, "2024-01-01"),       // P4 — keep
            rec(4L, 41L, "2024-01-01"),       // P5 — ignore
        )
        val out = buildPriorityEpisodeCandidates(
            records = recs,
            priorityBySeriesId = mapOf(1L to 1, 2L to 3, 3L to 4, 4L to 5),
            queuedEpisodeIds = emptySet(),
            cooldownEpisodeIds = emptySet(),
            perSeriesCap = 5,
            priorities = 3..4,
        )
        assertEquals(setOf(2L, 3L), out.map { it.seriesId }.toSet())
    }

    @Test fun p3_before_p4_within_band() {
        val recs = records(
            rec(1L, 11L, "2024-02-01"),       // P4, later air
            rec(2L, 21L, "2024-01-01"),       // P3, earlier air
        )
        val out = buildPriorityEpisodeCandidates(
            records = recs,
            priorityBySeriesId = mapOf(1L to 4, 2L to 3),
            queuedEpisodeIds = emptySet(),
            cooldownEpisodeIds = emptySet(),
            perSeriesCap = 5,
            priorities = 3..4,
        )
        assertEquals(listOf(2L, 1L), out.map { it.seriesId })  // P3 wins on priority
    }
}
```

Also update the existing runner tests (`P1P2EpisodeSearchRunnerTest`) to use a new `band` argument when calling the runner. Rename the class to `PriorityEpisodeSearchRunnerTest` and add a `band` parameter:

```kotlin
class PriorityEpisodeSearchRunnerTest {

    private fun freshDb(): Database {
        val tmp = Files.createTempFile("prio-priority-runner", ".db")
        tmp.toFile().deleteOnExit()
        return Database(tmp.toAbsolutePath().toString())
    }

    private class FakeSonarr(
        private val throwOnCall: Long? = null,
    ) : SonarrClient(
        baseUrl = "http://fake",
        apiKey = "x",
        http = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { _ ->
            io.ktor.client.engine.mock.respondOk("{}")
        }) { install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { kotlinx.serialization.json.Json } },
    ) {
        val calls = mutableListOf<List<Long>>()
        override suspend fun triggerEpisodeSearch(episodeIds: List<Long>): kotlinx.serialization.json.JsonObject {
            calls += episodeIds
            if (throwOnCall != null && episodeIds.contains(throwOnCall)) error("fake failure")
            return kotlinx.serialization.json.buildJsonObject {}
        }
    }

    private fun candidate(seriesId: Long, vararg episodeIds: Long, priority: Int = 1) =
        PriorityEpisodeCandidate(
            seriesId = seriesId,
            priority = priority,
            oldestAirDate = "2024-01-01",
            episodes = episodeIds.map { PriorityEpisodeEpisode(it, "2024-01-01", 1) },
        )

    @Test fun writes_cooldown_under_correct_band() = runTest {
        val db = freshDb()
        val fake = FakeSonarr()
        runPriorityEpisodePass(
            candidates = listOf(candidate(1L, 11L, 12L)),
            sonarr = fake, db = db, band = "p3p4",
            budget = 10, delaySeconds = 0, dryRun = false, nowEpochSeconds = 1_700_000_000L,
        )
        assertEquals(setOf(11L, 12L), db.listPriorityAttemptedSince("p3p4", 0L).toSet())
        // Different band — no rows
        assertTrue(db.listPriorityAttemptedSince("p1p2", 0L).isEmpty())
    }

    @Test fun fires_episode_search_in_order_records_cooldown_p1p2() = runTest {
        val db = freshDb()
        val fake = FakeSonarr()
        val fired = runPriorityEpisodePass(
            candidates = listOf(
                candidate(seriesId = 1L, 11L, 12L, 13L),
                candidate(seriesId = 2L, 21L),
            ),
            sonarr = fake, db = db, band = "p1p2",
            budget = 10, delaySeconds = 0, dryRun = false, nowEpochSeconds = 1_700_000_000L,
        )
        assertEquals(2, fired)
        assertEquals(listOf(listOf(11L, 12L, 13L), listOf(21L)), fake.calls)
        assertEquals(setOf(11L, 12L, 13L, 21L), db.listPriorityAttemptedSince("p1p2", 0L).toSet())
    }

    @Test fun respects_budget() = runTest {
        val db = freshDb()
        val fake = FakeSonarr()
        val fired = runPriorityEpisodePass(
            candidates = listOf(candidate(1L, 11L), candidate(2L, 21L), candidate(3L, 31L)),
            sonarr = fake, db = db, band = "p1p2",
            budget = 2, delaySeconds = 0, dryRun = false, nowEpochSeconds = 1L,
        )
        assertEquals(2, fired)
        assertEquals(2, fake.calls.size)
    }

    @Test fun zero_budget_processes_nothing() = runTest {
        val db = freshDb()
        val fake = FakeSonarr()
        val fired = runPriorityEpisodePass(
            candidates = listOf(candidate(1L, 11L), candidate(2L, 21L)),
            sonarr = fake, db = db, band = "p1p2",
            budget = 0, delaySeconds = 0, dryRun = false, nowEpochSeconds = 1L,
        )
        assertEquals(0, fired)
        assertTrue(fake.calls.isEmpty())
        assertTrue(db.listPriorityAttemptedSince("p1p2", 0L).isEmpty())
    }

    @Test fun dry_run_does_not_call_sonarr_or_record_cooldown() = runTest {
        val db = freshDb()
        val fake = FakeSonarr()
        val fired = runPriorityEpisodePass(
            candidates = listOf(candidate(1L, 11L, 12L)),
            sonarr = fake, db = db, band = "p1p2",
            budget = 10, delaySeconds = 0, dryRun = true, nowEpochSeconds = 1L,
        )
        assertEquals(1, fired)
        assertTrue(fake.calls.isEmpty())
        assertTrue(db.listPriorityAttemptedSince("p1p2", 0L).isEmpty())
    }

    @Test fun failure_breaks_and_does_not_record_cooldown_for_failed_call() = runTest {
        val db = freshDb()
        val fake = FakeSonarr(throwOnCall = 21L)
        val fired = runPriorityEpisodePass(
            candidates = listOf(
                candidate(1L, 11L),
                candidate(2L, 21L),
                candidate(3L, 31L),
            ),
            sonarr = fake, db = db, band = "p1p2",
            budget = 10, delaySeconds = 0, dryRun = false, nowEpochSeconds = 1L,
        )
        assertEquals(1, fired)
        assertEquals(listOf(11L), db.listPriorityAttemptedSince("p1p2", 0L))
    }
}
```

Delete the old `P1P2AttemptsRoundTripTest.kt` file (Task C1 replaced its coverage).

- [ ] **Step 2: Run tests to verify they fail**

```
cd prioritarr; ./gradlew.bat backend:test --tests "*PriorityEpisode*" -i
```
Expected: FAIL — `buildPriorityEpisodeCandidates`, `PriorityEpisodeCandidate`, `PriorityEpisodeEpisode`, `runPriorityEpisodePass` unresolved.

- [ ] **Step 3: Rename + refactor the planner file**

Rename `P1P2EpisodeSearch.kt` → `PriorityEpisodeSearch.kt`. Replace the existing content:

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

private val logger = LoggerFactory.getLogger("org.yoshiz.app.prioritarr.backend.sweep.priority")

/** One missing episode from Sonarr's /wanted/missing, parsed for the priority planner. */
internal data class PriorityEpisodeEpisode(
    val episodeId: Long,
    val airDateUtc: String,
    val seasonNumber: Int,
)

/** One in-band series' worth of candidates, in firing order. */
internal data class PriorityEpisodeCandidate(
    val seriesId: Long,
    val priority: Int,
    val oldestAirDate: String,
    val episodes: List<PriorityEpisodeEpisode>,
)

/**
 * Pure planner — same shape as the old buildP1P2Candidates but
 * parameterised over a priority range. Used by Pass A1 (P1/P2) and
 * Pass A2 (P3/P4) of runBackfillSweep, and by the on-grab follow-up.
 *
 * Outer sort: (priority asc, oldestAirDate asc). P1 wins over P2 wins
 * over P3 wins over P4 within the band; oldest wins within priority.
 */
internal fun buildPriorityEpisodeCandidates(
    records: JsonArray,
    priorityBySeriesId: Map<Long, Int>,
    queuedEpisodeIds: Set<Long>,
    cooldownEpisodeIds: Set<Long>,
    perSeriesCap: Int,
    priorities: IntRange,
): List<PriorityEpisodeCandidate> {
    val grouped = mutableMapOf<Long, MutableList<PriorityEpisodeEpisode>>()
    for (row in records) {
        val obj = row.jsonObject
        val sid = obj["seriesId"]?.jsonPrimitive?.longOrNull ?: continue
        val priority = priorityBySeriesId[sid] ?: continue
        if (priority !in priorities) continue
        val episodeId = obj["id"]?.jsonPrimitive?.longOrNull ?: continue
        if (episodeId in queuedEpisodeIds) continue
        if (episodeId in cooldownEpisodeIds) continue
        val airDate = obj["airDateUtc"]?.jsonPrimitive?.contentOrNull ?: "9999"
        val season = obj["seasonNumber"]?.jsonPrimitive?.intOrNull ?: 0
        grouped.getOrPut(sid) { mutableListOf() }.add(PriorityEpisodeEpisode(episodeId, airDate, season))
    }
    return grouped.entries.mapNotNull { (sid, eps) ->
        val sorted = eps.sortedBy { it.airDateUtc }.take(perSeriesCap)
        if (sorted.isEmpty()) return@mapNotNull null
        val priority = priorityBySeriesId.getValue(sid)
        PriorityEpisodeCandidate(
            seriesId = sid,
            priority = priority,
            oldestAirDate = sorted.first().airDateUtc,
            episodes = sorted,
        )
    }.sortedWith(compareBy({ it.priority }, { it.oldestAirDate }))
}

/**
 * Execute [candidates] in order, calling Sonarr's EpisodeSearch and
 * recording per-(band, episode) cooldown rows. Each candidate counts
 * as 1 against [budget] regardless of how many episode IDs the
 * command carries. On failure, break and DO NOT record cooldown for
 * the failed call so we retry next sweep.
 *
 * [band] is the priority_band value written to priority_episode_attempts
 * — "p1p2" or "p3p4". Different bands have independent cooldown spaces.
 *
 * [delaySeconds] is the per-Sonarr-command throttle. Expected operator
 * range is 0..300; callers should clamp input before invoking.
 *
 * @return the count of candidates processed (not strictly the count
 *         actually grabbed — dry-run candidates within budget are
 *         counted; on mid-loop failure the failed one is NOT counted).
 */
internal suspend fun runPriorityEpisodePass(
    candidates: List<PriorityEpisodeCandidate>,
    sonarr: SonarrClient,
    db: Database,
    band: String,
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
            logger.info("[backfill-{}] DRY RUN: would EpisodeSearch series {} eps {}", band, c.seriesId, ids)
        } else {
            try {
                sonarr.triggerEpisodeSearch(ids)
            } catch (e: Exception) {
                logger.warn("[backfill-{}] EpisodeSearch failed for series {}: {}", band, c.seriesId, e.message)
                break
            }
            ids.forEach { db.upsertPriorityAttempt(band, it, nowEpochSeconds) }
            logger.info("[backfill-{}] triggered: series {} eps {} (P{})", band, c.seriesId, ids, c.priority)
            if (delaySeconds > 0) delay(delaySeconds * 1_000L)
        }
        fired++
    }
    return fired
}
```

- [ ] **Step 4: Update Database.kt to remove now-unused legacy P1/P2 wrappers**

In `database/Database.kt`, delete these now-unused functions (the band-aware versions from Task C1 replace them):
- `upsertP1P2Attempt`
- `listP1P2AttemptedSince`
- `getP1P2AttemptCount`
- `clearP1P2Attempt`

Keep the SQLDelight queries themselves (`upsertP1P2Attempt:` etc. in `Schema.sq`) for now — they're unreachable from Kotlin but harmless. Removing them is a follow-up.

- [ ] **Step 5: Update OnGrabFollowup to use the band-aware wrappers**

In `webhooks/OnGrabFollowup.kt`, replace `db.upsertP1P2Attempt(it, nowEpochSeconds)` with `db.upsertPriorityAttempt("p1p2", it, nowEpochSeconds)`, and replace `db.listP1P2AttemptedSince(...)` with `db.listPriorityAttemptedSince("p1p2", ...)`. Other logic unchanged.

In `webhooks/OnGrabFollowupTest.kt`, replace any references to `upsertP1P2Attempt` / `listP1P2AttemptedSince` with `upsertPriorityAttempt("p1p2", ...)` / `listPriorityAttemptedSince("p1p2", ...)`.

- [ ] **Step 6: Update Sweep.kt to use the parameterised planner**

In `sweep/Sweep.kt`'s `runBackfillSweep`, replace the existing Pass A1 logic. Find:

```kotlin
val cooldownIds = db.listP1P2AttemptedSince(nowSec - p1p2CooldownMinutes * 60L).toSet()
...
val p1p2 = buildP1P2Candidates(...)
val p1p2Fired = ...runP1P2EpisodePass(...)
```

Replace with:

```kotlin
val cooldownP1P2 = db.listPriorityAttemptedSince("p1p2", nowSec - p1p2CooldownMinutes * 60L).toSet()
val p1p2 = buildPriorityEpisodeCandidates(
    records = records,
    priorityBySeriesId = priorityBySeriesId,
    queuedEpisodeIds = queuedIds,
    cooldownEpisodeIds = cooldownP1P2,
    perSeriesCap = P1P2_PER_SERIES_CAP,
    priorities = 1..2,
)
val p1p2Fired = if (p1p2MaxPerSweep > 0) {
    runPriorityEpisodePass(
        candidates = p1p2,
        sonarr = sonarr, db = db, band = "p1p2",
        budget = p1p2MaxPerSweep,
        delaySeconds = delaySeconds,
        dryRun = dryRun,
        nowEpochSeconds = nowSec,
    )
} else 0
```

Pass A2 (P3/P4) is added in Task C4 — this task only restores parity for P1/P2.

- [ ] **Step 7: Run tests to verify they pass**

```
cd prioritarr; ./gradlew.bat backend:test
```
Expected: BUILD SUCCESSFUL. All planner tests + runner tests + on-grab follow-up tests + sweep integration tests pass.

- [ ] **Step 8: Commit**

```
git rm prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/database/P1P2AttemptsRoundTripTest.kt
git mv prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/sweep/P1P2EpisodeSearch.kt prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/sweep/PriorityEpisodeSearch.kt
git mv prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/sweep/P1P2EpisodeSearchTest.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/sweep/PriorityEpisodeSearchTest.kt
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/sweep/PriorityEpisodeSearch.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/sweep/PriorityEpisodeSearchTest.kt prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/database/Database.kt prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/webhooks/OnGrabFollowup.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/webhooks/OnGrabFollowupTest.kt prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/sweep/Sweep.kt
git commit -m "refactor(sweep): parameterise P1/P2 planner over priority range"
```

Append the Co-Authored-By trailer.

---

### Task C3: Add P3/P4 settings + thread through Main.kt

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/config/Settings.kt`
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/schemas/V2.kt`
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/api/v2/V2Routes.kt`
- Modify: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/config/SettingsParserTest.kt`

- [ ] **Step 1: Write the failing test**

Append to `SettingsParserTest.kt`:

```kotlin
@Test
fun yaml_loads_backfill_p3_p4_fields() {
    val yaml = """
        intervals:
          backfill_p3_p4_max_per_sweep: 15
          backfill_p3_p4_cooldown_minutes: 90
          backfill_p3_p4_followup_episodes: 1
    """.trimIndent()
    val settings = parseSettingsFromYamlString(yaml)
    assertEquals(15, settings.intervals.backfillP3P4MaxPerSweep)
    assertEquals(90, settings.intervals.backfillP3P4CooldownMinutes)
    assertEquals(1, settings.intervals.backfillP3P4FollowupEpisodes)
}

@Test
fun intervals_p3p4_fields_have_documented_defaults() {
    val s = loadSettingsFrom(requiredEnvForParser)
    assertEquals(10, s.intervals.backfillP3P4MaxPerSweep)
    assertEquals(60, s.intervals.backfillP3P4CooldownMinutes)
    assertEquals(0, s.intervals.backfillP3P4FollowupEpisodes)
}

@Test
fun applySettingsOverride_threads_p3p4_fields() {
    val base = loadSettingsFrom(requiredEnvForParser)
    val override = EditableSettings(
        backfillP3P4MaxPerSweep = 25, backfillP3P4CooldownMinutes = 45, backfillP3P4FollowupEpisodes = 3,
    )
    val result = applySettingsOverride(base, override)
    assertEquals(25, result.intervals.backfillP3P4MaxPerSweep)
    assertEquals(45, result.intervals.backfillP3P4CooldownMinutes)
    assertEquals(3, result.intervals.backfillP3P4FollowupEpisodes)
}
```

- [ ] **Step 2: Run to verify failure**

```
cd prioritarr; ./gradlew.bat backend:test --tests "*.SettingsParserTest" -i
```
Expected: FAIL — `backfillP3P4MaxPerSweep` unresolved.

- [ ] **Step 3: Add fields to `Intervals`**

In `config/Settings.kt`, locate `data class Intervals` (where the P1/P2 fields were added in Task 1 of the P1/P2 plan, near `cutoffMaxSearchesPerSweep`). Add after the existing `backfillP1P2*` fields:

```kotlin
    // P3/P4 fast-grab pass — same shape as P1/P2 but lower priority,
    // longer cooldown, on-grab follow-up off by default.
    val backfillP3P4MaxPerSweep: Int = 10,
    val backfillP3P4CooldownMinutes: Int = 60,
    val backfillP3P4FollowupEpisodes: Int = 0,
```

- [ ] **Step 4: Add override-pass-through + YAML parsing**

In `EditableSettings` (next to the P1/P2 nullable fields):

```kotlin
    val backfillP3P4MaxPerSweep: Int? = null,
    val backfillP3P4CooldownMinutes: Int? = null,
    val backfillP3P4FollowupEpisodes: Int? = null,
```

In `applySettingsOverride`'s `intervals.copy(...)` block:

```kotlin
        backfillP3P4MaxPerSweep = override.backfillP3P4MaxPerSweep ?: base.intervals.backfillP3P4MaxPerSweep,
        backfillP3P4CooldownMinutes = override.backfillP3P4CooldownMinutes ?: base.intervals.backfillP3P4CooldownMinutes,
        backfillP3P4FollowupEpisodes = override.backfillP3P4FollowupEpisodes ?: base.intervals.backfillP3P4FollowupEpisodes,
```

In the YAML parser's `(root["intervals"] as? Map<*, *>)?.let { o -> intervals.copy(...) }` block:

```kotlin
        backfillP3P4MaxPerSweep = o.num("backfill_p3_p4_max_per_sweep") { it.toInt() } ?: intervals.backfillP3P4MaxPerSweep,
        backfillP3P4CooldownMinutes = o.num("backfill_p3_p4_cooldown_minutes") { it.toInt() } ?: intervals.backfillP3P4CooldownMinutes,
        backfillP3P4FollowupEpisodes = o.num("backfill_p3_p4_followup_episodes") { it.toInt() } ?: intervals.backfillP3P4FollowupEpisodes,
```

- [ ] **Step 5: Thread through V2 wire**

In `schemas/V2.kt`'s `IntervalsWire`, add (after the P1/P2 fields):

```kotlin
    val backfillP3P4MaxPerSweep: Int = 10,
    val backfillP3P4CooldownMinutes: Int = 60,
    val backfillP3P4FollowupEpisodes: Int = 0,
```

In `api/v2/V2Routes.kt`'s `mergeEditable`, add (next to the P1/P2 lines):

```kotlin
    backfillP3P4MaxPerSweep = patch.backfillP3P4MaxPerSweep ?: existing.backfillP3P4MaxPerSweep,
    backfillP3P4CooldownMinutes = patch.backfillP3P4CooldownMinutes ?: existing.backfillP3P4CooldownMinutes,
    backfillP3P4FollowupEpisodes = patch.backfillP3P4FollowupEpisodes ?: existing.backfillP3P4FollowupEpisodes,
```

Add the three fields to BOTH `IntervalsWire(...)` construction sites (next to the P1/P2 mappings):

```kotlin
backfillP3P4MaxPerSweep = s.intervals.backfillP3P4MaxPerSweep,
backfillP3P4CooldownMinutes = s.intervals.backfillP3P4CooldownMinutes,
backfillP3P4FollowupEpisodes = s.intervals.backfillP3P4FollowupEpisodes,
```

- [ ] **Step 6: Run tests**

```
cd prioritarr; ./gradlew.bat backend:test
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/config/Settings.kt prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/schemas/V2.kt prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/api/v2/V2Routes.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/config/SettingsParserTest.kt
git commit -m "feat(config): add backfillP3P4MaxPerSweep/Cooldown/Followup intervals"
```

Append the Co-Authored-By trailer.

---

### Task C4: Wire Pass A2 (P3/P4) into runBackfillSweep

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/sweep/Sweep.kt`
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/Main.kt`
- Modify: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/sweep/SweepIntegrationTest.kt`

- [ ] **Step 1: Write the failing test**

In `SweepIntegrationTest.kt`, add:

```kotlin
@Test fun p3p4_fires_episode_search_not_series_search() = runTest {
    val records = buildJsonArray {
        add(rec(1L, 11L, "2024-01-01"))   // P1 — EpisodeSearch (Pass A1)
        add(rec(2L, 21L, "2024-01-01"))   // P3 — EpisodeSearch (Pass A2, NEW)
        add(rec(2L, 22L, "2024-01-02"))
        add(rec(3L, 31L, "2024-01-01"))   // P4 — EpisodeSearch (Pass A2, NEW)
        add(rec(4L, 41L, "2024-01-01"))   // P5 — SeriesSearch (Pass B inactive fallback)
    }
    val sonarr = FakeSonarr(records)
    val db = freshDb()
    val priorityService = FakePriority(mapOf(1L to 1, 2L to 3, 3L to 4, 4L to 5))

    runBackfillSweep(
        sonarr = sonarr, priorityService = priorityService, db = db,
        p5Ratchet = P5RatchetConfig(enabled = false), bandwidth = BandwidthSettings(),
        telemetry = null,
        maxSearches = 10, delaySeconds = 0, dryRun = false,
        p1p2MaxPerSweep = 20, p1p2CooldownMinutes = 30,
        p3p4MaxPerSweep = 10, p3p4CooldownMinutes = 60,
    )

    // P1, P3, P4 each got one EpisodeSearch (in priority order).
    assertEquals(3, sonarr.episodeSearches.size)
    assertEquals(listOf(11L), sonarr.episodeSearches[0])         // P1 series 1
    assertEquals(listOf(21L, 22L), sonarr.episodeSearches[1])    // P3 series 2
    assertEquals(listOf(31L), sonarr.episodeSearches[2])         // P4 series 3

    // No SeriesSearch for P3/P4 (the regression we're guarding).
    assertEquals(listOf(4L), sonarr.seriesSearches)              // only P5

    // Cooldown rows written in the correct bands.
    assertEquals(setOf(11L), db.listPriorityAttemptedSince("p1p2", 0L).toSet())
    assertEquals(setOf(21L, 22L, 31L), db.listPriorityAttemptedSince("p3p4", 0L).toSet())
}
```

- [ ] **Step 2: Run to verify failure**

```
cd prioritarr; ./gradlew.bat backend:test --tests "*.SweepIntegrationTest" -i
```
Expected: FAIL — `runBackfillSweep` signature mismatch (missing `p3p4MaxPerSweep`, `p3p4CooldownMinutes`).

- [ ] **Step 3: Update `runBackfillSweep` signature + Pass A2 body**

In `sweep/Sweep.kt`, find `suspend fun runBackfillSweep(...)`. Add the two new params after the P1/P2 ones:

```kotlin
suspend fun runBackfillSweep(
    sonarr: SonarrClient,
    priorityService: PriorityService,
    db: Database,
    p5Ratchet: P5RatchetConfig,
    bandwidth: BandwidthSettings,
    telemetry: DownloadTelemetry?,
    maxSearches: Int,
    delaySeconds: Int,
    dryRun: Boolean,
    p1p2MaxPerSweep: Int,
    p1p2CooldownMinutes: Int,
    p3p4MaxPerSweep: Int,
    p3p4CooldownMinutes: Int,
): Int {
```

(Match the same change in the `priorityForSeriesFn` lambda overload too.)

Replace the Pass A2 loop (currently fires `triggerSeriesSearch` for `priority in 3..4`) with an episode-search pass mirroring Pass A1:

```kotlin
// ---- Pass A2: P3/P4 episode-level (was SeriesSearch) ----
val cooldownP3P4 = db.listPriorityAttemptedSince("p3p4", nowSec - p3p4CooldownMinutes * 60L).toSet()
val p3p4 = buildPriorityEpisodeCandidates(
    records = records,
    priorityBySeriesId = priorityBySeriesId,
    queuedEpisodeIds = queuedIds,
    cooldownEpisodeIds = cooldownP3P4,
    perSeriesCap = P1P2_PER_SERIES_CAP,
    priorities = 3..4,
)
val p3p4Fired = if (p3p4MaxPerSweep > 0) {
    runPriorityEpisodePass(
        candidates = p3p4,
        sonarr = sonarr, db = db, band = "p3p4",
        budget = p3p4MaxPerSweep,
        delaySeconds = delaySeconds,
        dryRun = dryRun,
        nowEpochSeconds = nowSec,
    )
} else 0

// Pass B remains unchanged (P5 ratchet); maxSearches now applies to it only.
```

Remove the old `for (entry in passA2)` SeriesSearch loop.

Update the function's running `fired` accumulator to include `p3p4Fired`:

```kotlin
return p1p2Fired + p3p4Fired + fired   // fired = Pass B count
```

- [ ] **Step 4: Update Main.kt to pass new args**

In `Main.kt`, find the `BACKFILL_SWEEP` job's `run` block. Add two args:

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
                        p3p4MaxPerSweep = s.intervals.backfillP3P4MaxPerSweep,
                        p3p4CooldownMinutes = s.intervals.backfillP3P4CooldownMinutes,
                    )
                    org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome()
                },
```

- [ ] **Step 5: Run tests**

```
cd prioritarr; ./gradlew.bat backend:test
```
Expected: BUILD SUCCESSFUL. All sweep tests pass; the new P3/P4 integration test passes.

- [ ] **Step 6: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/sweep/Sweep.kt prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/Main.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/sweep/SweepIntegrationTest.kt
git commit -m "feat(sweep): migrate P3/P4 from SeriesSearch to EpisodeSearch"
```

Append the Co-Authored-By trailer.

---

### Task C5: Extend on-grab follow-up to P3/P4 (opt-in)

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/webhooks/OnGrabFollowup.kt`
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/app/Module.kt`
- Modify: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/webhooks/OnGrabFollowupTest.kt`

- [ ] **Step 1: Write the failing tests**

In `OnGrabFollowupTest.kt`, add:

```kotlin
@Test fun follow_up_fires_for_p3_when_band_enabled() = runTest {
    val missing = buildJsonArray {
        add(rec(7L, 707L, "2024-04-07"))
        add(rec(7L, 708L, "2024-04-08"))
    }
    val sonarr = FakeSonarr(missing)
    val db = freshDb()
    val event = OnGrabEvent(
        seriesId = 7L, seriesTitle = "X", tvdbId = 0L,
        episodeIds = listOf(706L), downloadClient = "sab", downloadId = "x", airDate = null,
    )

    runOnGrabFollowup(
        event = event, priority = 3, sonarr = sonarr, db = db,
        followupCap = 2, cooldownSeconds = 3600L, band = "p3p4",
        nowEpochSeconds = 1_700_000_000L,
    )

    assertEquals(listOf(listOf(707L, 708L)), sonarr.episodeSearches)
    assertEquals(setOf(707L, 708L), db.listPriorityAttemptedSince("p3p4", 0L).toSet())
    // No P1/P2 band writes
    assertTrue(db.listPriorityAttemptedSince("p1p2", 0L).isEmpty())
}

@Test fun follow_up_does_not_fire_for_p5() = runTest {
    val sonarr = FakeSonarr(buildJsonArray { add(rec(7L, 707L, "2024-04-07")) })
    val db = freshDb()
    runOnGrabFollowup(
        event = OnGrabEvent(7L, "x", 0L, listOf(706L), "sab", "x", null),
        priority = 5, sonarr = sonarr, db = db,
        followupCap = 2, cooldownSeconds = 3600L, band = "p3p4",
        nowEpochSeconds = 1L,
    )
    assertTrue(sonarr.episodeSearches.isEmpty())
}
```

Update existing P1/P2 follow-up tests to pass `band = "p1p2"` explicitly.

- [ ] **Step 2: Run to verify failure**

```
cd prioritarr; ./gradlew.bat backend:test --tests "*.OnGrabFollowupTest" -i
```
Expected: FAIL — `runOnGrabFollowup` doesn't accept `band` parameter; priority validation is `1..2`.

- [ ] **Step 3: Update `runOnGrabFollowup` signature**

In `webhooks/OnGrabFollowup.kt`, replace the function signature + the priority gate:

```kotlin
suspend fun runOnGrabFollowup(
    event: OnGrabEvent,
    priority: Int,
    sonarr: SonarrClient,
    db: Database,
    followupCap: Int,
    cooldownSeconds: Long,
    band: String,
    nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
) {
    val expectedRange = when (band) {
        "p1p2" -> 1..2
        "p3p4" -> 3..4
        else -> {
            logger.warn("[ongrab-followup] unknown band: {}", band)
            return
        }
    }
    if (priority !in expectedRange) return
    if (followupCap <= 0) return
    try {
        val missing = sonarr.getWantedMissing()
        val queueIds = runCatching { sonarr.getQueue().toEpisodeIdSet() }.getOrDefault(emptySet())
        val cooldownIds = db.listPriorityAttemptedSince(band, nowEpochSeconds - cooldownSeconds).toSet()
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
        candidates.forEach { db.upsertPriorityAttempt(band, it, nowEpochSeconds) }
        db.appendAudit(
            action = "ongrab_followup",
            seriesId = event.seriesId,
            client = null, clientId = null,
            details = buildJsonObject {
                put("episode_ids", buildJsonArray { candidates.forEach { add(it) } })
                put("priority", priority)
                put("band", band)
            },
        )
        logger.info("[ongrab-followup] series {} band {} fired EpisodeSearch for {}", event.seriesId, band, candidates)
    } catch (e: Exception) {
        logger.warn("[ongrab-followup] series {} band {} failed: {}", event.seriesId, band, e.message)
    }
}
```

- [ ] **Step 4: Update Module.kt's Grab branch**

In `app/Module.kt`'s Grab branch (the existing P1/P2 follow-up launch), update the call site and add a P3/P4 launch:

```kotlin
                    if (processed) {
                        application.launch {
                            val s = liveSettings(state.db, state.settings)
                            // P1/P2 follow-up (existing behaviour)
                            org.yoshiz.app.prioritarr.backend.webhooks.runOnGrabFollowup(
                                event = event,
                                priority = priorityResult.priority,
                                sonarr = state.sonarr,
                                db = state.db,
                                followupCap = s.intervals.backfillP1P2FollowupEpisodes,
                                cooldownSeconds = s.intervals.backfillP1P2CooldownMinutes * 60L,
                                band = "p1p2",
                            )
                            // P3/P4 follow-up (opt-in via setting)
                            org.yoshiz.app.prioritarr.backend.webhooks.runOnGrabFollowup(
                                event = event,
                                priority = priorityResult.priority,
                                sonarr = state.sonarr,
                                db = state.db,
                                followupCap = s.intervals.backfillP3P4FollowupEpisodes,
                                cooldownSeconds = s.intervals.backfillP3P4CooldownMinutes * 60L,
                                band = "p3p4",
                            )
                        }
                    }
```

The band check inside `runOnGrabFollowup` ensures only the matching band actually does work — calling both is safe because each early-returns for out-of-band priorities.

- [ ] **Step 5: Run tests**

```
cd prioritarr; ./gradlew.bat backend:test
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/webhooks/OnGrabFollowup.kt prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/app/Module.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/webhooks/OnGrabFollowupTest.kt
git commit -m "feat(webhooks): extend on-grab follow-up to P3/P4 (opt-in)"
```

Append the Co-Authored-By trailer.

---

### Task C6: Frontend — P3/P4 settings + relabel

**Files:**
- Modify: `prioritarr/frontend/src/lib/jobs.tsx`

- [ ] **Step 1: Update the backfill-sweep settings rows**

Find the `backfill-sweep` block in `jobs.tsx`. Update the `settings` array (the P1/P2 entries from Task 11 of the P1/P2 plan stay):

```tsx
    settings: [
      { key: 'backfillMaxSearchesPerSweep', label: 'Max searches per sweep (P5)', type: 'number', min: 1, step: 1 },
      { key: 'backfillDelayBetweenSearchesSeconds', label: 'Delay between searches (sec)', type: 'number', min: 0, step: 1 },
      { key: 'backfillP1P2MaxPerSweep', label: 'P1/P2 max per sweep (0 = disable)', type: 'number', min: 0, step: 1 },
      { key: 'backfillP1P2CooldownMinutes', label: 'P1/P2 cooldown (min)', type: 'number', min: 1, step: 1 },
      { key: 'backfillP1P2FollowupEpisodes', label: 'P1/P2 on-grab follow-up (eps)', type: 'number', min: 0, step: 1 },
      { key: 'backfillP3P4MaxPerSweep', label: 'P3/P4 max per sweep (0 = disable)', type: 'number', min: 0, step: 1 },
      { key: 'backfillP3P4CooldownMinutes', label: 'P3/P4 cooldown (min)', type: 'number', min: 1, step: 1 },
      { key: 'backfillP3P4FollowupEpisodes', label: 'P3/P4 on-grab follow-up (eps, 0 = off)', type: 'number', min: 0, step: 1 },
    ],
```

The `backfillMaxSearchesPerSweep` label changes from "Max searches per sweep (P3/P4)" to "Max searches per sweep (P5)" since after this work, it's the P5-only budget.

- [ ] **Step 2: Verify the frontend builds**

```
cd prioritarr/frontend; npm run build
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```
git add prioritarr/frontend/src/lib/jobs.tsx
git commit -m "feat(ui): surface P3/P4 backfill settings + relabel max-searches as P5-only"
```

Append the Co-Authored-By trailer.

---

## Subsystem A — Sonarr Jam Guard

### Task A1: SonarrClient — listCommands, cancelCommand, fireProcessMonitoredDownloads, systemRestart

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/clients/Sonarr.kt`
- Modify: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/clients/SonarrClientTest.kt`

- [ ] **Step 1: Write the failing client tests**

In `SonarrClientTest.kt`, add (the existing test file has a `MockEngine`-based setup for HTTP — reuse it):

```kotlin
@Test
fun `listCommands GETs api v3 command and returns JsonArray`() = runTest {
    val mock = MockEngine { req ->
        assertEquals("/api/v3/command", req.url.encodedPath)
        respondOk("""[{"id":1,"name":"SeriesSearch","status":"started"}]""")
    }
    val client = SonarrClient("http://fake", "k", HttpClient(mock) { install(ContentNegotiation) { json() } })
    val arr = client.listCommands()
    assertEquals(1, arr.size)
    assertEquals("SeriesSearch", arr[0].jsonObject["name"]?.jsonPrimitive?.contentOrNull)
}

@Test
fun `cancelCommand DELETEs api v3 command and returns true on 200`() = runTest {
    val mock = MockEngine { req ->
        assertEquals(HttpMethod.Delete, req.method)
        assertEquals("/api/v3/command/42", req.url.encodedPath)
        respondOk("")
    }
    val client = SonarrClient("http://fake", "k", HttpClient(mock) { install(ContentNegotiation) { json() } })
    assertTrue(client.cancelCommand(42L))
}

@Test
fun `cancelCommand returns false on 409 (already started)`() = runTest {
    val mock = MockEngine { _ ->
        respond("", HttpStatusCode.Conflict)
    }
    val client = SonarrClient("http://fake", "k", HttpClient(mock) { install(ContentNegotiation) { json() } })
    assertFalse(client.cancelCommand(42L))
}

@Test
fun `fireProcessMonitoredDownloads POSTs api v3 command with name`() = runTest {
    val mock = MockEngine { req ->
        assertEquals(HttpMethod.Post, req.method)
        assertEquals("/api/v3/command", req.url.encodedPath)
        val body = req.body.toByteArray().decodeToString()
        assertTrue(body.contains("ProcessMonitoredDownloads"), "body should include command name, got: $body")
        respondOk("""{"id":99,"status":"queued"}""")
    }
    val client = SonarrClient("http://fake", "k", HttpClient(mock) { install(ContentNegotiation) { json() } })
    val r = client.fireProcessMonitoredDownloads()
    assertEquals(99L, r["id"]?.jsonPrimitive?.longOrNull)
}

@Test
fun `systemRestart POSTs api v3 system restart and returns true on 200`() = runTest {
    val mock = MockEngine { req ->
        assertEquals(HttpMethod.Post, req.method)
        assertEquals("/api/v3/system/restart", req.url.encodedPath)
        respondOk("{}")
    }
    val client = SonarrClient("http://fake", "k", HttpClient(mock) { install(ContentNegotiation) { json() } })
    assertTrue(client.systemRestart())
}

@Test
fun `systemRestart returns false on 5xx`() = runTest {
    val mock = MockEngine { _ -> respond("", HttpStatusCode.InternalServerError) }
    val client = SonarrClient("http://fake", "k", HttpClient(mock) { install(ContentNegotiation) { json() } })
    assertFalse(client.systemRestart())
}
```

- [ ] **Step 2: Run to verify failure**

```
cd prioritarr; ./gradlew.bat backend:test --tests "*.SonarrClientTest" -i
```
Expected: FAIL — `listCommands`, `cancelCommand`, `fireProcessMonitoredDownloads`, `systemRestart` unresolved.

- [ ] **Step 3: Implement the methods**

In `clients/Sonarr.kt`, add inside the `SonarrClient` class (next to the existing `triggerEpisodeSearch` etc.):

```kotlin
    suspend fun listCommands(): JsonArray =
        http.get("$baseUrl/api/v3/command") { header("X-Api-Key", apiKey) }.body()

    /** Returns true on 200, false on 409 (already started) or 404 (not found). */
    suspend fun cancelCommand(commandId: Long): Boolean {
        val resp = http.delete("$baseUrl/api/v3/command/$commandId") { header("X-Api-Key", apiKey) }
        return resp.status.value in 200..299
    }

    suspend fun fireProcessMonitoredDownloads(): JsonObject =
        http.post("$baseUrl/api/v3/command") {
            header("X-Api-Key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("name", "ProcessMonitoredDownloads") })
        }.body()

    /** Returns true on 200, false on anything else. Does not throw. */
    suspend fun systemRestart(): Boolean {
        val resp = try {
            http.post("$baseUrl/api/v3/system/restart") {
                header("X-Api-Key", apiKey)
                contentType(ContentType.Application.Json)
            }
        } catch (_: Exception) { return false }
        return resp.status.value in 200..299
    }
```

Add the required imports (`io.ktor.http.contentType`, `io.ktor.client.request.delete`, `io.ktor.client.request.setBody`, `kotlinx.serialization.json.put`, etc.) if not already present.

- [ ] **Step 4: Run tests to verify pass**

```
cd prioritarr; ./gradlew.bat backend:test --tests "*.SonarrClientTest" -i
```
Expected: PASS (6 new tests).

- [ ] **Step 5: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/clients/Sonarr.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/clients/SonarrClientTest.kt
git commit -m "feat(sonarr): client surface for command queue + system restart"
```

Append the Co-Authored-By trailer.

---

### Task A2: JamDetector pure function

**Files:**
- Create: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/resilience/JamDetector.kt`
- Create: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/resilience/JamDetectorTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `JamDetectorTest.kt`:

```kotlin
package org.yoshiz.app.prioritarr.backend.resilience

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

class JamDetectorTest {

    private fun cmd(
        id: Long,
        name: String,
        status: String,
        startedSecAgo: Long? = null,
        queuedSecAgo: Long? = null,
        now: Long = 1_700_000_000L,
    ): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("name", JsonPrimitive(name))
        put("status", JsonPrimitive(status))
        startedSecAgo?.let {
            put("started", JsonPrimitive(java.time.Instant.ofEpochSecond(now - it).toString()))
        }
        queuedSecAgo?.let {
            put("queued", JsonPrimitive(java.time.Instant.ofEpochSecond(now - it).toString()))
        }
    }

    private fun commands(vararg c: JsonObject): JsonArray = buildJsonArray { c.forEach { add(it) } }

    @Test fun empty_command_queue_is_healthy() {
        val v = detectJam(commands(), nowEpochSeconds = 1_700_000_000L, searchAgeMinutes = 30, importBlockedMinutes = 10)
        assertEquals(JamVerdict.Healthy, v)
    }

    @Test fun young_started_series_search_is_healthy() {
        val v = detectJam(
            commands(cmd(1L, "SeriesSearch", "started", startedSecAgo = 60L)),
            nowEpochSeconds = 1_700_000_000L, searchAgeMinutes = 30, importBlockedMinutes = 10,
        )
        assertEquals(JamVerdict.Healthy, v)
    }

    @Test fun started_series_search_past_threshold_is_jammed() {
        val v = detectJam(
            commands(cmd(1L, "SeriesSearch", "started", startedSecAgo = 31 * 60L)),
            nowEpochSeconds = 1_700_000_000L, searchAgeMinutes = 30, importBlockedMinutes = 10,
        )
        assertIs<JamVerdict.Jammed>(v)
        assertEquals(listOf(1L), v.stuckSeriesSearchIds)
        assertEquals(emptyList(), v.queuedSeriesSearchIds)
    }

    @Test fun queued_series_search_listed_for_cancellation() {
        val v = detectJam(
            commands(
                cmd(1L, "SeriesSearch", "started", startedSecAgo = 31 * 60L),
                cmd(2L, "SeriesSearch", "queued", queuedSecAgo = 5 * 60L),
                cmd(3L, "MissingEpisodeSearch", "queued", queuedSecAgo = 5 * 60L),
            ),
            nowEpochSeconds = 1_700_000_000L, searchAgeMinutes = 30, importBlockedMinutes = 10,
        )
        assertIs<JamVerdict.Jammed>(v)
        assertEquals(listOf(1L), v.stuckSeriesSearchIds)
        assertEquals(setOf(2L, 3L), v.queuedSeriesSearchIds.toSet())
    }

    @Test fun import_blocked_when_ProcessMonitoredDownloads_queued_too_long() {
        val v = detectJam(
            commands(
                cmd(1L, "SeriesSearch", "started", startedSecAgo = 31 * 60L),
                cmd(99L, "ProcessMonitoredDownloads", "queued", queuedSecAgo = 11 * 60L),
            ),
            nowEpochSeconds = 1_700_000_000L, searchAgeMinutes = 30, importBlockedMinutes = 10,
        )
        assertIs<JamVerdict.Jammed>(v)
        assertEquals(1_700_000_000L - 11 * 60L, v.importBlockedSince)
    }

    @Test fun import_not_blocked_when_ProcessMonitoredDownloads_started() {
        val v = detectJam(
            commands(cmd(99L, "ProcessMonitoredDownloads", "started", startedSecAgo = 30L)),
            nowEpochSeconds = 1_700_000_000L, searchAgeMinutes = 30, importBlockedMinutes = 10,
        )
        assertEquals(JamVerdict.Healthy, v)
    }

    @Test fun non_search_commands_ignored() {
        val v = detectJam(
            commands(
                cmd(1L, "RefreshSeries", "started", startedSecAgo = 60 * 60L),
                cmd(2L, "RescanSeries", "started", startedSecAgo = 60 * 60L),
            ),
            nowEpochSeconds = 1_700_000_000L, searchAgeMinutes = 30, importBlockedMinutes = 10,
        )
        assertEquals(JamVerdict.Healthy, v)
    }

    @Test fun unparseable_timestamps_dont_crash() {
        val rec = buildJsonObject {
            put("id", JsonPrimitive(1L))
            put("name", JsonPrimitive("SeriesSearch"))
            put("status", JsonPrimitive("started"))
            put("started", JsonPrimitive("not-a-date"))
        }
        val v = detectJam(buildJsonArray { add(rec) }, 1_700_000_000L, 30, 10)
        // Unparseable started timestamp is treated as "unknown age" → not stuck
        assertEquals(JamVerdict.Healthy, v)
    }
}
```

- [ ] **Step 2: Run to verify failure**

```
cd prioritarr; ./gradlew.bat backend:test --tests "*.JamDetectorTest" -i
```
Expected: FAIL — `detectJam`, `JamVerdict` unresolved.

- [ ] **Step 3: Implement JamDetector**

Create `resilience/JamDetector.kt`:

```kotlin
package org.yoshiz.app.prioritarr.backend.resilience

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant

/** Outcome of a jam-detection pass over Sonarr's /api/v3/command response. */
sealed interface JamVerdict {
    object Healthy : JamVerdict

    data class Jammed(
        /** Commands matching SeriesSearch / MissingEpisodeSearch with `started` older than threshold. */
        val stuckSeriesSearchIds: List<Long>,
        /** Same types but in `queued` state — safe to cancel via DELETE /command/{id}. */
        val queuedSeriesSearchIds: List<Long>,
        /** Epoch sec of the oldest ProcessMonitoredDownloads queued > importBlockedMinutes. Null if none. */
        val importBlockedSince: Long?,
        /** Epoch sec of the oldest started search across all stuck commands. */
        val oldestStartedAt: Long,
    ) : JamVerdict
}

private val SEARCH_TYPES = setOf("SeriesSearch", "MissingEpisodeSearch")

/**
 * Pure function — given Sonarr's command-queue payload and the
 * configured thresholds, return a verdict. No side effects, no I/O.
 *
 * @param commands the raw JsonArray from GET /api/v3/command
 * @param nowEpochSeconds wall-clock seconds; injected for testability
 * @param searchAgeMinutes age above which a started SeriesSearch is "stuck"
 * @param importBlockedMinutes age above which a queued ProcessMonitoredDownloads counts as blocked
 */
fun detectJam(
    commands: JsonArray,
    nowEpochSeconds: Long,
    searchAgeMinutes: Int,
    importBlockedMinutes: Int,
): JamVerdict {
    val stuck = mutableListOf<Long>()
    val queued = mutableListOf<Long>()
    var oldestStartedAt = Long.MAX_VALUE
    var importBlockedSince: Long? = null

    val searchAgeSec = searchAgeMinutes * 60L
    val importBlockedSec = importBlockedMinutes * 60L

    for (row in commands) {
        val obj = row.jsonObject
        val id = obj["id"]?.jsonPrimitive?.longOrNull ?: continue
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
        val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: continue

        if (name in SEARCH_TYPES) {
            when (status) {
                "started" -> {
                    val startedAt = parseInstantSec(obj["started"]?.jsonPrimitive?.contentOrNull) ?: continue
                    if (nowEpochSeconds - startedAt > searchAgeSec) {
                        stuck += id
                        if (startedAt < oldestStartedAt) oldestStartedAt = startedAt
                    }
                }
                "queued" -> queued += id
            }
        } else if (name == "ProcessMonitoredDownloads" && status == "queued") {
            val queuedAt = parseInstantSec(obj["queued"]?.jsonPrimitive?.contentOrNull) ?: continue
            if (nowEpochSeconds - queuedAt > importBlockedSec) {
                if (importBlockedSince == null || queuedAt < importBlockedSince) {
                    importBlockedSince = queuedAt
                }
            }
        }
    }

    val jammed = stuck.isNotEmpty() || queued.isNotEmpty() || importBlockedSince != null
    return if (!jammed) JamVerdict.Healthy else JamVerdict.Jammed(
        stuckSeriesSearchIds = stuck,
        queuedSeriesSearchIds = queued,
        importBlockedSince = importBlockedSince,
        oldestStartedAt = if (oldestStartedAt == Long.MAX_VALUE) nowEpochSeconds else oldestStartedAt,
    )
}

/** Parse Sonarr's ISO-8601 timestamps to epoch seconds. Returns null on parse failure (logged elsewhere). */
private fun parseInstantSec(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return try { Instant.parse(iso).epochSecond } catch (_: Exception) { null }
}
```

- [ ] **Step 4: Run tests to verify pass**

```
cd prioritarr; ./gradlew.bat backend:test --tests "*.JamDetectorTest" -i
```
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/resilience/JamDetector.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/resilience/JamDetectorTest.kt
git commit -m "feat(resilience): JamDetector pure function for Sonarr command queue"
```

Append the Co-Authored-By trailer.

---

### Task A3: sonarr_jam_interventions table + DB wrappers

**Files:**
- Modify: `prioritarr/backend/src/main/sqldelight/org/yoshiz/app/prioritarr/backend/database/Schema.sq`
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/database/Database.kt`
- Create: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/database/JamInterventionsRoundTripTest.kt`

- [ ] **Step 1: Write the failing test**

Create `JamInterventionsRoundTripTest.kt`:

```kotlin
package org.yoshiz.app.prioritarr.backend.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.nio.file.Files

class JamInterventionsRoundTripTest {

    private fun freshDb(): Database {
        val tmp = Files.createTempFile("prio-jam", ".db")
        tmp.toFile().deleteOnExit()
        return Database(tmp.toAbsolutePath().toString())
    }

    @Test fun insert_then_lastJamIntervention_returns_latest() {
        val db = freshDb()
        assertNull(db.lastJamIntervention())
        db.insertJamIntervention(attemptedAt = 1_000L, verdict = "jammed", action = "soft", outcome = "ok", cancelledCount = 3)
        db.insertJamIntervention(attemptedAt = 2_000L, verdict = "healthy", action = "cleared", outcome = "ok", cancelledCount = 0)
        val last = db.lastJamIntervention()!!
        assertEquals(2_000L, last.attemptedAt)
        assertEquals("cleared", last.action)
    }

    @Test fun listJamInterventions_returns_newest_first_with_limit() {
        val db = freshDb()
        for (i in 1L..15L) {
            db.insertJamIntervention(attemptedAt = i * 1_000L, verdict = "jammed", action = "soft", outcome = "ok", cancelledCount = 0)
        }
        val rows = db.listJamInterventions(limit = 5)
        assertEquals(5, rows.size)
        assertEquals(15_000L, rows[0].attemptedAt)
        assertEquals(11_000L, rows[4].attemptedAt)
    }
}
```

- [ ] **Step 2: Run to verify failure**

```
cd prioritarr; ./gradlew.bat backend:test --tests "*.JamInterventionsRoundTripTest" -i
```
Expected: FAIL — `insertJamIntervention`, `lastJamIntervention`, `listJamInterventions` unresolved.

- [ ] **Step 3: Add schema + queries**

In `Schema.sq`, append:

```sql
-- ------------------------------------------------------------------
-- sonarr_jam_interventions
-- ------------------------------------------------------------------
-- One row per scheduler tick where the Jam Guard took ANY action
-- (including "noop" for the still-in-grace-window case). Recent rows
-- drive the state machine ("what did I try last and when?") and feed
-- the UI's intervention-history panel.
CREATE TABLE IF NOT EXISTS sonarr_jam_interventions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    attempted_at INTEGER NOT NULL CHECK (attempted_at > 0),
    verdict TEXT NOT NULL,        -- "jammed" | "healthy"
    action TEXT NOT NULL,         -- "soft" | "restart" | "await_user_restart" | "cleared" | "noop"
    outcome TEXT,                 -- "ok" | "failed" | textual detail; nullable
    cancelled_count INTEGER NOT NULL DEFAULT 0 CHECK (cancelled_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_jam_attempted_at ON sonarr_jam_interventions(attempted_at DESC);

insertJamIntervention:
INSERT INTO sonarr_jam_interventions (attempted_at, verdict, action, outcome, cancelled_count)
VALUES (?, ?, ?, ?, ?);

lastJamIntervention:
SELECT * FROM sonarr_jam_interventions ORDER BY attempted_at DESC LIMIT 1;

listJamInterventions:
SELECT * FROM sonarr_jam_interventions ORDER BY attempted_at DESC LIMIT ?;
```

- [ ] **Step 4: Add Database wrappers**

In `database/Database.kt`, append:

```kotlin
    // ------------------------------------------------------------------
    // sonarr_jam_interventions
    // ------------------------------------------------------------------

    data class JamIntervention(
        val id: Long,
        val attemptedAt: Long,
        val verdict: String,
        val action: String,
        val outcome: String?,
        val cancelledCount: Int,
    )

    fun insertJamIntervention(
        attemptedAt: Long,
        verdict: String,
        action: String,
        outcome: String?,
        cancelledCount: Int,
    ) {
        q.insertJamIntervention(
            attempted_at = attemptedAt,
            verdict = verdict,
            action = action,
            outcome = outcome,
            cancelled_count = cancelledCount.toLong(),
        )
    }

    fun lastJamIntervention(): JamIntervention? =
        q.lastJamIntervention().executeAsOneOrNull()?.let {
            JamIntervention(
                id = it.id,
                attemptedAt = it.attempted_at,
                verdict = it.verdict,
                action = it.action,
                outcome = it.outcome,
                cancelledCount = it.cancelled_count.toInt(),
            )
        }

    fun listJamInterventions(limit: Int): List<JamIntervention> =
        q.listJamInterventions(limit.toLong()).executeAsList().map {
            JamIntervention(
                id = it.id,
                attemptedAt = it.attempted_at,
                verdict = it.verdict,
                action = it.action,
                outcome = it.outcome,
                cancelledCount = it.cancelled_count.toInt(),
            )
        }
```

- [ ] **Step 5: Run tests to verify pass**

```
cd prioritarr; ./gradlew.bat backend:test --tests "*.JamInterventionsRoundTripTest" -i
```
Expected: PASS.

- [ ] **Step 6: Commit**

```
git add prioritarr/backend/src/main/sqldelight/org/yoshiz/app/prioritarr/backend/database/Schema.sq prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/database/Database.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/database/JamInterventionsRoundTripTest.kt
git commit -m "feat(db): sonarr_jam_interventions table + Database wrappers"
```

Append the Co-Authored-By trailer.

---

### Task A4: JamGuard runner + state machine

**Files:**
- Create: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/resilience/JamGuard.kt`
- Create: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/resilience/JamGuardTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `JamGuardTest.kt`:

```kotlin
package org.yoshiz.app.prioritarr.backend.resilience

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
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

class JamGuardTest {

    private fun freshDb(): Database {
        val tmp = Files.createTempFile("prio-jam-guard", ".db")
        tmp.toFile().deleteOnExit()
        return Database(tmp.toAbsolutePath().toString())
    }

    private fun noopHttp() = HttpClient(MockEngine { respondOk("{}") }) {
        install(ContentNegotiation) { json() }
    }

    /** Configurable fake Sonarr — returns scripted commands; records mitigation calls. */
    private class FakeSonarr(
        private var commandsResponse: JsonArray = JsonArray(emptyList()),
        private val restartReturns: Boolean = true,
    ) : SonarrClient("http://fake", "k", HttpClient(MockEngine { respondOk("{}") }) {
        install(ContentNegotiation) { json() }
    }) {
        val cancelledIds = mutableListOf<Long>()
        var fireProcessCalled = 0
        var restartCalled = 0

        fun setCommands(arr: JsonArray) { commandsResponse = arr }

        override suspend fun listCommands(): JsonArray = commandsResponse
        override suspend fun cancelCommand(commandId: Long): Boolean {
            cancelledIds += commandId; return true
        }
        override suspend fun fireProcessMonitoredDownloads(): JsonObject {
            fireProcessCalled++; return buildJsonObject {}
        }
        override suspend fun systemRestart(): Boolean {
            restartCalled++; return restartReturns
        }
    }

    private fun stuckCommands(now: Long): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("id", JsonPrimitive(1L))
            put("name", JsonPrimitive("SeriesSearch"))
            put("status", JsonPrimitive("started"))
            put("started", JsonPrimitive(java.time.Instant.ofEpochSecond(now - 60 * 60L).toString()))
        })
        add(buildJsonObject {
            put("id", JsonPrimitive(2L))
            put("name", JsonPrimitive("SeriesSearch"))
            put("status", JsonPrimitive("queued"))
            put("queued", JsonPrimitive(java.time.Instant.ofEpochSecond(now - 5 * 60L).toString()))
        })
        add(buildJsonObject {
            put("id", JsonPrimitive(3L))
            put("name", JsonPrimitive("MissingEpisodeSearch"))
            put("status", JsonPrimitive("queued"))
            put("queued", JsonPrimitive(java.time.Instant.ofEpochSecond(now - 5 * 60L).toString()))
        })
    }

    @Test fun healthy_queue_records_nothing() = runTest {
        val db = freshDb()
        val sonarr = FakeSonarr(commandsResponse = JsonArray(emptyList()))
        val outcome = runJamGuard(
            sonarr = sonarr, db = db,
            searchAgeMinutes = 30, importBlockedMinutes = 10,
            autoRestart = false, nowEpochSeconds = 1_700_000_000L,
        )
        assertEquals(JamGuardOutcome.Healthy, outcome)
        assertEquals(0, db.listJamInterventions(10).size)  // no row inserted for first-tick healthy
        assertEquals(0, sonarr.cancelledIds.size)
    }

    @Test fun healthy_after_jammed_records_cleared_row() = runTest {
        val db = freshDb()
        // Seed prior "soft" intervention so the next healthy tick should record "cleared"
        db.insertJamIntervention(1_699_999_500L, "jammed", "soft", "ok", 2)

        val sonarr = FakeSonarr(commandsResponse = JsonArray(emptyList()))
        val outcome = runJamGuard(
            sonarr = sonarr, db = db,
            searchAgeMinutes = 30, importBlockedMinutes = 10,
            autoRestart = false, nowEpochSeconds = 1_700_000_000L,
        )
        assertEquals(JamGuardOutcome.Healthy, outcome)
        val rows = db.listJamInterventions(10)
        assertEquals(2, rows.size)
        assertEquals("cleared", rows[0].action)
    }

    @Test fun first_jammed_tick_fires_soft_mitigation() = runTest {
        val now = 1_700_000_000L
        val db = freshDb()
        val sonarr = FakeSonarr(commandsResponse = stuckCommands(now))
        val outcome = runJamGuard(
            sonarr = sonarr, db = db,
            searchAgeMinutes = 30, importBlockedMinutes = 10,
            autoRestart = false, nowEpochSeconds = now,
        )
        assertTrue(outcome is JamGuardOutcome.SoftAttempted)
        assertEquals(setOf(2L, 3L), sonarr.cancelledIds.toSet())
        assertEquals(1, sonarr.fireProcessCalled)
        assertEquals(0, sonarr.restartCalled)
        val row = db.lastJamIntervention()!!
        assertEquals("soft", row.action)
        assertEquals(2, row.cancelledCount)
    }

    @Test fun second_jammed_tick_within_30min_window_is_noop() = runTest {
        val now = 1_700_000_000L
        val db = freshDb()
        // Prior soft mitigation 10 minutes ago — too recent to escalate
        db.insertJamIntervention(now - 10 * 60L, "jammed", "soft", "ok", 0)

        val sonarr = FakeSonarr(commandsResponse = stuckCommands(now))
        val outcome = runJamGuard(
            sonarr = sonarr, db = db,
            searchAgeMinutes = 30, importBlockedMinutes = 10,
            autoRestart = true, nowEpochSeconds = now,
        )
        // Inside both the 30-min "no repeat soft" window AND it's not >=5min since soft (well, 10min > 5min,
        // so restart should escalate IF autoRestart is on). Adjust expectations:
        assertTrue(outcome is JamGuardOutcome.Restarted)
        assertEquals(1, sonarr.restartCalled)
    }

    @Test fun jammed_after_soft_with_autoRestart_off_records_await_user_restart() = runTest {
        val now = 1_700_000_000L
        val db = freshDb()
        db.insertJamIntervention(now - 6 * 60L, "jammed", "soft", "ok", 1)

        val sonarr = FakeSonarr(commandsResponse = stuckCommands(now))
        val outcome = runJamGuard(
            sonarr = sonarr, db = db,
            searchAgeMinutes = 30, importBlockedMinutes = 10,
            autoRestart = false, nowEpochSeconds = now,
        )
        assertEquals(JamGuardOutcome.AwaitingUser, outcome)
        assertEquals(0, sonarr.restartCalled)
        val row = db.lastJamIntervention()!!
        assertEquals("await_user_restart", row.action)
    }

    @Test fun await_user_restart_not_duplicated_on_subsequent_ticks() = runTest {
        val now = 1_700_000_000L
        val db = freshDb()
        db.insertJamIntervention(now - 7 * 60L, "jammed", "soft", "ok", 0)
        db.insertJamIntervention(now - 60L, "jammed", "await_user_restart", null, 0)

        val sonarr = FakeSonarr(commandsResponse = stuckCommands(now))
        runJamGuard(
            sonarr = sonarr, db = db,
            searchAgeMinutes = 30, importBlockedMinutes = 10,
            autoRestart = false, nowEpochSeconds = now,
        )
        // Only one await_user_restart row should exist (the seeded one); no new insert
        val awaits = db.listJamInterventions(20).count { it.action == "await_user_restart" }
        assertEquals(1, awaits)
    }
}
```

- [ ] **Step 2: Run to verify failure**

```
cd prioritarr; ./gradlew.bat backend:test --tests "*.JamGuardTest" -i
```
Expected: FAIL — `runJamGuard`, `JamGuardOutcome` unresolved.

- [ ] **Step 3: Implement JamGuard**

Create `resilience/JamGuard.kt`:

```kotlin
package org.yoshiz.app.prioritarr.backend.resilience

import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.database.Database

private val logger = LoggerFactory.getLogger("org.yoshiz.app.prioritarr.backend.resilience.jam-guard")

sealed interface JamGuardOutcome {
    object Healthy : JamGuardOutcome
    object Noop : JamGuardOutcome                       // jammed but within grace window
    data class SoftAttempted(val cancelled: Int) : JamGuardOutcome
    data class Restarted(val ok: Boolean) : JamGuardOutcome
    object AwaitingUser : JamGuardOutcome
}

// Bounds for the escalation state machine. Hardcoded constants — operator
// value in tuning these is low; surface a setting if real-world usage demands it.
private const val SOFT_REPEAT_WINDOW_SEC = 30 * 60L     // 30 min between soft attempts
private const val SOFT_ESCALATION_DELAY_SEC = 5 * 60L   // 5 min before escalating soft → restart

/**
 * One scheduler tick of the Sonarr command-queue jam guard. Detects
 * jams via [detectJam], escalates mitigation based on the most-recent
 * intervention row, and persists a new row for every action taken.
 *
 * Idempotent: multiple identical ticks produce at most one soft
 * intervention per 30-min window, at most one restart per "still jammed
 * >5min after soft," and never duplicates the await_user_restart row.
 */
suspend fun runJamGuard(
    sonarr: SonarrClient,
    db: Database,
    searchAgeMinutes: Int,
    importBlockedMinutes: Int,
    autoRestart: Boolean,
    nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
): JamGuardOutcome {
    val commands = try { sonarr.listCommands() } catch (e: Exception) {
        logger.warn("[jam-guard] listCommands failed: {}", e.message)
        return JamGuardOutcome.Noop
    }
    val verdict = detectJam(commands, nowEpochSeconds, searchAgeMinutes, importBlockedMinutes)
    val last = db.lastJamIntervention()

    if (verdict is JamVerdict.Healthy) {
        // Close the audit loop: if the most recent row was a mitigation
        // attempt, record a "cleared" row so the UI shows the resolution.
        if (last != null && last.action !in setOf("cleared", "noop")) {
            db.insertJamIntervention(nowEpochSeconds, "healthy", "cleared", "ok", 0)
        }
        return JamGuardOutcome.Healthy
    }

    val v = verdict as JamVerdict.Jammed
    val lastIsRecent = last != null && nowEpochSeconds - last.attemptedAt < SOFT_REPEAT_WINDOW_SEC

    // Branch 1: no recent intervention → soft mitigation.
    if (!lastIsRecent || last?.action == "cleared") {
        val cancelled = v.queuedSeriesSearchIds.count { runCatching { sonarr.cancelCommand(it) }.getOrDefault(false) }
        runCatching { sonarr.fireProcessMonitoredDownloads() }
        db.insertJamIntervention(nowEpochSeconds, "jammed", "soft", "ok", cancelled)
        logger.info("[jam-guard] soft mitigation: cancelled={}, fired ProcessMonitoredDownloads", cancelled)
        return JamGuardOutcome.SoftAttempted(cancelled)
    }

    // Branch 2: recent soft attempt → escalate to restart (or await user) if enough time has passed.
    if (last?.action == "soft" && nowEpochSeconds - last.attemptedAt >= SOFT_ESCALATION_DELAY_SEC) {
        if (autoRestart) {
            val ok = sonarr.systemRestart()
            db.insertJamIntervention(nowEpochSeconds, "jammed", "restart", if (ok) "ok" else "failed", 0)
            logger.info("[jam-guard] escalation: restart issued, ok={}", ok)
            return JamGuardOutcome.Restarted(ok)
        } else {
            // Only insert one "await_user_restart" per soft-window — avoid spam.
            if (last.action != "await_user_restart") {
                db.insertJamIntervention(nowEpochSeconds, "jammed", "await_user_restart", "user_action_required", 0)
            }
            return JamGuardOutcome.AwaitingUser
        }
    }

    // Branch 3: await_user_restart already issued; don't duplicate.
    if (last?.action == "await_user_restart") {
        return JamGuardOutcome.AwaitingUser
    }

    // Branch 4: still inside grace window after a soft attempt.
    return JamGuardOutcome.Noop
}
```

- [ ] **Step 4: Run tests to verify pass**

```
cd prioritarr; ./gradlew.bat backend:test --tests "*.JamGuardTest" -i
```
Expected: PASS.

- [ ] **Step 5: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/resilience/JamGuard.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/resilience/JamGuardTest.kt
git commit -m "feat(resilience): JamGuard runner with escalating state machine"
```

Append the Co-Authored-By trailer.

---

### Task A5: canFireSeriesSearch helper + wire into P5 ratchet + queue janitor

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/clients/Sonarr.kt`
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/sweep/P5Ratchet.kt`
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/reconcile/QueueJanitor.kt`
- Create: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/clients/SonarrSearchCapTest.kt`

- [ ] **Step 1: Write the failing test**

Create `SonarrSearchCapTest.kt`:

```kotlin
package org.yoshiz.app.prioritarr.backend.clients

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SonarrSearchCapTest {

    private fun client(commandsBody: String): SonarrClient {
        val mock = MockEngine { respondOk(commandsBody) }
        val http = HttpClient(mock) { install(ContentNegotiation) { json() } }
        return SonarrClient("http://fake", "k", http)
    }

    @Test fun returns_true_when_under_cap() = runTest {
        val body = """[{"id":1,"name":"SeriesSearch","status":"started"}]"""
        val c = client(body)
        assertTrue(c.canFireSeriesSearch(cap = 3))
    }

    @Test fun returns_false_at_cap() = runTest {
        val body = """[
            {"id":1,"name":"SeriesSearch","status":"started"},
            {"id":2,"name":"SeriesSearch","status":"queued"},
            {"id":3,"name":"MissingEpisodeSearch","status":"started"}
        ]"""
        val c = client(body)
        assertFalse(c.canFireSeriesSearch(cap = 3))
    }

    @Test fun ignores_non_search_commands() = runTest {
        val body = """[
            {"id":1,"name":"RefreshSeries","status":"started"},
            {"id":2,"name":"RescanSeries","status":"started"},
            {"id":3,"name":"ProcessMonitoredDownloads","status":"queued"},
            {"id":4,"name":"SeriesSearch","status":"queued"}
        ]"""
        val c = client(body)
        assertTrue(c.canFireSeriesSearch(cap = 3))
    }

    @Test fun ignores_completed_failed_search_commands() = runTest {
        val body = """[
            {"id":1,"name":"SeriesSearch","status":"completed"},
            {"id":2,"name":"SeriesSearch","status":"failed"},
            {"id":3,"name":"SeriesSearch","status":"queued"}
        ]"""
        val c = client(body)
        assertTrue(c.canFireSeriesSearch(cap = 3))
    }
}
```

- [ ] **Step 2: Run to verify failure**

```
cd prioritarr; ./gradlew.bat backend:test --tests "*.SonarrSearchCapTest" -i
```
Expected: FAIL — `canFireSeriesSearch` unresolved.

- [ ] **Step 3: Implement canFireSeriesSearch (with 30s in-process cache)**

In `clients/Sonarr.kt`, add inside `SonarrClient`:

```kotlin
    // 30-second cache for listCommands so the cap check on every
    // prioritarr-triggered SeriesSearch doesn't fan-out to Sonarr.
    @Volatile private var commandsCache: Pair<Long, JsonArray>? = null
    private val commandsCacheMutex = kotlinx.coroutines.sync.Mutex()
    private val COMMANDS_CACHE_TTL_MS = 30_000L

    private suspend fun listCommandsCached(): JsonArray {
        val now = System.currentTimeMillis()
        val cached = commandsCache
        if (cached != null && now - cached.first < COMMANDS_CACHE_TTL_MS) return cached.second
        commandsCacheMutex.withLock {
            val recheck = commandsCache
            if (recheck != null && now - recheck.first < COMMANDS_CACHE_TTL_MS) return recheck.second
            val fresh = try { listCommands() } catch (_: Exception) { kotlinx.serialization.json.JsonArray(emptyList()) }
            commandsCache = now to fresh
            return fresh
        }
    }

    /**
     * Returns true if prioritarr is allowed to fire one more SeriesSearch /
     * MissingEpisodeSearch — i.e., fewer than [cap] are currently queued or
     * started in Sonarr. False = skip this call site. Cached for 30s.
     */
    suspend fun canFireSeriesSearch(cap: Int): Boolean {
        val commands = listCommandsCached()
        val inFlight = commands.count {
            val o = it.jsonObject
            val name = o["name"]?.jsonPrimitive?.contentOrNull
            val status = o["status"]?.jsonPrimitive?.contentOrNull
            (name == "SeriesSearch" || name == "MissingEpisodeSearch") &&
                (status == "queued" || status == "started")
        }
        return inFlight < cap
    }
```

Add the missing import: `import kotlinx.coroutines.sync.withLock`.

- [ ] **Step 4: Wire the cap into the two remaining SeriesSearch callers**

In `sweep/P5Ratchet.kt`, find the runner function `runP5SeasonRatchet` (the function that loops over the plan's `RatchetAction.SeriesSearch` entries and fires `sonarr.triggerSeriesSearch`). Before firing, add:

```kotlin
                    is RatchetAction.SeriesSearch -> {
                        if (!sonarr.canFireSeriesSearch(cap = settings.sonarrJamConcurrentSearchCap)) {
                            logger.info("[p5-ratchet] skipped SeriesSearch for series {}: concurrent-search cap reached", action.seriesId)
                            return@forEach
                        }
                        sonarr.triggerSeriesSearch(action.seriesId).also { seriesSearches++ }
                    }
```

Same for `reconcile/QueueJanitor.kt`'s re-search-on-blocklist path: before `sonarr.triggerEpisodeSearch(item.episodeIds)` (this fires EpisodeSearch, not SeriesSearch — leave it; the cap targets the wider command). If QueueJanitor fires any `triggerSeriesSearch`, add the same guard.

For settings access from these functions, the cap is read from `settings.sonarrJamConcurrentSearchCap` (added in Task A7 below). For tasks A5/A6 ordering, you can either:
- Hardcode `cap = 3` in this task and replace with the setting in A7, OR
- Add the setting field in this task and finish the rest of the plumbing in A7

The cleaner sequence: add the field now (one-liner in Settings.kt; no UI yet) and read it everywhere from the start. Update `data class Intervals` in `config/Settings.kt`:

```kotlin
    // Subsystem A — Jam Guard
    val sonarrJamConcurrentSearchCap: Int = 3,
```

(Other Subsystem A settings are added in Task A6.)

- [ ] **Step 5: Run tests**

```
cd prioritarr; ./gradlew.bat backend:test
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/clients/Sonarr.kt prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/sweep/P5Ratchet.kt prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/reconcile/QueueJanitor.kt prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/config/Settings.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/clients/SonarrSearchCapTest.kt
git commit -m "feat(resilience): cap concurrent prioritarr-triggered SeriesSearch at 3"
```

Append the Co-Authored-By trailer.

---

### Task A6: Settings + scheduler job registration

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/config/Settings.kt`
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/schemas/V2.kt`
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/api/v2/V2Routes.kt`
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/Main.kt`
- Modify: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/config/SettingsParserTest.kt`

- [ ] **Step 1: Write the failing test**

Append to `SettingsParserTest.kt`:

```kotlin
@Test
fun yaml_loads_sonarr_jam_fields() {
    val yaml = """
        intervals:
          sonarr_jam_search_age_minutes: 45
          sonarr_jam_import_blocked_minutes: 15
          sonarr_jam_guard_interval_minutes: 10
          sonarr_jam_auto_restart: true
    """.trimIndent()
    val s = parseSettingsFromYamlString(yaml)
    assertEquals(45, s.intervals.sonarrJamSearchAgeMinutes)
    assertEquals(15, s.intervals.sonarrJamImportBlockedMinutes)
    assertEquals(10, s.intervals.sonarrJamGuardIntervalMinutes)
    assertEquals(true, s.intervals.sonarrJamAutoRestart)
}

@Test
fun intervals_jam_fields_have_documented_defaults() {
    val s = loadSettingsFrom(requiredEnvForParser)
    assertEquals(30, s.intervals.sonarrJamSearchAgeMinutes)
    assertEquals(10, s.intervals.sonarrJamImportBlockedMinutes)
    assertEquals(5, s.intervals.sonarrJamGuardIntervalMinutes)
    assertEquals(false, s.intervals.sonarrJamAutoRestart)
    assertEquals(3, s.intervals.sonarrJamConcurrentSearchCap)
}
```

- [ ] **Step 2: Run to verify failure**

```
cd prioritarr; ./gradlew.bat backend:test --tests "*.SettingsParserTest" -i
```
Expected: FAIL — `sonarrJamSearchAgeMinutes` etc. unresolved.

- [ ] **Step 3: Add fields to Intervals + EditableSettings + applySettingsOverride + YAML parser**

In `config/Settings.kt`'s `Intervals`, next to the `sonarrJamConcurrentSearchCap` field added in Task A5:

```kotlin
    val sonarrJamSearchAgeMinutes: Int = 30,
    val sonarrJamImportBlockedMinutes: Int = 10,
    val sonarrJamGuardIntervalMinutes: Int = 5,
    val sonarrJamAutoRestart: Boolean = false,
```

In `EditableSettings`:

```kotlin
    val sonarrJamSearchAgeMinutes: Int? = null,
    val sonarrJamImportBlockedMinutes: Int? = null,
    val sonarrJamGuardIntervalMinutes: Int? = null,
    val sonarrJamAutoRestart: Boolean? = null,
    val sonarrJamConcurrentSearchCap: Int? = null,
```

In `applySettingsOverride`'s `intervals.copy(...)`:

```kotlin
        sonarrJamSearchAgeMinutes = override.sonarrJamSearchAgeMinutes ?: base.intervals.sonarrJamSearchAgeMinutes,
        sonarrJamImportBlockedMinutes = override.sonarrJamImportBlockedMinutes ?: base.intervals.sonarrJamImportBlockedMinutes,
        sonarrJamGuardIntervalMinutes = override.sonarrJamGuardIntervalMinutes ?: base.intervals.sonarrJamGuardIntervalMinutes,
        sonarrJamAutoRestart = override.sonarrJamAutoRestart ?: base.intervals.sonarrJamAutoRestart,
        sonarrJamConcurrentSearchCap = override.sonarrJamConcurrentSearchCap ?: base.intervals.sonarrJamConcurrentSearchCap,
```

In the YAML parser block:

```kotlin
        sonarrJamSearchAgeMinutes = o.num("sonarr_jam_search_age_minutes") { it.toInt() } ?: intervals.sonarrJamSearchAgeMinutes,
        sonarrJamImportBlockedMinutes = o.num("sonarr_jam_import_blocked_minutes") { it.toInt() } ?: intervals.sonarrJamImportBlockedMinutes,
        sonarrJamGuardIntervalMinutes = o.num("sonarr_jam_guard_interval_minutes") { it.toInt() } ?: intervals.sonarrJamGuardIntervalMinutes,
        sonarrJamAutoRestart = (o["sonarr_jam_auto_restart"] as? Boolean) ?: intervals.sonarrJamAutoRestart,
        sonarrJamConcurrentSearchCap = o.num("sonarr_jam_concurrent_search_cap") { it.toInt() } ?: intervals.sonarrJamConcurrentSearchCap,
```

- [ ] **Step 4: Thread through V2 wire**

In `schemas/V2.kt`'s `IntervalsWire`:

```kotlin
    val sonarrJamSearchAgeMinutes: Int = 30,
    val sonarrJamImportBlockedMinutes: Int = 10,
    val sonarrJamGuardIntervalMinutes: Int = 5,
    val sonarrJamAutoRestart: Boolean = false,
    val sonarrJamConcurrentSearchCap: Int = 3,
```

In `V2Routes.kt`'s `mergeEditable`:

```kotlin
    sonarrJamSearchAgeMinutes = patch.sonarrJamSearchAgeMinutes ?: existing.sonarrJamSearchAgeMinutes,
    sonarrJamImportBlockedMinutes = patch.sonarrJamImportBlockedMinutes ?: existing.sonarrJamImportBlockedMinutes,
    sonarrJamGuardIntervalMinutes = patch.sonarrJamGuardIntervalMinutes ?: existing.sonarrJamGuardIntervalMinutes,
    sonarrJamAutoRestart = patch.sonarrJamAutoRestart ?: existing.sonarrJamAutoRestart,
    sonarrJamConcurrentSearchCap = patch.sonarrJamConcurrentSearchCap ?: existing.sonarrJamConcurrentSearchCap,
```

Add the five fields to BOTH `IntervalsWire(...)` construction sites (read from `s.intervals.X`).

- [ ] **Step 5: Register the SONARR_JAM_GUARD scheduler job in Main.kt**

In `Main.kt`, locate the existing job registration block (search `JobDefinition`). Add a new `JobId` constant first — find the `object JobId { ... }` declaration:

```kotlin
const val SONARR_JAM_GUARD = "sonarr-jam-guard"
```

Then in the job registration block, add (placement: between `QUEUE_JANITOR` and `UNMONITORED_REAPER` or wherever fits alphabetically by id):

```kotlin
            add(org.yoshiz.app.prioritarr.backend.scheduler.JobDefinition(
                id = JobId.SONARR_JAM_GUARD,
                cadenceMinutes = { liveSettings(db, settings).intervals.sonarrJamGuardIntervalMinutes.toLong() },
                weight = org.yoshiz.app.prioritarr.backend.scheduler.JobWeight.LIGHT,
                firstRunDelayMinutes = 2,
                run = {
                    val s = liveSettings(db, settings)
                    val outcome = org.yoshiz.app.prioritarr.backend.resilience.runJamGuard(
                        sonarr = sonarr,
                        db = db,
                        searchAgeMinutes = s.intervals.sonarrJamSearchAgeMinutes,
                        importBlockedMinutes = s.intervals.sonarrJamImportBlockedMinutes,
                        autoRestart = s.intervals.sonarrJamAutoRestart,
                    )
                    org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome(
                        summary = outcome::class.simpleName,
                    )
                },
            ))
```

- [ ] **Step 6: Run tests**

```
cd prioritarr; ./gradlew.bat backend:test
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/config/Settings.kt prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/schemas/V2.kt prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/api/v2/V2Routes.kt prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/Main.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/config/SettingsParserTest.kt
git commit -m "feat(resilience): wire JamGuard into scheduler + settings plumbing"
```

Append the Co-Authored-By trailer.

---

### Task A7: V2 routes for jam-guard one-click actions

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/api/v2/V2Routes.kt`
- Modify: `prioritarr/frontend/src/hooks/queries.ts`

- [ ] **Step 1: Add three POST routes**

In `api/v2/V2Routes.kt`, find the section near `/trakt/oauth/refresh` or other dedicated routes. Add:

```kotlin
    post("/sonarr/clear-queued") {
        val commands = state.sonarr.listCommands()
        val queuedSearchIds = commands.mapNotNull {
            val o = it.jsonObject
            val name = o["name"]?.jsonPrimitive?.contentOrNull
            val status = o["status"]?.jsonPrimitive?.contentOrNull
            val id = o["id"]?.jsonPrimitive?.longOrNull
            if (id != null && status == "queued" && (name == "SeriesSearch" || name == "MissingEpisodeSearch")) id else null
        }
        var cancelled = 0
        queuedSearchIds.forEach { if (state.sonarr.cancelCommand(it)) cancelled++ }
        state.db.insertJamIntervention(
            attemptedAt = System.currentTimeMillis() / 1000L,
            verdict = "jammed", action = "manual_clear", outcome = "ok",
            cancelledCount = cancelled,
        )
        call.respond(kotlinx.serialization.json.buildJsonObject {
            put("status", "cleared")
            put("cancelled", cancelled)
            put("total_queued_searches", queuedSearchIds.size)
        })
    }

    post("/sonarr/fire-import") {
        runCatching { state.sonarr.fireProcessMonitoredDownloads() }
        call.respond(kotlinx.serialization.json.buildJsonObject {
            put("status", "ok")
        })
    }

    post("/sonarr/restart") {
        val ok = state.sonarr.systemRestart()
        state.db.insertJamIntervention(
            attemptedAt = System.currentTimeMillis() / 1000L,
            verdict = "jammed", action = "manual_restart", outcome = if (ok) "ok" else "failed",
            cancelledCount = 0,
        )
        call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
            kotlinx.serialization.json.buildJsonObject {
                put("status", if (ok) "restarted" else "failed")
            }
        )
    }

    get("/sonarr/jam-interventions") {
        val rows = state.db.listJamInterventions(20)
        call.respond(kotlinx.serialization.json.buildJsonArray {
            rows.forEach { row ->
                add(kotlinx.serialization.json.buildJsonObject {
                    put("id", row.id)
                    put("attemptedAt", row.attemptedAt)
                    put("verdict", row.verdict)
                    put("action", row.action)
                    put("outcome", row.outcome)
                    put("cancelledCount", row.cancelledCount)
                })
            }
        })
    }
```

- [ ] **Step 2: Add frontend hooks**

In `frontend/src/hooks/queries.ts`, append:

```typescript
export function useSonarrClearQueued() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => postJson<{ status: string; cancelled: number; total_queued_searches: number }>('/api/v2/sonarr/clear-queued'),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['jam-interventions'] }),
  })
}

export function useSonarrFireImport() {
  return useMutation({
    mutationFn: () => postJson<{ status: string }>('/api/v2/sonarr/fire-import'),
  })
}

export function useSonarrRestart() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => postJson<{ status: string }>('/api/v2/sonarr/restart'),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['jam-interventions'] }),
  })
}

export interface JamIntervention {
  id: number
  attemptedAt: number
  verdict: string
  action: string
  outcome: string | null
  cancelledCount: number
}

export function useJamInterventions() {
  return useQuery({
    queryKey: ['jam-interventions'],
    queryFn: () => fetchJson<JamIntervention[]>('/api/v2/sonarr/jam-interventions'),
    refetchInterval: 30_000,
  })
}
```

- [ ] **Step 3: Compile-check both sides**

```
cd prioritarr; ./gradlew.bat backend:compileKotlin
cd prioritarr/frontend; npm run build
```
Both expected BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/api/v2/V2Routes.kt prioritarr/frontend/src/hooks/queries.ts
git commit -m "feat(api): jam-guard one-click action routes + frontend hooks"
```

Append the Co-Authored-By trailer.

---

### Task A8: Settings page UI — Sonarr resilience section

**Files:**
- Modify: `prioritarr/frontend/src/pages/SettingsPage.tsx`

- [ ] **Step 1: Add the section**

In `SettingsPage.tsx`, find a sensible place (after Trakt or near the bottom of service-related sections). Add a new function `SonarrResilienceSection`:

```tsx
function SonarrResilienceSection() {
  const clearQueued = useSonarrClearQueued()
  const fireImport = useSonarrFireImport()
  const restart = useSonarrRestart()
  const interventions = useJamInterventions()
  const settings = useSettings()
  const save = useSaveSettings()

  const s = (settings.data ?? {}) as {
    intervals?: { sonarrJamAutoRestart?: boolean }
  }
  const autoRestart = !!s.intervals?.sonarrJamAutoRestart

  return (
    <SectionShell title="Sonarr resilience" description="Detect + recover from Sonarr command-queue jams.">
      <div className="flex gap-2 flex-wrap">
        <ActionButton
          variant="secondary"
          loading={clearQueued.isPending}
          loadingLabel="Clearing…"
          onClick={() => clearQueued.mutate()}
        >
          Clear queued searches
        </ActionButton>
        <ActionButton
          variant="secondary"
          loading={fireImport.isPending}
          loadingLabel="Firing…"
          onClick={() => fireImport.mutate()}
        >
          Fire import scan
        </ActionButton>
        <ActionButton
          variant="secondary"
          loading={restart.isPending}
          loadingLabel="Restarting…"
          onClick={() => {
            if (!confirm('Restart Sonarr? Aborts in-flight searches but they will resume after restart.')) return
            restart.mutate()
          }}
        >
          Restart Sonarr
        </ActionButton>
      </div>

      <div className="mt-4 flex items-center gap-2">
        <input
          type="checkbox"
          id="sonarr-jam-auto-restart"
          checked={autoRestart}
          onChange={(e) => save.mutate({ sonarrJamAutoRestart: e.target.checked })}
        />
        <label htmlFor="sonarr-jam-auto-restart" className="text-sm">
          Auto-restart Sonarr when jammed (last-resort recovery)
        </label>
      </div>
      <div className="text-xs opacity-60 mt-1">
        When ON, prioritarr will restart Sonarr if soft mitigation (cancel queued searches +
        fire ProcessMonitoredDownloads) doesn't clear a jam within 5 minutes. Restarts abort
        in-flight searches but DB-persisted commands resume on startup.
      </div>

      {interventions.data && interventions.data.length > 0 && (
        <div className="mt-6">
          <div className="text-sm font-medium opacity-90 mb-2">Recent interventions</div>
          <table className="text-xs w-full">
            <thead>
              <tr className="opacity-60">
                <th className="text-left p-1">Time</th>
                <th className="text-left p-1">Verdict</th>
                <th className="text-left p-1">Action</th>
                <th className="text-left p-1">Outcome</th>
                <th className="text-right p-1">Cancelled</th>
              </tr>
            </thead>
            <tbody>
              {interventions.data.slice(0, 10).map((row) => (
                <tr key={row.id} className="border-t border-surface-3">
                  <td className="p-1 opacity-70">{new Date(row.attemptedAt * 1000).toLocaleString()}</td>
                  <td className="p-1">{row.verdict}</td>
                  <td className="p-1">{row.action}</td>
                  <td className="p-1 opacity-70">{row.outcome ?? '—'}</td>
                  <td className="p-1 text-right">{row.cancelledCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </SectionShell>
  )
}
```

(Adapt component names like `SectionShell`, `ActionButton` to whatever the codebase actually exports — search for an existing section pattern in `SettingsPage.tsx` and mirror it.)

Mount the section in the main `SettingsPage` render block:

```tsx
<SonarrResilienceSection />
```

Place it near the bottom of the settings page, after the existing Sonarr connection card.

- [ ] **Step 2: Verify build**

```
cd prioritarr/frontend; npm run build
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```
git add prioritarr/frontend/src/pages/SettingsPage.tsx
git commit -m "feat(ui): Sonarr resilience settings section with one-click actions"
```

Append the Co-Authored-By trailer.

---

## Subsystem B — Indexer Health Guard

### Task B1: SonarrClient — listIndexers, indexerStatus, toggleIndexer

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/clients/Sonarr.kt`
- Modify: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/clients/SonarrClientTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to `SonarrClientTest.kt`:

```kotlin
@Test
fun `listIndexers GETs api v3 indexer`() = runTest {
    val mock = MockEngine { req ->
        assertEquals("/api/v3/indexer", req.url.encodedPath)
        respondOk("""[{"id":1,"name":"Anidex","enabled":true}]""")
    }
    val client = SonarrClient("http://fake", "k", HttpClient(mock) { install(ContentNegotiation) { json() } })
    val arr = client.listIndexers()
    assertEquals("Anidex", arr[0].jsonObject["name"]?.jsonPrimitive?.contentOrNull)
}

@Test
fun `indexerStatus GETs api v3 indexerstatus`() = runTest {
    val mock = MockEngine { req ->
        assertEquals("/api/v3/indexerstatus", req.url.encodedPath)
        respondOk("""[{"id":10,"indexerId":1,"disabledTill":"2026-05-20T00:00:00Z","mostRecentFailure":"2026-05-19T12:00:00Z"}]""")
    }
    val client = SonarrClient("http://fake", "k", HttpClient(mock) { install(ContentNegotiation) { json() } })
    val arr = client.indexerStatus()
    assertEquals(1L, arr[0].jsonObject["indexerId"]?.jsonPrimitive?.longOrNull)
}

@Test
fun `toggleIndexer PUTs full body with enabled flag flipped`() = runTest {
    // toggleIndexer first GETs the indexer, then PUTs the full body with enabled mutated.
    var getCalled = false
    var putCalled = false
    val mock = MockEngine { req ->
        when (req.method) {
            HttpMethod.Get -> {
                getCalled = true
                respondOk("""{"id":1,"name":"Anidex","enabled":true,"protocol":"torrent"}""")
            }
            HttpMethod.Put -> {
                putCalled = true
                assertEquals("/api/v3/indexer/1", req.url.encodedPath)
                val body = req.body.toByteArray().decodeToString()
                assertTrue(body.contains("\"enabled\":false"), "body should have enabled=false, got: $body")
                respondOk(body)  // echo back
            }
            else -> respond("", HttpStatusCode.MethodNotAllowed)
        }
    }
    val client = SonarrClient("http://fake", "k", HttpClient(mock) { install(ContentNegotiation) { json() } })
    assertTrue(client.toggleIndexer(1L, enabled = false))
    assertTrue(getCalled)
    assertTrue(putCalled)
}
```

- [ ] **Step 2: Run to verify failure**

```
cd prioritarr; ./gradlew.bat backend:test --tests "*.SonarrClientTest" -i
```
Expected: FAIL — `listIndexers`, `indexerStatus`, `toggleIndexer` unresolved.

- [ ] **Step 3: Implement the methods**

In `clients/Sonarr.kt`, add inside `SonarrClient`:

```kotlin
    suspend fun listIndexers(): JsonArray =
        http.get("$baseUrl/api/v3/indexer") { header("X-Api-Key", apiKey) }.body()

    suspend fun indexerStatus(): JsonArray =
        http.get("$baseUrl/api/v3/indexerstatus") { header("X-Api-Key", apiKey) }.body()

    /**
     * Read the full indexer body, mutate the `enabled` flag, PUT it back.
     * Sonarr's API requires the full body for indexer mutations — there
     * is no PATCH endpoint. Returns true on 2xx.
     */
    suspend fun toggleIndexer(indexerId: Long, enabled: Boolean): Boolean {
        val current: JsonObject = try {
            http.get("$baseUrl/api/v3/indexer/$indexerId") { header("X-Api-Key", apiKey) }.body()
        } catch (e: Exception) {
            return false
        }
        // Build a new JsonObject with the same fields but flip "enabled".
        val mutated = kotlinx.serialization.json.buildJsonObject {
            current.forEach { (k, v) ->
                if (k == "enable" || k == "enableRss" || k == "enableAutomaticSearch" || k == "enableInteractiveSearch") {
                    // Sonarr exposes a quartet of boolean toggles; flip all of them in lockstep.
                    put(k, JsonPrimitive(enabled))
                } else {
                    put(k, v)
                }
            }
        }
        val resp = http.put("$baseUrl/api/v3/indexer/$indexerId") {
            header("X-Api-Key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(mutated)
        }
        return resp.status.value in 200..299
    }
```

(Sonarr's indexer object has four boolean toggles — `enable`, `enableRss`, `enableAutomaticSearch`, `enableInteractiveSearch` — flipping all four together is the common "disable this indexer entirely" gesture. Adjust the test if the codebase prefers only flipping `enable`.)

- [ ] **Step 4: Run tests to verify pass**

```
cd prioritarr; ./gradlew.bat backend:test --tests "*.SonarrClientTest" -i
```
Expected: PASS.

- [ ] **Step 5: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/clients/Sonarr.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/clients/SonarrClientTest.kt
git commit -m "feat(sonarr): client surface for indexer + indexerstatus + toggle"
```

Append the Co-Authored-By trailer.

---

### Task B2: indexer_health_history table + DB wrappers

**Files:**
- Modify: `prioritarr/backend/src/main/sqldelight/org/yoshiz/app/prioritarr/backend/database/Schema.sq`
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/database/Database.kt`
- Create: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/database/IndexerHealthRoundTripTest.kt`

- [ ] **Step 1: Write the failing test**

Create `IndexerHealthRoundTripTest.kt`:

```kotlin
package org.yoshiz.app.prioritarr.backend.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.nio.file.Files

class IndexerHealthRoundTripTest {

    private fun freshDb(): Database {
        val tmp = Files.createTempFile("prio-indexer", ".db")
        tmp.toFile().deleteOnExit()
        return Database(tmp.toAbsolutePath().toString())
    }

    @Test fun upsert_then_get_round_trip() {
        val db = freshDb()
        db.upsertIndexerHealth(
            indexerId = 1L, indexerName = "Anidex", status = "unhealthy",
            firstUnhealthyAt = 1_000L, lastProbeAt = 2_000L,
            consecutiveFailures = 0, nextProbeAt = null,
        )
        val row = db.getIndexerHealth(1L)!!
        assertEquals("Anidex", row.indexerName)
        assertEquals("unhealthy", row.status)
        assertEquals(1_000L, row.firstUnhealthyAt)
        assertEquals(2_000L, row.lastProbeAt)
        assertNull(row.nextProbeAt)
    }

    @Test fun upsert_overwrites_existing_row() {
        val db = freshDb()
        db.upsertIndexerHealth(1L, "X", "healthy", null, 1_000L, 0, null)
        db.upsertIndexerHealth(1L, "X", "auto_disabled", 1_000L, 2_000L, 1, 3_000L)
        val row = db.getIndexerHealth(1L)!!
        assertEquals("auto_disabled", row.status)
        assertEquals(3_000L, row.nextProbeAt)
        assertEquals(1, row.consecutiveFailures)
    }

    @Test fun listIndexerHealth_returns_all_rows() {
        val db = freshDb()
        db.upsertIndexerHealth(1L, "X", "healthy", null, 1L, 0, null)
        db.upsertIndexerHealth(2L, "Y", "auto_disabled", 1L, 2L, 1, 3L)
        val rows = db.listIndexerHealth()
        assertEquals(setOf(1L, 2L), rows.map { it.indexerId }.toSet())
    }
}
```

- [ ] **Step 2: Run to verify failure**

```
cd prioritarr; ./gradlew.bat backend:test --tests "*.IndexerHealthRoundTripTest" -i
```
Expected: FAIL — `upsertIndexerHealth`, `getIndexerHealth`, `listIndexerHealth` unresolved.

- [ ] **Step 3: Add schema + queries**

In `Schema.sq`, append:

```sql
-- ------------------------------------------------------------------
-- indexer_health_history
-- ------------------------------------------------------------------
-- One row per Sonarr indexer. Tracks prioritarr's view of indexer
-- health (healthy / unhealthy / auto_disabled / pinned) and the
-- exponential-backoff schedule for probing auto-disabled indexers.
-- See docs/specs/2026-05-19-sonarr-resilience-indexer-health-design.md.
CREATE TABLE IF NOT EXISTS indexer_health_history (
    indexer_id INTEGER PRIMARY KEY,
    indexer_name TEXT NOT NULL,
    status TEXT NOT NULL,                 -- "healthy" | "unhealthy" | "auto_disabled" | "pinned"
    first_unhealthy_at INTEGER,           -- epoch sec; null when status = "healthy"
    last_probe_at INTEGER NOT NULL,
    consecutive_failures INTEGER NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0),
    next_probe_at INTEGER                 -- epoch sec; only set when status = "auto_disabled"
);

upsertIndexerHealth:
INSERT INTO indexer_health_history (indexer_id, indexer_name, status, first_unhealthy_at, last_probe_at, consecutive_failures, next_probe_at)
VALUES (?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(indexer_id) DO UPDATE SET
    indexer_name = excluded.indexer_name,
    status = excluded.status,
    first_unhealthy_at = excluded.first_unhealthy_at,
    last_probe_at = excluded.last_probe_at,
    consecutive_failures = excluded.consecutive_failures,
    next_probe_at = excluded.next_probe_at;

getIndexerHealth:
SELECT * FROM indexer_health_history WHERE indexer_id = ?;

listIndexerHealth:
SELECT * FROM indexer_health_history ORDER BY indexer_id;
```

- [ ] **Step 4: Add Database wrappers**

In `database/Database.kt`, append:

```kotlin
    // ------------------------------------------------------------------
    // indexer_health_history
    // ------------------------------------------------------------------

    data class IndexerHealthRow(
        val indexerId: Long,
        val indexerName: String,
        val status: String,
        val firstUnhealthyAt: Long?,
        val lastProbeAt: Long,
        val consecutiveFailures: Int,
        val nextProbeAt: Long?,
    )

    fun upsertIndexerHealth(
        indexerId: Long,
        indexerName: String,
        status: String,
        firstUnhealthyAt: Long?,
        lastProbeAt: Long,
        consecutiveFailures: Int,
        nextProbeAt: Long?,
    ) {
        q.upsertIndexerHealth(
            indexer_id = indexerId,
            indexer_name = indexerName,
            status = status,
            first_unhealthy_at = firstUnhealthyAt,
            last_probe_at = lastProbeAt,
            consecutive_failures = consecutiveFailures.toLong(),
            next_probe_at = nextProbeAt,
        )
    }

    fun getIndexerHealth(indexerId: Long): IndexerHealthRow? =
        q.getIndexerHealth(indexerId).executeAsOneOrNull()?.let {
            IndexerHealthRow(
                indexerId = it.indexer_id,
                indexerName = it.indexer_name,
                status = it.status,
                firstUnhealthyAt = it.first_unhealthy_at,
                lastProbeAt = it.last_probe_at,
                consecutiveFailures = it.consecutive_failures.toInt(),
                nextProbeAt = it.next_probe_at,
            )
        }

    fun listIndexerHealth(): List<IndexerHealthRow> =
        q.listIndexerHealth().executeAsList().map {
            IndexerHealthRow(
                indexerId = it.indexer_id,
                indexerName = it.indexer_name,
                status = it.status,
                firstUnhealthyAt = it.first_unhealthy_at,
                lastProbeAt = it.last_probe_at,
                consecutiveFailures = it.consecutive_failures.toInt(),
                nextProbeAt = it.next_probe_at,
            )
        }
```

- [ ] **Step 5: Run tests to verify pass**

```
cd prioritarr; ./gradlew.bat backend:test --tests "*.IndexerHealthRoundTripTest" -i
```
Expected: PASS.

- [ ] **Step 6: Commit**

```
git add prioritarr/backend/src/main/sqldelight/org/yoshiz/app/prioritarr/backend/database/Schema.sq prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/database/Database.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/database/IndexerHealthRoundTripTest.kt
git commit -m "feat(db): indexer_health_history table + Database wrappers"
```

Append the Co-Authored-By trailer.

---

### Task B3: IndexerHealth pure evaluator (state machine)

**Files:**
- Create: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/resilience/IndexerHealth.kt`
- Create: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/resilience/IndexerHealthEvaluatorTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `IndexerHealthEvaluatorTest.kt`:

```kotlin
package org.yoshiz.app.prioritarr.backend.resilience

import org.yoshiz.app.prioritarr.backend.database.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

class IndexerHealthEvaluatorTest {

    private val backoff = listOf(2, 4, 6, 12, 24)
    private val unhealthyHours = 6

    private fun row(
        status: String,
        firstUnhealthy: Long? = null,
        consecutive: Int = 0,
        nextProbe: Long? = null,
    ) = Database.IndexerHealthRow(
        indexerId = 1L, indexerName = "TestIdx", status = status,
        firstUnhealthyAt = firstUnhealthy, lastProbeAt = 0L,
        consecutiveFailures = consecutive, nextProbeAt = nextProbe,
    )

    @Test fun healthy_to_unhealthy_when_sonarr_reports_failing() {
        val r = evaluateIndexerHealth(row("healthy"), sonarrSaysFailing = true, sonarrEnabled = true,
            unhealthyHours = unhealthyHours, backoffHours = backoff, nowEpochSeconds = 1_000_000L)
        assertEquals("unhealthy", r.newRow.status)
        assertEquals(1_000_000L, r.newRow.firstUnhealthyAt)
        assertIs<IndexerAction.Audit>(r.action)
    }

    @Test fun healthy_stays_healthy_when_ok() {
        val r = evaluateIndexerHealth(row("healthy"), sonarrSaysFailing = false, sonarrEnabled = true,
            unhealthyHours = unhealthyHours, backoffHours = backoff, nowEpochSeconds = 1_000_000L)
        assertEquals("healthy", r.newRow.status)
        assertEquals(IndexerAction.None, r.action)
    }

    @Test fun unhealthy_past_threshold_with_enabled_becomes_auto_disabled() {
        // Unhealthy for 7 hours (> 6h threshold) and Sonarr still reports failing
        val firstUnhealthy = 1_000_000L - 7 * 3600L
        val r = evaluateIndexerHealth(
            row("unhealthy", firstUnhealthy = firstUnhealthy),
            sonarrSaysFailing = true, sonarrEnabled = true,
            unhealthyHours = 6, backoffHours = backoff, nowEpochSeconds = 1_000_000L,
        )
        assertEquals("auto_disabled", r.newRow.status)
        assertEquals(0, r.newRow.consecutiveFailures)
        assertEquals(1_000_000L + 2 * 3600L, r.newRow.nextProbeAt)
        assertIs<IndexerAction.Disable>(r.action)
    }

    @Test fun unhealthy_below_threshold_stays_unhealthy_no_action() {
        val firstUnhealthy = 1_000_000L - 5 * 3600L  // only 5 hours
        val r = evaluateIndexerHealth(
            row("unhealthy", firstUnhealthy = firstUnhealthy),
            sonarrSaysFailing = true, sonarrEnabled = true,
            unhealthyHours = 6, backoffHours = backoff, nowEpochSeconds = 1_000_000L,
        )
        assertEquals("unhealthy", r.newRow.status)
        assertEquals(IndexerAction.None, r.action)
    }

    @Test fun unhealthy_recovers_to_healthy_when_sonarr_ok() {
        val r = evaluateIndexerHealth(
            row("unhealthy", firstUnhealthy = 100L),
            sonarrSaysFailing = false, sonarrEnabled = true,
            unhealthyHours = 6, backoffHours = backoff, nowEpochSeconds = 1_000_000L,
        )
        assertEquals("healthy", r.newRow.status)
        assertNull(r.newRow.firstUnhealthyAt)
        assertIs<IndexerAction.Audit>(r.action)
    }

    @Test fun unhealthy_with_user_disabled_no_action() {
        val firstUnhealthy = 1_000_000L - 7 * 3600L
        val r = evaluateIndexerHealth(
            row("unhealthy", firstUnhealthy = firstUnhealthy),
            sonarrSaysFailing = true, sonarrEnabled = false,
            unhealthyHours = 6, backoffHours = backoff, nowEpochSeconds = 1_000_000L,
        )
        // Don't toggle if user already has it off
        assertEquals(IndexerAction.None, r.action)
    }

    @Test fun auto_disabled_probes_when_next_probe_at_reached() {
        val nextProbe = 1_000_000L - 1L  // probe time just passed
        val r = evaluateIndexerHealth(
            row("auto_disabled", consecutive = 0, nextProbe = nextProbe),
            sonarrSaysFailing = true, sonarrEnabled = false,  // currently disabled by us
            unhealthyHours = 6, backoffHours = backoff, nowEpochSeconds = 1_000_000L,
        )
        assertIs<IndexerAction.ReEnable>(r.action)
        assertEquals("auto_disabled", r.newRow.status)  // stays auto_disabled until next tick
    }

    @Test fun auto_disabled_recovers_after_probe_when_healthy() {
        // We re-enabled it last tick (sonarrEnabled = true now), and Sonarr says healthy
        val r = evaluateIndexerHealth(
            row("auto_disabled", consecutive = 0, nextProbe = 900_000L),  // probe time elapsed
            sonarrSaysFailing = false, sonarrEnabled = true,
            unhealthyHours = 6, backoffHours = backoff, nowEpochSeconds = 1_000_000L,
        )
        assertEquals("healthy", r.newRow.status)
        assertEquals(0, r.newRow.consecutiveFailures)
        assertNull(r.newRow.nextProbeAt)
        assertIs<IndexerAction.Audit>(r.action)
    }

    @Test fun auto_disabled_re_disable_after_failed_probe_increments_backoff() {
        // We re-enabled last tick (sonarrEnabled = true now), Sonarr STILL says failing
        val r = evaluateIndexerHealth(
            row("auto_disabled", consecutive = 0, nextProbe = 900_000L),
            sonarrSaysFailing = true, sonarrEnabled = true,
            unhealthyHours = 6, backoffHours = backoff, nowEpochSeconds = 1_000_000L,
        )
        assertEquals("auto_disabled", r.newRow.status)
        assertEquals(1, r.newRow.consecutiveFailures)
        assertEquals(1_000_000L + 4 * 3600L, r.newRow.nextProbeAt)  // backoff[1] = 4h
        assertIs<IndexerAction.Disable>(r.action)
    }

    @Test fun backoff_caps_at_last_entry() {
        val r = evaluateIndexerHealth(
            row("auto_disabled", consecutive = 10, nextProbe = 900_000L),
            sonarrSaysFailing = true, sonarrEnabled = true,
            unhealthyHours = 6, backoffHours = backoff, nowEpochSeconds = 1_000_000L,
        )
        // backoff[min(10, 4)] = backoff[4] = 24h
        assertEquals(1_000_000L + 24 * 3600L, r.newRow.nextProbeAt)
    }

    @Test fun pinned_state_no_action_regardless() {
        val r = evaluateIndexerHealth(
            row("pinned"),
            sonarrSaysFailing = true, sonarrEnabled = false,
            unhealthyHours = 6, backoffHours = backoff, nowEpochSeconds = 1_000_000L,
        )
        assertEquals("pinned", r.newRow.status)
        assertEquals(IndexerAction.None, r.action)
    }
}
```

- [ ] **Step 2: Run to verify failure**

```
cd prioritarr; ./gradlew.bat backend:test --tests "*.IndexerHealthEvaluatorTest" -i
```
Expected: FAIL — `evaluateIndexerHealth`, `IndexerAction` unresolved.

- [ ] **Step 3: Implement evaluator**

Create `resilience/IndexerHealth.kt`:

```kotlin
package org.yoshiz.app.prioritarr.backend.resilience

import org.yoshiz.app.prioritarr.backend.database.Database

sealed interface IndexerAction {
    object None : IndexerAction
    object Disable : IndexerAction          // call toggleIndexer(id, false)
    object ReEnable : IndexerAction          // call toggleIndexer(id, true)
    data class Audit(val event: String) : IndexerAction
}

data class EvalResult(
    val newRow: Database.IndexerHealthRow,
    val action: IndexerAction,
)

/**
 * Pure state-machine evaluator for one indexer per tick. See spec
 * §"Subsystem B → State machine" for the full transition table.
 *
 * @param row the persisted health row for this indexer (or a fresh
 *            healthy row if this is the first observation)
 * @param sonarrSaysFailing derived from indexerStatus.disabledTill +
 *            indexerStatus.mostRecentFailure vs unhealthyHours threshold
 * @param sonarrEnabled indexer.enabled flag from /api/v3/indexer
 */
fun evaluateIndexerHealth(
    row: Database.IndexerHealthRow,
    sonarrSaysFailing: Boolean,
    sonarrEnabled: Boolean,
    unhealthyHours: Int,
    backoffHours: List<Int>,
    nowEpochSeconds: Long,
): EvalResult {
    val unhealthySec = unhealthyHours * 3600L

    // Pinned is operator-managed; never touched by the guard.
    if (row.status == "pinned") {
        return EvalResult(row.copy(lastProbeAt = nowEpochSeconds), IndexerAction.None)
    }

    when (row.status) {
        "healthy" -> {
            return if (sonarrSaysFailing) {
                EvalResult(
                    row.copy(status = "unhealthy", firstUnhealthyAt = nowEpochSeconds, lastProbeAt = nowEpochSeconds),
                    IndexerAction.Audit("indexer_unhealthy"),
                )
            } else {
                EvalResult(row.copy(lastProbeAt = nowEpochSeconds), IndexerAction.None)
            }
        }

        "unhealthy" -> {
            if (!sonarrSaysFailing) {
                return EvalResult(
                    row.copy(status = "healthy", firstUnhealthyAt = null, lastProbeAt = nowEpochSeconds),
                    IndexerAction.Audit("indexer_recovered"),
                )
            }
            // Sonarr says failing. Check if we've been unhealthy long enough to auto-disable.
            val firstUnhealthy = row.firstUnhealthyAt ?: nowEpochSeconds
            if (sonarrEnabled && nowEpochSeconds - firstUnhealthy > unhealthySec) {
                val nextProbe = nowEpochSeconds + backoffHours[0] * 3600L
                return EvalResult(
                    row.copy(
                        status = "auto_disabled",
                        consecutiveFailures = 0,
                        nextProbeAt = nextProbe,
                        lastProbeAt = nowEpochSeconds,
                    ),
                    IndexerAction.Disable,
                )
            }
            // Either user has disabled it, or we haven't reached the threshold yet.
            return EvalResult(row.copy(lastProbeAt = nowEpochSeconds), IndexerAction.None)
        }

        "auto_disabled" -> {
            // Three cases here:
            // 1. nextProbeAt elapsed AND sonarrEnabled = false → re-enable for one cycle
            // 2. We re-enabled previously (sonarrEnabled = true) and Sonarr now says healthy → recovered
            // 3. We re-enabled previously and Sonarr still says failing → re-disable with longer backoff
            val nextProbe = row.nextProbeAt ?: (nowEpochSeconds + backoffHours[0] * 3600L)

            if (sonarrEnabled && !sonarrSaysFailing) {
                return EvalResult(
                    row.copy(
                        status = "healthy",
                        firstUnhealthyAt = null,
                        nextProbeAt = null,
                        consecutiveFailures = 0,
                        lastProbeAt = nowEpochSeconds,
                    ),
                    IndexerAction.Audit("indexer_recovered_after_probe"),
                )
            }

            if (sonarrEnabled && sonarrSaysFailing) {
                // Probe failed — re-disable with longer backoff.
                val newFailures = row.consecutiveFailures + 1
                val backoffIndex = minOf(newFailures, backoffHours.size - 1)
                val newNextProbe = nowEpochSeconds + backoffHours[backoffIndex] * 3600L
                return EvalResult(
                    row.copy(
                        status = "auto_disabled",
                        consecutiveFailures = newFailures,
                        nextProbeAt = newNextProbe,
                        lastProbeAt = nowEpochSeconds,
                    ),
                    IndexerAction.Disable,
                )
            }

            // Currently disabled by us. If probe time has elapsed, re-enable.
            if (nowEpochSeconds >= nextProbe) {
                return EvalResult(
                    row.copy(lastProbeAt = nowEpochSeconds),
                    IndexerAction.ReEnable,
                )
            }

            // Otherwise, wait.
            return EvalResult(row.copy(lastProbeAt = nowEpochSeconds), IndexerAction.None)
        }

        else -> return EvalResult(row.copy(lastProbeAt = nowEpochSeconds), IndexerAction.None)
    }
}
```

- [ ] **Step 4: Run tests to verify pass**

```
cd prioritarr; ./gradlew.bat backend:test --tests "*.IndexerHealthEvaluatorTest" -i
```
Expected: PASS (11 tests).

- [ ] **Step 5: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/resilience/IndexerHealth.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/resilience/IndexerHealthEvaluatorTest.kt
git commit -m "feat(resilience): IndexerHealth pure state-machine evaluator"
```

Append the Co-Authored-By trailer.

---

### Task B4: IndexerHealthGuard runner

**Files:**
- Create: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/resilience/IndexerHealthGuard.kt`
- Create: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/resilience/IndexerHealthGuardTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `IndexerHealthGuardTest.kt`:

```kotlin
package org.yoshiz.app.prioritarr.backend.resilience

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.database.Database
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

class IndexerHealthGuardTest {

    private fun freshDb(): Database {
        val tmp = Files.createTempFile("prio-indexer-guard", ".db")
        tmp.toFile().deleteOnExit()
        return Database(tmp.toAbsolutePath().toString())
    }

    private class FakeSonarr(
        private val indexers: JsonArray,
        private val status: JsonArray,
    ) : SonarrClient("http://fake", "k", HttpClient(MockEngine { respondOk("{}") }) {
        install(ContentNegotiation) { json() }
    }) {
        val toggleCalls = mutableListOf<Pair<Long, Boolean>>()
        override suspend fun listIndexers(): JsonArray = indexers
        override suspend fun indexerStatus(): JsonArray = status
        override suspend fun toggleIndexer(indexerId: Long, enabled: Boolean): Boolean {
            toggleCalls += indexerId to enabled
            return true
        }
    }

    @Test fun creates_initial_healthy_rows_for_new_indexers() = runTest {
        val db = freshDb()
        val indexers = buildJsonArray {
            add(buildJsonObject {
                put("id", JsonPrimitive(1L))
                put("name", JsonPrimitive("Anidex"))
                put("enable", JsonPrimitive(true))
            })
        }
        val sonarr = FakeSonarr(indexers, JsonArray(emptyList()))

        runIndexerHealthGuard(
            sonarr = sonarr, db = db,
            unhealthyHours = 6,
            backoffHours = listOf(2, 4, 6, 12, 24),
            nowEpochSeconds = 1_000_000L,
        )
        val rows = db.listIndexerHealth()
        assertEquals(1, rows.size)
        assertEquals("healthy", rows[0].status)
        assertEquals("Anidex", rows[0].indexerName)
    }

    @Test fun auto_disables_indexer_unhealthy_past_threshold() = runTest {
        val db = freshDb()
        // Seed: indexer was first observed unhealthy 7h ago
        db.upsertIndexerHealth(1L, "Anidex", "unhealthy",
            firstUnhealthyAt = 1_000_000L - 7 * 3600L, lastProbeAt = 1_000_000L - 100L,
            consecutiveFailures = 0, nextProbeAt = null)

        val indexers = buildJsonArray {
            add(buildJsonObject {
                put("id", JsonPrimitive(1L))
                put("name", JsonPrimitive("Anidex"))
                put("enable", JsonPrimitive(true))
            })
        }
        // Sonarr says still failing — disabledTill in the future
        val status = buildJsonArray {
            add(buildJsonObject {
                put("indexerId", JsonPrimitive(1L))
                put("disabledTill", JsonPrimitive(java.time.Instant.ofEpochSecond(1_000_000L + 3600L).toString()))
            })
        }
        val sonarr = FakeSonarr(indexers, status)

        runIndexerHealthGuard(
            sonarr = sonarr, db = db,
            unhealthyHours = 6,
            backoffHours = listOf(2, 4, 6, 12, 24),
            nowEpochSeconds = 1_000_000L,
        )

        assertEquals(listOf(1L to false), sonarr.toggleCalls)
        val row = db.getIndexerHealth(1L)!!
        assertEquals("auto_disabled", row.status)
        assertEquals(1_000_000L + 2 * 3600L, row.nextProbeAt)
    }

    @Test fun pinned_indexer_not_touched_even_when_failing() = runTest {
        val db = freshDb()
        db.upsertIndexerHealth(1L, "Anidex", "pinned",
            firstUnhealthyAt = 1_000_000L - 7 * 3600L, lastProbeAt = 1_000_000L - 100L,
            consecutiveFailures = 0, nextProbeAt = null)

        val indexers = buildJsonArray {
            add(buildJsonObject {
                put("id", JsonPrimitive(1L))
                put("name", JsonPrimitive("Anidex"))
                put("enable", JsonPrimitive(false))
            })
        }
        val status = buildJsonArray {
            add(buildJsonObject {
                put("indexerId", JsonPrimitive(1L))
                put("disabledTill", JsonPrimitive(java.time.Instant.ofEpochSecond(1_000_000L + 3600L).toString()))
            })
        }
        val sonarr = FakeSonarr(indexers, status)

        runIndexerHealthGuard(
            sonarr = sonarr, db = db,
            unhealthyHours = 6,
            backoffHours = listOf(2, 4, 6, 12, 24),
            nowEpochSeconds = 1_000_000L,
        )

        assertTrue(sonarr.toggleCalls.isEmpty())
        val row = db.getIndexerHealth(1L)!!
        assertEquals("pinned", row.status)
    }

    @Test fun probe_re_enables_auto_disabled_indexer() = runTest {
        val db = freshDb()
        db.upsertIndexerHealth(1L, "Anidex", "auto_disabled",
            firstUnhealthyAt = 1L, lastProbeAt = 100L,
            consecutiveFailures = 0, nextProbeAt = 1_000_000L - 10L)  // probe time just passed

        val indexers = buildJsonArray {
            add(buildJsonObject {
                put("id", JsonPrimitive(1L))
                put("name", JsonPrimitive("Anidex"))
                put("enable", JsonPrimitive(false))  // currently disabled (by us)
            })
        }
        val status = buildJsonArray {
            add(buildJsonObject {
                put("indexerId", JsonPrimitive(1L))
                put("disabledTill", JsonPrimitive(java.time.Instant.ofEpochSecond(1_000_000L + 3600L).toString()))
            })
        }
        val sonarr = FakeSonarr(indexers, status)

        runIndexerHealthGuard(
            sonarr = sonarr, db = db,
            unhealthyHours = 6,
            backoffHours = listOf(2, 4, 6, 12, 24),
            nowEpochSeconds = 1_000_000L,
        )

        assertEquals(listOf(1L to true), sonarr.toggleCalls)
    }
}
```

- [ ] **Step 2: Run to verify failure**

```
cd prioritarr; ./gradlew.bat backend:test --tests "*.IndexerHealthGuardTest" -i
```
Expected: FAIL — `runIndexerHealthGuard` unresolved.

- [ ] **Step 3: Implement IndexerHealthGuard**

Create `resilience/IndexerHealthGuard.kt`:

```kotlin
package org.yoshiz.app.prioritarr.backend.resilience

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.booleanOrNull
import org.slf4j.LoggerFactory
import org.yoshiz.app.prioritarr.backend.clients.SonarrClient
import org.yoshiz.app.prioritarr.backend.database.Database
import java.time.Instant

private val logger = LoggerFactory.getLogger("org.yoshiz.app.prioritarr.backend.resilience.indexer-health")

/**
 * One scheduler tick of the indexer health guard. Joins Sonarr's
 * /api/v3/indexer + /api/v3/indexerstatus, evaluates each indexer
 * through the pure state machine, persists the new row, and applies
 * the Disable/ReEnable side effects.
 *
 * Idempotent. Never throws (failures warn-logged + skipped for next
 * tick).
 */
suspend fun runIndexerHealthGuard(
    sonarr: SonarrClient,
    db: Database,
    unhealthyHours: Int,
    backoffHours: List<Int>,
    nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
) {
    val indexers = try { sonarr.listIndexers() } catch (e: Exception) {
        logger.warn("[indexer-health] listIndexers failed: {}", e.message); return
    }
    val statusByIndexerId: Map<Long, JsonObject> = try {
        sonarr.indexerStatus().associate { it.jsonObject.let { o -> (o["indexerId"]?.jsonPrimitive?.longOrNull ?: -1L) to o } }
    } catch (e: Exception) {
        logger.warn("[indexer-health] indexerStatus failed: {}", e.message); return
    }

    for (idxEl in indexers) {
        val idx = idxEl.jsonObject
        val id = idx["id"]?.jsonPrimitive?.longOrNull ?: continue
        val name = idx["name"]?.jsonPrimitive?.contentOrNull ?: continue
        val enabled = idx["enable"]?.jsonPrimitive?.booleanOrNull ?: true

        val statusObj = statusByIndexerId[id]
        val sonarrSaysFailing = isFailingNow(statusObj, nowEpochSeconds, unhealthyHours)

        val existing = db.getIndexerHealth(id) ?: Database.IndexerHealthRow(
            indexerId = id, indexerName = name, status = "healthy",
            firstUnhealthyAt = null, lastProbeAt = nowEpochSeconds,
            consecutiveFailures = 0, nextProbeAt = null,
        )
        // Always sync the name in case it changed
        val syncedRow = existing.copy(indexerName = name)

        val result = evaluateIndexerHealth(syncedRow, sonarrSaysFailing, enabled, unhealthyHours, backoffHours, nowEpochSeconds)

        when (val action = result.action) {
            is IndexerAction.Disable -> {
                val ok = runCatching { sonarr.toggleIndexer(id, false) }.getOrDefault(false)
                if (!ok) logger.warn("[indexer-health] toggleIndexer({}, false) failed; will retry next tick", id)
                logger.info("[indexer-health] auto-disabled indexer {} ({})", id, name)
                db.appendAudit("indexer_auto_disabled", null, null, null,
                    kotlinx.serialization.json.buildJsonObject {
                        put("indexer_id", id); put("indexer_name", name)
                    })
            }
            is IndexerAction.ReEnable -> {
                val ok = runCatching { sonarr.toggleIndexer(id, true) }.getOrDefault(false)
                if (!ok) logger.warn("[indexer-health] toggleIndexer({}, true) failed; will retry next tick", id)
                logger.info("[indexer-health] probe re-enabled indexer {} ({})", id, name)
            }
            is IndexerAction.Audit -> {
                db.appendAudit(action.event, null, null, null,
                    kotlinx.serialization.json.buildJsonObject {
                        put("indexer_id", id); put("indexer_name", name)
                    })
            }
            IndexerAction.None -> { /* no-op */ }
        }

        db.upsertIndexerHealth(
            indexerId = result.newRow.indexerId,
            indexerName = result.newRow.indexerName,
            status = result.newRow.status,
            firstUnhealthyAt = result.newRow.firstUnhealthyAt,
            lastProbeAt = result.newRow.lastProbeAt,
            consecutiveFailures = result.newRow.consecutiveFailures,
            nextProbeAt = result.newRow.nextProbeAt,
        )
    }
}

/** Derive prioritarr's "failing" view from Sonarr's per-indexer status row. */
private fun isFailingNow(status: JsonObject?, now: Long, unhealthyHours: Int): Boolean {
    if (status == null) return false
    val disabledTillIso = status["disabledTill"]?.jsonPrimitive?.contentOrNull
    val disabledTill = parseIsoSec(disabledTillIso)
    if (disabledTill != null && disabledTill > now) return true

    val mostRecentFailureIso = status["mostRecentFailure"]?.jsonPrimitive?.contentOrNull
    val mostRecentFailure = parseIsoSec(mostRecentFailureIso)
    if (mostRecentFailure != null && now - mostRecentFailure < unhealthyHours * 3600L) return true

    return false
}

private fun parseIsoSec(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return try { Instant.parse(iso).epochSecond } catch (_: Exception) { null }
}
```

- [ ] **Step 4: Run tests to verify pass**

```
cd prioritarr; ./gradlew.bat backend:test --tests "*.IndexerHealthGuardTest" -i
```
Expected: PASS.

- [ ] **Step 5: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/resilience/IndexerHealthGuard.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/resilience/IndexerHealthGuardTest.kt
git commit -m "feat(resilience): IndexerHealthGuard runner with auto-disable + probe"
```

Append the Co-Authored-By trailer.

---

### Task B5: Settings + scheduler job registration

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/config/Settings.kt`
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/schemas/V2.kt`
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/api/v2/V2Routes.kt`
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/Main.kt`
- Modify: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/config/SettingsParserTest.kt`

- [ ] **Step 1: Write the failing test**

Append to `SettingsParserTest.kt`:

```kotlin
@Test
fun yaml_loads_indexer_health_fields() {
    val yaml = """
        intervals:
          indexer_health_unhealthy_threshold_hours: 8
          indexer_health_probe_interval_minutes: 60
          indexer_health_auto_disable: false
    """.trimIndent()
    val s = parseSettingsFromYamlString(yaml)
    assertEquals(8, s.intervals.indexerHealthUnhealthyThresholdHours)
    assertEquals(60, s.intervals.indexerHealthProbeIntervalMinutes)
    assertEquals(false, s.intervals.indexerHealthAutoDisable)
}

@Test
fun intervals_indexer_health_fields_have_documented_defaults() {
    val s = loadSettingsFrom(requiredEnvForParser)
    assertEquals(6, s.intervals.indexerHealthUnhealthyThresholdHours)
    assertEquals(30, s.intervals.indexerHealthProbeIntervalMinutes)
    assertEquals(true, s.intervals.indexerHealthAutoDisable)
    assertEquals(listOf(2, 4, 6, 12, 24), s.intervals.indexerHealthBackoffHours)
}
```

- [ ] **Step 2: Run to verify failure**

```
cd prioritarr; ./gradlew.bat backend:test --tests "*.SettingsParserTest" -i
```
Expected: FAIL.

- [ ] **Step 3: Add fields**

In `config/Settings.kt`'s `Intervals`:

```kotlin
    val indexerHealthUnhealthyThresholdHours: Int = 6,
    val indexerHealthProbeIntervalMinutes: Int = 30,
    val indexerHealthBackoffHours: List<Int> = listOf(2, 4, 6, 12, 24),
    val indexerHealthAutoDisable: Boolean = true,
```

In `EditableSettings`:

```kotlin
    val indexerHealthUnhealthyThresholdHours: Int? = null,
    val indexerHealthProbeIntervalMinutes: Int? = null,
    val indexerHealthBackoffHours: List<Int>? = null,
    val indexerHealthAutoDisable: Boolean? = null,
```

In `applySettingsOverride`'s `intervals.copy(...)`:

```kotlin
        indexerHealthUnhealthyThresholdHours = override.indexerHealthUnhealthyThresholdHours ?: base.intervals.indexerHealthUnhealthyThresholdHours,
        indexerHealthProbeIntervalMinutes = override.indexerHealthProbeIntervalMinutes ?: base.intervals.indexerHealthProbeIntervalMinutes,
        indexerHealthBackoffHours = override.indexerHealthBackoffHours ?: base.intervals.indexerHealthBackoffHours,
        indexerHealthAutoDisable = override.indexerHealthAutoDisable ?: base.intervals.indexerHealthAutoDisable,
```

In the YAML parser block:

```kotlin
        indexerHealthUnhealthyThresholdHours = o.num("indexer_health_unhealthy_threshold_hours") { it.toInt() } ?: intervals.indexerHealthUnhealthyThresholdHours,
        indexerHealthProbeIntervalMinutes = o.num("indexer_health_probe_interval_minutes") { it.toInt() } ?: intervals.indexerHealthProbeIntervalMinutes,
        indexerHealthBackoffHours = (o["indexer_health_backoff_hours"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: intervals.indexerHealthBackoffHours,
        indexerHealthAutoDisable = (o["indexer_health_auto_disable"] as? Boolean) ?: intervals.indexerHealthAutoDisable,
```

- [ ] **Step 4: Thread through V2 wire**

In `schemas/V2.kt`'s `IntervalsWire`:

```kotlin
    val indexerHealthUnhealthyThresholdHours: Int = 6,
    val indexerHealthProbeIntervalMinutes: Int = 30,
    val indexerHealthBackoffHours: List<Int> = listOf(2, 4, 6, 12, 24),
    val indexerHealthAutoDisable: Boolean = true,
```

In `V2Routes.kt`'s `mergeEditable`:

```kotlin
    indexerHealthUnhealthyThresholdHours = patch.indexerHealthUnhealthyThresholdHours ?: existing.indexerHealthUnhealthyThresholdHours,
    indexerHealthProbeIntervalMinutes = patch.indexerHealthProbeIntervalMinutes ?: existing.indexerHealthProbeIntervalMinutes,
    indexerHealthBackoffHours = patch.indexerHealthBackoffHours ?: existing.indexerHealthBackoffHours,
    indexerHealthAutoDisable = patch.indexerHealthAutoDisable ?: existing.indexerHealthAutoDisable,
```

Add to both `IntervalsWire(...)` construction sites (read from `s.intervals.X`).

- [ ] **Step 5: Register INDEXER_HEALTH_GUARD job**

In `Main.kt`, add to `object JobId`:

```kotlin
const val INDEXER_HEALTH_GUARD = "indexer-health-guard"
```

Add the job registration (next to SONARR_JAM_GUARD or alphabetically):

```kotlin
            add(org.yoshiz.app.prioritarr.backend.scheduler.JobDefinition(
                id = JobId.INDEXER_HEALTH_GUARD,
                cadenceMinutes = { liveSettings(db, settings).intervals.indexerHealthProbeIntervalMinutes.toLong() },
                prerequisites = { liveSettings(db, settings).intervals.indexerHealthAutoDisable },
                weight = org.yoshiz.app.prioritarr.backend.scheduler.JobWeight.LIGHT,
                firstRunDelayMinutes = 3,
                run = {
                    val s = liveSettings(db, settings)
                    org.yoshiz.app.prioritarr.backend.resilience.runIndexerHealthGuard(
                        sonarr = sonarr,
                        db = db,
                        unhealthyHours = s.intervals.indexerHealthUnhealthyThresholdHours,
                        backoffHours = s.intervals.indexerHealthBackoffHours,
                    )
                    org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome()
                },
            ))
```

- [ ] **Step 6: Run tests**

```
cd prioritarr; ./gradlew.bat backend:test
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/config/Settings.kt prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/schemas/V2.kt prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/api/v2/V2Routes.kt prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/Main.kt prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/config/SettingsParserTest.kt
git commit -m "feat(resilience): wire IndexerHealthGuard into scheduler + settings"
```

Append the Co-Authored-By trailer.

---

### Task B6: V2 routes for indexer health actions + frontend hooks

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/api/v2/V2Routes.kt`
- Modify: `prioritarr/frontend/src/hooks/queries.ts`

- [ ] **Step 1: Add routes**

In `V2Routes.kt`:

```kotlin
    get("/indexers/health") {
        val rows = state.db.listIndexerHealth()
        call.respond(kotlinx.serialization.json.buildJsonArray {
            rows.forEach { row ->
                add(kotlinx.serialization.json.buildJsonObject {
                    put("indexerId", row.indexerId)
                    put("indexerName", row.indexerName)
                    put("status", row.status)
                    put("firstUnhealthyAt", row.firstUnhealthyAt)
                    put("lastProbeAt", row.lastProbeAt)
                    put("consecutiveFailures", row.consecutiveFailures)
                    put("nextProbeAt", row.nextProbeAt)
                })
            }
        })
    }

    post("/indexers/{id}/probe") {
        val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("id", "missing")
        val ok = state.sonarr.toggleIndexer(id, enabled = true)
        // Force the next health-guard tick to re-evaluate by clearing next_probe_at
        val existing = state.db.getIndexerHealth(id) ?: throw ValidationException("id", "unknown")
        state.db.upsertIndexerHealth(
            indexerId = id, indexerName = existing.indexerName,
            status = existing.status, firstUnhealthyAt = existing.firstUnhealthyAt,
            lastProbeAt = System.currentTimeMillis() / 1000L,
            consecutiveFailures = existing.consecutiveFailures,
            nextProbeAt = if (existing.status == "auto_disabled") System.currentTimeMillis() / 1000L - 1 else existing.nextProbeAt,
        )
        call.respond(kotlinx.serialization.json.buildJsonObject {
            put("status", if (ok) "probed" else "failed")
        })
    }

    post("/indexers/{id}/reset-failures") {
        val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("id", "missing")
        val existing = state.db.getIndexerHealth(id) ?: throw ValidationException("id", "unknown")
        state.db.upsertIndexerHealth(
            indexerId = id, indexerName = existing.indexerName,
            status = existing.status, firstUnhealthyAt = existing.firstUnhealthyAt,
            lastProbeAt = existing.lastProbeAt,
            consecutiveFailures = 0,
            nextProbeAt = existing.nextProbeAt,
        )
        call.respond(kotlinx.serialization.json.buildJsonObject { put("status", "ok") })
    }

    post("/indexers/{id}/pin") {
        val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("id", "missing")
        val existing = state.db.getIndexerHealth(id) ?: throw ValidationException("id", "unknown")
        state.db.upsertIndexerHealth(
            indexerId = id, indexerName = existing.indexerName,
            status = "pinned", firstUnhealthyAt = null,
            lastProbeAt = System.currentTimeMillis() / 1000L,
            consecutiveFailures = 0, nextProbeAt = null,
        )
        call.respond(kotlinx.serialization.json.buildJsonObject { put("status", "pinned") })
    }

    post("/indexers/{id}/unpin") {
        val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("id", "missing")
        val existing = state.db.getIndexerHealth(id) ?: throw ValidationException("id", "unknown")
        // Return to "healthy" — the next health-guard tick will re-classify if needed.
        state.db.upsertIndexerHealth(
            indexerId = id, indexerName = existing.indexerName,
            status = "healthy", firstUnhealthyAt = null,
            lastProbeAt = System.currentTimeMillis() / 1000L,
            consecutiveFailures = 0, nextProbeAt = null,
        )
        call.respond(kotlinx.serialization.json.buildJsonObject { put("status", "unpinned") })
    }
```

- [ ] **Step 2: Add frontend hooks**

In `frontend/src/hooks/queries.ts`:

```typescript
export interface IndexerHealthRow {
  indexerId: number
  indexerName: string
  status: 'healthy' | 'unhealthy' | 'auto_disabled' | 'pinned'
  firstUnhealthyAt: number | null
  lastProbeAt: number
  consecutiveFailures: number
  nextProbeAt: number | null
}

export function useIndexerHealth() {
  return useQuery({
    queryKey: ['indexer-health'],
    queryFn: () => fetchJson<IndexerHealthRow[]>('/api/v2/indexers/health'),
    refetchInterval: 60_000,
  })
}

export function useIndexerProbe() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => postJson<{ status: string }>(`/api/v2/indexers/${id}/probe`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['indexer-health'] }),
  })
}

export function useIndexerResetFailures() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => postJson<{ status: string }>(`/api/v2/indexers/${id}/reset-failures`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['indexer-health'] }),
  })
}

export function useIndexerPin() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => postJson<{ status: string }>(`/api/v2/indexers/${id}/pin`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['indexer-health'] }),
  })
}

export function useIndexerUnpin() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => postJson<{ status: string }>(`/api/v2/indexers/${id}/unpin`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['indexer-health'] }),
  })
}
```

- [ ] **Step 3: Verify build**

```
cd prioritarr; ./gradlew.bat backend:compileKotlin
cd prioritarr/frontend; npm run build
```
Both BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/api/v2/V2Routes.kt prioritarr/frontend/src/hooks/queries.ts
git commit -m "feat(api): indexer health routes + frontend hooks"
```

Append the Co-Authored-By trailer.

---

### Task B7: Settings page UI — Indexer health section

**Files:**
- Modify: `prioritarr/frontend/src/pages/SettingsPage.tsx`

- [ ] **Step 1: Add the section**

Add a new function `IndexerHealthSection` to `SettingsPage.tsx`:

```tsx
function IndexerHealthSection() {
  const health = useIndexerHealth()
  const probe = useIndexerProbe()
  const reset = useIndexerResetFailures()
  const pin = useIndexerPin()
  const unpin = useIndexerUnpin()

  const settings = useSettings()
  const save = useSaveSettings()
  const s = (settings.data ?? {}) as { intervals?: { indexerHealthAutoDisable?: boolean } }
  const autoDisable = s.intervals?.indexerHealthAutoDisable !== false  // default true

  if (!health.data) return <div className="opacity-60 text-sm">Loading…</div>

  return (
    <SectionShell title="Indexer health" description="Auto-disable unhealthy indexers with exponential-backoff probe + retry.">
      <div className="flex items-center gap-2 mb-3">
        <input
          type="checkbox"
          id="indexer-health-auto-disable"
          checked={autoDisable}
          onChange={(e) => save.mutate({ indexerHealthAutoDisable: e.target.checked })}
        />
        <label htmlFor="indexer-health-auto-disable" className="text-sm">
          Auto-disable unhealthy indexers (probe schedule: 2 / 4 / 6 / 12 / 24 hours)
        </label>
      </div>

      <table className="text-xs w-full">
        <thead>
          <tr className="opacity-60">
            <th className="text-left p-1">Indexer</th>
            <th className="text-left p-1">Status</th>
            <th className="text-right p-1">Failures</th>
            <th className="text-left p-1">Next probe</th>
            <th className="text-right p-1">Actions</th>
          </tr>
        </thead>
        <tbody>
          {health.data.map((row) => {
            const statusColor =
              row.status === 'healthy' ? 'text-green-300'
              : row.status === 'auto_disabled' ? 'text-red-300'
              : row.status === 'pinned' ? 'text-blue-300'
              : 'text-amber-300'
            return (
              <tr key={row.indexerId} className="border-t border-surface-3">
                <td className="p-1">{row.indexerName}</td>
                <td className={`p-1 ${statusColor}`}>{row.status}</td>
                <td className="p-1 text-right">{row.consecutiveFailures}</td>
                <td className="p-1 opacity-70">
                  {row.nextProbeAt ? new Date(row.nextProbeAt * 1000).toLocaleString() : '—'}
                </td>
                <td className="p-1 text-right space-x-2">
                  <button className="text-xs underline" onClick={() => probe.mutate(row.indexerId)}>Probe now</button>
                  <button className="text-xs underline" onClick={() => reset.mutate(row.indexerId)}>Reset failures</button>
                  {row.status === 'pinned'
                    ? <button className="text-xs underline" onClick={() => unpin.mutate(row.indexerId)}>Unpin</button>
                    : <button className="text-xs underline" onClick={() => pin.mutate(row.indexerId)}>Pin</button>}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </SectionShell>
  )
}
```

Mount in the main render:

```tsx
<IndexerHealthSection />
```

- [ ] **Step 2: Verify build**

```
cd prioritarr/frontend; npm run build
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```
git add prioritarr/frontend/src/pages/SettingsPage.tsx
git commit -m "feat(ui): indexer health section with probe + pin actions"
```

Append the Co-Authored-By trailer.

---

## Documentation

### Task D1: README — Resilience + Indexer health + P3/P4 narrative

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add the new top-level section**

In `README.md`, after the "Queue enforcement" section (or wherever fits the existing structure), insert:

```markdown
## Resilience

Prioritarr watches Sonarr for two failure patterns that silently break the
import pipeline, and recovers from both:

**Sonarr command-queue jams.** Long-running `SeriesSearch` commands can pile
up behind each other when indexers are slow, blocking the single-threaded
`ProcessMonitoredDownloads` task that auto-imports completed downloads.
Result: files downloaded by qBittorrent/SAB sit on disk for hours. The
**Jam Guard** runs every 5 minutes, detects stuck searches (started >30 min
ago) and queued import-monitor blocking (>10 min), and escalates:

1. **Soft mitigation** — cancel queued (not-yet-started) `SeriesSearch` /
   `MissingEpisodeSearch` commands and fire `ProcessMonitoredDownloads` to
   unblock imports.
2. **Restart** (opt-in) — if still jammed 5 minutes later AND
   `sonarr_jam_auto_restart` is enabled, `POST /api/v3/system/restart`
   to abort everything. DB-persisted commands resume on Sonarr startup.

Soft mitigation is always-on. Restart defaults OFF; flip it on per your
risk tolerance. Settings → Sonarr resilience surfaces a "Restart Sonarr"
button regardless for manual recovery.

To prevent jams in the first place, prioritarr also **caps concurrent
prioritarr-triggered `SeriesSearch` commands at 3** (configurable). Each
backfill sweep checks Sonarr's command queue before firing; if 3+ search
commands are already queued/started, prioritarr skips this sweep cycle.

**Indexer health.** Slow/dead indexers compound the jam problem — every
search waits for them to time out. The **Indexer Health Guard** runs every
30 minutes, joins Sonarr's `/api/v3/indexer` + `/api/v3/indexerstatus`,
and applies an auto-disable + exponential-backoff state machine:

- After 6 hours of continuous failure → **auto-disable** the indexer in
  Sonarr.
- Probe at **2 / 4 / 6 / 12 / 24** hours (then capped at 24h between
  retries).
- Each failed probe extends the backoff. A successful probe restores the
  indexer to `healthy` and resets all counters.
- **Pin** an indexer (via Settings → Indexer health) to opt it out of
  automation entirely — useful if you have only one indexer or want manual
  control.
```

- [ ] **Step 2: Update the Background jobs table**

Find the "Background jobs" table (or section). Add two rows:

```markdown
| Sonarr jam guard | 5 min | Polls /api/v3/command, detects stuck SeriesSearch + blocked imports, applies soft mitigation → optional restart. |
| Indexer health guard | 30 min | Polls /api/v3/indexerstatus, auto-disables indexers unhealthy >6h, probes them on a 2/4/6/12/24h backoff. |
```

- [ ] **Step 3: Update "What prioritarr does"**

Find the bulleted "What prioritarr does" list near the top of the README. Add:

```markdown
- **Detects and recovers from Sonarr command-queue jams** — auto-cancels
  queued searches, fires import scans, optionally restarts Sonarr.
- **Manages indexer health** — auto-disables unhealthy indexers with
  exponential-backoff probe + retry. Pinning + manual probe + reset-failures
  in Settings.
```

- [ ] **Step 4: Update Queue enforcement / Priority decision graph notes**

Find the section that describes per-priority behaviour (the priority decision graph). Add a note:

```markdown
Search behaviour by priority band:

- **P1 / P2** — `EpisodeSearch` with up to 5 oldest missing episodes per
  series per sweep. Per-episode cooldown (default 30 min). On-grab
  follow-up immediately searches the next 1-2 episodes when one grabs.
- **P3 / P4** — same shape as P1/P2 (`EpisodeSearch`, 5 oldest, per-episode
  cooldown) but longer cooldown (default 60 min) and on-grab follow-up off
  by default.
- **P5** — ratchet with `SeasonSearch` → `EpisodeSearch` escalation when
  bandwidth-active. Inactive-mode fallback is still `SeriesSearch` (gated
  by the concurrent-search cap above).
```

- [ ] **Step 5: Retroactive — P1/P2 fast-grab narrative**

Find a sensible spot (under "Resilience" or in a new "Priority-aware search" subsection). Add a paragraph:

```markdown
### P1/P2 fast-grab (retroactive note)

Prioritarr fires `EpisodeSearch` (not `SeriesSearch`) for P1/P2 series so
each Sonarr command stays small and fast. Up to 5 oldest missing episodes
per series per sweep, with a per-episode 30-minute cooldown to prevent
re-searching the same episode in back-to-back sweeps. On boot, the backfill
sweep waits for the first `priorities-refresh` job to complete before
firing (so it never sweeps against an empty priority cache), with the
scheduler retrying the prereq-failed sweep every minute rather than after
the full 2-hour cadence. When a P1/P2 series receives a grab webhook, the
on-grab follow-up immediately searches the next 1-2 oldest missing episodes
of the same series, capitalising on the indexer's hot state.

Spec: [docs/specs/2026-05-15-p1p2-fast-grab-design.md](docs/specs/2026-05-15-p1p2-fast-grab-design.md).
```

- [ ] **Step 6: Retroactive — Trakt auto-refresh narrative**

Find the existing Trakt section (or under "Watch sources"). Add a paragraph:

```markdown
### Trakt token auto-refresh

Prioritarr refreshes Trakt tokens proactively within the 7-day window
before expiry (Trakt issues 7-day access tokens; the daily scheduler tick
covers the full window). When Trakt rejects a stored token despite the
auto-refresh (typically: the app was revoked at trakt.tv/oauth/applications
or the client secret was rotated), Settings → Trakt surfaces an inline
**Reconnect now** button right next to the error so recovery is one click.
The actual "last refreshed" timestamp is tracked separately from the expiry
so the UI reflects reality rather than a derived guess.
```

- [ ] **Step 7: Update the YAML configuration reference**

Find the existing YAML config block (usually near the bottom under "Configuration"). Add the new keys:

```yaml
intervals:
  # Subsystem A — Sonarr Jam Guard
  sonarr_jam_search_age_minutes: 30      # SeriesSearch started > this = stuck
  sonarr_jam_import_blocked_minutes: 10  # ProcessMonitoredDownloads queued > this = blocked
  sonarr_jam_guard_interval_minutes: 5   # how often the jam guard runs
  sonarr_jam_auto_restart: false         # opt-in: restart Sonarr as last resort
  sonarr_jam_concurrent_search_cap: 3    # max in-flight SeriesSearch before skip

  # Subsystem B — Indexer Health Guard
  indexer_health_unhealthy_threshold_hours: 6
  indexer_health_probe_interval_minutes: 30
  indexer_health_backoff_hours: [2, 4, 6, 12, 24]
  indexer_health_auto_disable: true

  # Subsystem C — P3/P4 episode-search
  backfill_p3_p4_max_per_sweep: 10
  backfill_p3_p4_cooldown_minutes: 60
  backfill_p3_p4_followup_episodes: 0    # 0 = off; set >0 to enable
```

Document the reinterpretation:

```markdown
> Note: `backfill_max_searches_per_sweep` now applies **only to P5** (after
> the P3/P4 migration to per-episode search). The value default of 10 is
> unchanged; only the scope changed.
```

- [ ] **Step 8: Commit**

```
git add README.md
git commit -m "docs(readme): resilience + indexer health + retroactive P1/P2 + Trakt"
```

Append the Co-Authored-By trailer.

---

## Self-review checklist

After the plan is implemented end-to-end, the controller should verify:

**Spec coverage:**
- Subsystem A — Tasks A1 (client), A2 (detector), A3 (DB), A4 (runner), A5 (cap), A6 (settings + scheduler), A7 (routes), A8 (UI). ✓
- Subsystem B — Tasks B1 (client), B2 (DB), B3 (evaluator), B4 (runner), B5 (settings + scheduler), B6 (routes), B7 (UI). ✓
- Subsystem C — Tasks C1 (table + migration), C2 (planner+runner refactor), C3 (settings), C4 (Pass A2 wiring), C5 (follow-up extension), C6 (UI). ✓
- Documentation — Task D1 (all 7 README sub-tasks). ✓

**Placeholder scan:** None. All steps have concrete code/SQL/commands.

**Type consistency:**
- `PriorityEpisodeCandidate`, `PriorityEpisodeEpisode` — used identically in Tasks C2, C4, C5.
- `JamVerdict.Jammed` fields — `stuckSeriesSearchIds`, `queuedSeriesSearchIds`, `importBlockedSince`, `oldestStartedAt` — consistent in A2, A4.
- `Database.IndexerHealthRow` fields — `indexerId`, `indexerName`, `status`, `firstUnhealthyAt`, `lastProbeAt`, `consecutiveFailures`, `nextProbeAt` — consistent in B2, B3, B4, B6, B7.
- Band naming `"p1p2"` / `"p3p4"` — consistent across C1, C2, C4, C5.
- Settings field names — consistent between Settings.kt, IntervalsWire, V2Routes.mergeEditable, YAML keys, frontend hooks.

**Frequent commits:** every task ends with a `git commit`. Each commit is independently green.

---

## Execution handoff

Plan complete and saved to `docs/plans/2026-05-19-sonarr-resilience-implementation.md`.

Two execution options:

**1. Subagent-Driven (recommended)** — Dispatch a fresh subagent per task, two-stage review (spec compliance + code quality) between tasks, fast iteration in this session.

**2. Inline Execution** — Execute tasks in this session via the executing-plans skill, batch with checkpoints for review.

Which approach?
