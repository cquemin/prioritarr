# P5 Backfill Season Ratchet Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a per-series season ratchet to the P5 (full-backfill) priority band — both on the search side (Sonarr SeasonSearch picked over SeriesSearch when bandwidth is contended) and on the download side (qBit / SAB enforce earlier seasons before later ones for the same series). Both halves gated by the existing `BandwidthPolicy.utilisationExceedsThreshold` signal.

**Architecture:** The download-side calculation is unified into a single pure function `computeEnforcement(downloads, ctx) → Map<clientId, EnforcementDecision>` that operates on a client-agnostic `ManagedDownloadView`. Each `DownloadClient` implementation translates decisions to its native API. The search side is a pure planner `buildP5RatchetPlan(inputs) → P5RatchetPlan` plus a thin runner. New SQLite table `p5_sweep_attempts` tracks per-(series, season) cooldowns + escalation counters; new nullable `season_number` column on `managed_downloads` carries the field needed for the per-series sub-band rule.

**Tech Stack:** Kotlin 1.9+, Ktor client, SQLDelight (SQLite), kotlinx.serialization, kotlinx.coroutines, kotlin.test (JUnit). Frontend (deferred to Task 19) is React + TypeScript.

**Spec:** `docs/specs/2026-05-05-p5-backfill-season-ratchet-design.md`

**Build/test commands** (run from `D:/docker/prioritarr/prioritarr/`):
- All tests: `./gradlew :backend:test`
- Single test class: `./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.enforcement.ComputeEnforcementTest"`
- Single test method: `./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.enforcement.ComputeEnforcementTest.layer1_p1_active_defers_p4_and_p5"`
- Compile only: `./gradlew :backend:compileKotlin`

**Conventions you'll see in this codebase:**
- Package root: `org.yoshiz.app.prioritarr.backend`
- Test files mirror source paths under `src/test/kotlin/...` and use `kotlin.test.*` assertions.
- `Database.q` is the raw SQLDelight query handle; typed wrappers sit on `Database` itself.
- Serializable data classes use `@kotlinx.serialization.Serializable`.
- Audit kinds are string constants (e.g. `"p5_ratchet_search"`); see the existing `AuditAction` object for the pattern.
- New files keep their imports fully-qualified for cross-package types when ambiguous (existing files mix both styles; follow the immediate neighbours).

---

## File Structure

**New files**
- `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/enforcement/EnforcementModel.kt` — unified `ManagedDownloadView`, `EnforcementDecision`, `TargetState`, `ManagedState`, `RawDownload`.
- `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/enforcement/ComputeEnforcement.kt` — pure `computeEnforcement(downloads, ctx)`.
- `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/sweep/P5Ratchet.kt` — pure `buildP5RatchetPlan` + `runP5SeasonRatchet` runner.
- `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/enforcement/ComputeEnforcementTest.kt`
- `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/sweep/P5RatchetTest.kt`

**Modified files**
- `prioritarr/backend/src/main/sqldelight/.../database/Schema.sq`
- `prioritarr/backend/src/main/kotlin/.../database/Database.kt`
- `prioritarr/backend/src/main/kotlin/.../config/Settings.kt`
- `prioritarr/backend/src/main/kotlin/.../clients/Sonarr.kt`
- `prioritarr/backend/src/main/kotlin/.../clients/DownloadClient.kt`
- `prioritarr/backend/src/main/kotlin/.../clients/QBittorrent.kt`
- `prioritarr/backend/src/main/kotlin/.../clients/Sabnzbd.kt`
- `prioritarr/backend/src/main/kotlin/.../enforcement/Enforcement.kt`
- `prioritarr/backend/src/main/kotlin/.../reconcile/Reconcile.kt`
- `prioritarr/backend/src/main/kotlin/.../sweep/Sweep.kt`
- `prioritarr/backend/src/main/kotlin/.../Main.kt`
- `prioritarr/backend/src/main/kotlin/.../api/v2/V2Routes.kt`
- `prioritarr/backend/src/test/kotlin/.../enforcement/EnforcementTest.kt` (parity migration)

**Frontend** — Settings sub-section + Series drawer tile. Outlined in Task 19; React work happens at the same level of granularity but doesn't merit step-by-step Kotlin TDD.

---

## Phase 1 — Schema & DB layer

### Task 1: Add `p5_sweep_attempts` table and `season_number` column

**Files:**
- Modify: `prioritarr/backend/src/main/sqldelight/org/yoshiz/app/prioritarr/backend/database/Schema.sq` (insert table near other tables; add column to `managed_downloads`)

- [ ] **Step 1: Add the new table**

Insert *after* the `managed_downloads` table definition (around line 38), before `webhook_dedupe`:

```sql
-- Per-(series, season) attempt log for the P5 backfill ratchet.
-- Tracks when we last asked Sonarr to look for this season's missing
-- episodes, what the missing-count was at the time, and how many
-- consecutive empty attempts we've had. The picker uses this to:
--   1. Cool down a (series, season) for `search_cooldown_hours`
--      between attempts (default 24h).
--   2. Escalate the search strategy from SeasonSearch to SeasonSearch
--      retry to per-EpisodeSearch as `consecutive_empty_attempts` rises.
--   3. Skip a (series, season) for `long_cooldown_hours` once the
--      counter reaches `escalation_threshold` (default 5).
CREATE TABLE IF NOT EXISTS p5_sweep_attempts (
    series_id INTEGER NOT NULL,
    season_number INTEGER NOT NULL,
    last_attempted_at INTEGER NOT NULL,    -- epoch seconds
    last_missing_count INTEGER,            -- null on the very first attempt
    consecutive_empty_attempts INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (series_id, season_number)
);
```

- [ ] **Step 2: Add `season_number` column to `managed_downloads`**

SQLDelight's schema migrations work via `ALTER TABLE` in a separate migration file, *not* by editing the original `CREATE TABLE`. Check whether the project uses migration files:

```
ls "D:/docker/prioritarr/prioritarr/backend/src/main/sqldelight/org/yoshiz/app/prioritarr/backend/database"
```

If only `Schema.sq` exists (no `migrations/` dir), this codebase relies on `CREATE TABLE IF NOT EXISTS` — fresh DBs get the new column, existing DBs need an explicit `ALTER` at startup. Edit the `CREATE TABLE managed_downloads` block to add the new column at the end:

```sql
CREATE TABLE IF NOT EXISTS managed_downloads (
    client TEXT NOT NULL,
    client_id TEXT NOT NULL,
    series_id INTEGER NOT NULL,
    episode_ids TEXT,
    initial_priority INTEGER NOT NULL,
    current_priority INTEGER NOT NULL,
    paused_by_us INTEGER NOT NULL DEFAULT 0,
    first_seen_at TEXT NOT NULL,
    last_reconciled_at TEXT NOT NULL,
    season_number INTEGER,                  -- nullable: populated on cross-ref with Sonarr queue
    PRIMARY KEY (client, client_id)
);
```

Then immediately *after* the CREATE TABLE block, add an idempotent ALTER for already-deployed databases:

```sql
-- Idempotent column addition for databases created before season_number existed.
-- ALTER TABLE ADD COLUMN errors with "duplicate column" if the column already
-- exists; SQLDelight surfaces that as a runtime error, so we wrap it in the
-- generated query layer (see Database.kt::ensureSeasonNumberColumn).
```

The actual ALTER is run from Kotlin (Step 5 below) so we can swallow the duplicate-column error.

- [ ] **Step 3: Add the SQLDelight named queries for `p5_sweep_attempts`**

Append to the bottom of `Schema.sq`, in a new section:

```sql
-- ------------------------------------------------------------------
-- p5_sweep_attempts
-- ------------------------------------------------------------------

upsertP5Attempt:
INSERT INTO p5_sweep_attempts
    (series_id, season_number, last_attempted_at,
     last_missing_count, consecutive_empty_attempts)
VALUES (?, ?, ?, ?, ?)
ON CONFLICT(series_id, season_number) DO UPDATE SET
    last_attempted_at         = excluded.last_attempted_at,
    last_missing_count        = excluded.last_missing_count,
    consecutive_empty_attempts = excluded.consecutive_empty_attempts;

selectP5Attempt:
SELECT * FROM p5_sweep_attempts
WHERE series_id = ? AND season_number = ?;

listP5Attempts:
SELECT * FROM p5_sweep_attempts;

listP5AttemptsForSeries:
SELECT * FROM p5_sweep_attempts WHERE series_id = ?;

deleteP5AttemptsForSeries:
DELETE FROM p5_sweep_attempts WHERE series_id = ?;
```

- [ ] **Step 4: Add a query to read/write `season_number` on `managed_downloads`**

Append next to the existing `setManagedPaused` query (around line 250):

```sql
setManagedSeasonNumber:
UPDATE managed_downloads SET season_number = ? WHERE client = ? AND client_id = ?;
```

The existing `selectManagedDownload` / `listManagedDownloads` already use `SELECT *`, so they'll pick up the new column automatically once SQLDelight regenerates.

- [ ] **Step 5: Verify SQLDelight regenerates without error**

```
cd D:/docker/prioritarr/prioritarr ; ./gradlew :backend:generateMainDatabaseInterface
```
Expected: success. If it fails on the `season_number` reference, double-check the `CREATE TABLE` was edited correctly.

- [ ] **Step 6: Commit**

```
git add prioritarr/backend/src/main/sqldelight/org/yoshiz/app/prioritarr/backend/database/Schema.sq
git commit -m "feat(p5-ratchet): schema for p5_sweep_attempts + managed_downloads.season_number"
```

---

### Task 2: Typed wrappers in `Database.kt`

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/database/Database.kt`

- [ ] **Step 1: Add a P5 attempt data class for ergonomic returns**

In `Database.kt`, near the top of the file (just after the class declaration's open brace, alongside the section dividers), add a section:

```kotlin
    // ------------------------------------------------------------------
    // p5_sweep_attempts
    // ------------------------------------------------------------------

    /**
     * In-memory shape returned by [listP5Attempts] / [getP5Attempt] —
     * a thin alias over the generated row class so callers don't have
     * to depend on SQLDelight types. Times are epoch seconds (matching
     * the column type).
     */
    data class P5SweepAttempt(
        val seriesId: Long,
        val seasonNumber: Int,
        val lastAttemptedAt: Long,
        val lastMissingCount: Int?,
        val consecutiveEmptyAttempts: Int,
    )

    fun upsertP5Attempt(
        seriesId: Long,
        seasonNumber: Int,
        lastAttemptedAt: Long,
        lastMissingCount: Int?,
        consecutiveEmptyAttempts: Int,
    ) {
        q.upsertP5Attempt(
            series_id = seriesId,
            season_number = seasonNumber.toLong(),
            last_attempted_at = lastAttemptedAt,
            last_missing_count = lastMissingCount?.toLong(),
            consecutive_empty_attempts = consecutiveEmptyAttempts.toLong(),
        )
    }

    fun getP5Attempt(seriesId: Long, seasonNumber: Int): P5SweepAttempt? =
        q.selectP5Attempt(seriesId, seasonNumber.toLong())
            .executeAsOneOrNull()
            ?.let { row ->
                P5SweepAttempt(
                    seriesId = row.series_id,
                    seasonNumber = row.season_number.toInt(),
                    lastAttemptedAt = row.last_attempted_at,
                    lastMissingCount = row.last_missing_count?.toInt(),
                    consecutiveEmptyAttempts = row.consecutive_empty_attempts.toInt(),
                )
            }

    fun listP5Attempts(): List<P5SweepAttempt> =
        q.listP5Attempts().executeAsList().map { row ->
            P5SweepAttempt(
                seriesId = row.series_id,
                seasonNumber = row.season_number.toInt(),
                lastAttemptedAt = row.last_attempted_at,
                lastMissingCount = row.last_missing_count?.toInt(),
                consecutiveEmptyAttempts = row.consecutive_empty_attempts.toInt(),
            )
        }

    fun listP5AttemptsForSeries(seriesId: Long): List<P5SweepAttempt> =
        q.listP5AttemptsForSeries(seriesId).executeAsList().map { row ->
            P5SweepAttempt(
                seriesId = row.series_id,
                seasonNumber = row.season_number.toInt(),
                lastAttemptedAt = row.last_attempted_at,
                lastMissingCount = row.last_missing_count?.toInt(),
                consecutiveEmptyAttempts = row.consecutive_empty_attempts.toInt(),
            )
        }

    fun deleteP5AttemptsForSeries(seriesId: Long) {
        q.deleteP5AttemptsForSeries(seriesId)
    }
```

Find the right insertion point: after the `managed_downloads` section (around the `deleteManagedDownload` function at line ~104). Place the new section between `managed_downloads` and `webhook_dedupe` so the file's section ordering matches `Schema.sq`.

- [ ] **Step 2: Add the season-number setter on `managed_downloads`**

In the existing `managed_downloads` section, alongside `deleteManagedDownload`:

```kotlin
    fun setManagedSeasonNumber(client: String, clientId: String, seasonNumber: Int?) {
        q.setManagedSeasonNumber(seasonNumber?.toLong(), client, clientId)
    }
```

- [ ] **Step 3: Add the legacy-DB column-add hook**

Find the `init` block (around line 26, before `db = Db(driver)`) and append:

```kotlin
    init {
        Db.Schema.create(driver)
        db = Db(driver)
        ensureLegacyColumns()
    }

    /**
     * For databases created before season_number existed on
     * managed_downloads. SQLDelight's CREATE TABLE IF NOT EXISTS won't
     * add new columns to existing tables; we issue an idempotent ALTER
     * here and swallow the "duplicate column" error so this is safe to
     * run on every startup.
     */
    private fun ensureLegacyColumns() {
        try {
            driver.execute(null, "ALTER TABLE managed_downloads ADD COLUMN season_number INTEGER", 0)
        } catch (e: Exception) {
            // SQLite emits "duplicate column name" for the second run.
            // Any other error is surfaced via SQLDelight's normal path on first read.
            if (e.message?.contains("duplicate column", ignoreCase = true) != true) throw e
        }
    }
```

Note: `init` already calls `Db.Schema.create(driver)`. Move the assignment of `db` to *before* `ensureLegacyColumns` so the ALTER runs after the CREATE.

- [ ] **Step 4: Build to verify wrappers compile**

```
cd D:/docker/prioritarr/prioritarr ; ./gradlew :backend:compileKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/database/Database.kt
git commit -m "feat(p5-ratchet): typed wrappers for p5_sweep_attempts + season_number setter"
```

---

### Task 3: Round-trip test for the new DB wrappers

**Files:**
- Create: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/database/P5AttemptsRoundTripTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package org.yoshiz.app.prioritarr.backend.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import java.nio.file.Files

class P5AttemptsRoundTripTest {

    private fun freshDb(): Database {
        val tmp = Files.createTempFile("prioritarr-p5-test", ".db")
        tmp.toFile().deleteOnExit()
        return Database(tmp.toAbsolutePath().toString())
    }

    @Test fun upsert_then_select_round_trip() {
        val db = freshDb()
        db.upsertP5Attempt(
            seriesId = 42L,
            seasonNumber = 1,
            lastAttemptedAt = 1_000_000L,
            lastMissingCount = 7,
            consecutiveEmptyAttempts = 0,
        )

        val row = db.getP5Attempt(42L, 1)
        assertNotNull(row)
        assertEquals(42L, row.seriesId)
        assertEquals(1, row.seasonNumber)
        assertEquals(1_000_000L, row.lastAttemptedAt)
        assertEquals(7, row.lastMissingCount)
        assertEquals(0, row.consecutiveEmptyAttempts)
    }

    @Test fun upsert_overwrites_on_conflict() {
        val db = freshDb()
        db.upsertP5Attempt(1L, 1, 1_000L, 5, 0)
        db.upsertP5Attempt(1L, 1, 2_000L, 3, 1)
        val row = db.getP5Attempt(1L, 1)!!
        assertEquals(2_000L, row.lastAttemptedAt)
        assertEquals(3, row.lastMissingCount)
        assertEquals(1, row.consecutiveEmptyAttempts)
    }

    @Test fun null_last_missing_count_round_trips() {
        val db = freshDb()
        db.upsertP5Attempt(1L, 1, 1_000L, null, 0)
        val row = db.getP5Attempt(1L, 1)!!
        assertNull(row.lastMissingCount)
    }

    @Test fun list_for_series_returns_only_that_series() {
        val db = freshDb()
        db.upsertP5Attempt(1L, 1, 1_000L, 5, 0)
        db.upsertP5Attempt(1L, 2, 1_100L, 3, 0)
        db.upsertP5Attempt(2L, 1, 1_200L, 8, 0)

        val seriesOne = db.listP5AttemptsForSeries(1L)
        assertEquals(2, seriesOne.size)
        assertEquals(setOf(1, 2), seriesOne.map { it.seasonNumber }.toSet())
    }

    @Test fun delete_for_series_removes_only_that_series() {
        val db = freshDb()
        db.upsertP5Attempt(1L, 1, 1_000L, 5, 0)
        db.upsertP5Attempt(2L, 1, 1_200L, 8, 0)
        db.deleteP5AttemptsForSeries(1L)
        assertEquals(0, db.listP5AttemptsForSeries(1L).size)
        assertEquals(1, db.listP5AttemptsForSeries(2L).size)
    }

    @Test fun managed_downloads_season_number_set_get() {
        val db = freshDb()
        db.upsertManagedDownload(
            client = "qbit",
            clientId = "abc",
            seriesId = 99L,
            episodeIds = listOf(1L, 2L),
            initialPriority = 5L,
            currentPriority = 5L,
            pausedByUs = false,
            firstSeenAt = "2026-05-05T00:00:00+00:00",
            lastReconciledAt = "2026-05-05T00:00:00+00:00",
        )
        db.setManagedSeasonNumber("qbit", "abc", 3)

        val row = db.getManagedDownload("qbit", "abc")
        assertNotNull(row)
        assertEquals(3L, row.season_number)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```
cd D:/docker/prioritarr/prioritarr ; ./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.database.P5AttemptsRoundTripTest"
```
Expected: PASS (Tasks 1+2 already implemented the wrappers). If it fails, the test exposes a gap — fix and re-run.

- [ ] **Step 3: Commit**

```
git add prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/database/P5AttemptsRoundTripTest.kt
git commit -m "test(p5-ratchet): round-trip coverage for p5_sweep_attempts wrappers"
```

---

## Phase 2 — Configuration

### Task 4: Add `P5RatchetConfig` data class + Settings field + YAML parsing

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/config/Settings.kt`

- [ ] **Step 1: Add the data class**

Add after `BandwidthSettings` (around line 114), before `AuditConfig`:

```kotlin
/**
 * P5 backfill ratchet — controls per-(series, season) cooldowns and
 * the search-strategy escalation ladder. Spec:
 * docs/specs/2026-05-05-p5-backfill-season-ratchet-design.md
 *
 * Setting [enabled] = false short-circuits both Pass B of the backfill
 * sweep AND Layer 2 of computeEnforcement; the system reverts to its
 * pre-feature behaviour on the next tick. This is the rollback path.
 */
@kotlinx.serialization.Serializable
data class P5RatchetConfig(
    val enabled: Boolean = false,
    val searchCooldownHours: Int = 24,
    val longCooldownHours: Int = 168,
    val escalationThreshold: Int = 5,
    val includeSpecials: Boolean = false,
    /**
     * Optional override for the bandwidth utilisation threshold below
     * which the ratchet is skipped. When null, falls back to
     * [BandwidthSettings.utilisationThresholdPct] so single-knob users
     * don't have to think about it.
     */
    val bandwidthThresholdPct: Double? = null,
)
```

- [ ] **Step 2: Add the field to `Settings`**

Find the `Settings` data class (around line 178). Add `p5Ratchet` next to `traktUnmonitor`:

```kotlin
    val priorityThresholds: PriorityThresholds = PriorityThresholds(),
    val intervals: Intervals = Intervals(),
    val cache: CacheConfig = CacheConfig(),
    val audit: AuditConfig = AuditConfig(),
    val bandwidth: BandwidthSettings = BandwidthSettings(),
    val archive: ArchiveSettings = ArchiveSettings(),
    val traktUnmonitor: TraktUnmonitorSettings = TraktUnmonitorSettings(),
    val p5Ratchet: P5RatchetConfig = P5RatchetConfig(),
```

- [ ] **Step 3: Add YAML overlay parsing**

Find the YAML overlay block (search for `(root["trakt_unmonitor"] as? Map<*, *>)?.let`). Add a parallel block immediately after it:

```kotlin
        (root["p5_ratchet"] as? Map<*, *>)?.let { o ->
            p5Ratchet = p5Ratchet.copy(
                enabled = (o["enabled"] as? Boolean) ?: p5Ratchet.enabled,
                searchCooldownHours = o.num("search_cooldown_hours") { it.toInt() } ?: p5Ratchet.searchCooldownHours,
                longCooldownHours = o.num("long_cooldown_hours") { it.toInt() } ?: p5Ratchet.longCooldownHours,
                escalationThreshold = o.num("escalation_threshold") { it.toInt() } ?: p5Ratchet.escalationThreshold,
                includeSpecials = (o["include_specials"] as? Boolean) ?: p5Ratchet.includeSpecials,
                bandwidthThresholdPct = o.num("bandwidth_threshold_pct") { it.toDouble() } ?: p5Ratchet.bandwidthThresholdPct,
            )
        }
```

You'll also need to declare `p5Ratchet` as a `var` in the function's scope. Search for `var traktUnmonitor` in the same file — `p5Ratchet` follows the same pattern. Then ensure the final `Settings.copy(...)` (or constructor call) at the bottom of the function passes the overlaid `p5Ratchet`.

- [ ] **Step 4: Build to verify it compiles**

```
cd D:/docker/prioritarr/prioritarr ; ./gradlew :backend:compileKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/config/Settings.kt
git commit -m "feat(p5-ratchet): P5RatchetConfig data class + YAML overlay"
```

---

### Task 5: Live-editable override for `P5RatchetConfig` (UI Settings path)

**Files:**
- Modify: `prioritarr/backend/src/main/sqldelight/.../database/Schema.sq`
- Modify: `prioritarr/backend/src/main/kotlin/.../database/Database.kt`

- [ ] **Step 1: Add the override table**

In `Schema.sq`, near the existing `app_bandwidth_overrides` table (around line 76):

```sql
-- Single-row JSON blob of P5RatchetConfig overrides. Mirrors the
-- bandwidth-overrides pattern: edits via /api/v2/settings/p5-ratchet
-- take effect on the next reconcile / sweep tick (no restart).
CREATE TABLE IF NOT EXISTS app_p5_ratchet_overrides (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    payload TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
```

And the named queries (near `app_bandwidth_overrides` queries, search for `selectBandwidthOverride`):

```sql
selectP5RatchetOverride:
SELECT payload FROM app_p5_ratchet_overrides WHERE id = 1;

upsertP5RatchetOverride:
INSERT INTO app_p5_ratchet_overrides (id, payload, updated_at)
VALUES (1, ?, ?)
ON CONFLICT(id) DO UPDATE SET payload = excluded.payload, updated_at = excluded.updated_at;

deleteP5RatchetOverride:
DELETE FROM app_p5_ratchet_overrides WHERE id = 1;
```

- [ ] **Step 2: Wrappers in `Database.kt`**

Add to `Database.kt`, mirroring the existing `getBandwidthOverride` / `setBandwidthOverride` / `clearBandwidthOverride` block:

```kotlin
    // ------------------------------------------------------------------
    // app_p5_ratchet_overrides
    // ------------------------------------------------------------------

    fun getP5RatchetOverride(): String? =
        q.selectP5RatchetOverride().executeAsOneOrNull()

    fun setP5RatchetOverride(payload: String) {
        q.upsertP5RatchetOverride(payload, nowIsoOffset())
    }

    fun clearP5RatchetOverride() {
        q.deleteP5RatchetOverride()
    }
```

- [ ] **Step 3: Verify SQLDelight regenerates and Kotlin compiles**

```
cd D:/docker/prioritarr/prioritarr ; ./gradlew :backend:compileKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```
git add prioritarr/backend/src/main/sqldelight/org/yoshiz/app/prioritarr/backend/database/Schema.sq \
        prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/database/Database.kt
git commit -m "feat(p5-ratchet): app_p5_ratchet_overrides table + Database wrappers"
```

---

## Phase 3 — Sonarr SeasonSearch

### Task 6: Add `triggerSeasonSearch` to `SonarrClient`

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/clients/Sonarr.kt`
- Test: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/clients/SonarrSeasonSearchTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/clients/SonarrSeasonSearchTest.kt`:

```kotlin
package org.yoshiz.app.prioritarr.backend.clients

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class SonarrSeasonSearchTest {

    @Test fun triggerSeasonSearch_posts_command_with_seriesId_and_seasonNumber() = runBlocking {
        var capturedBody: String? = null
        var capturedPath: String? = null
        val engine = MockEngine { req ->
            capturedPath = req.url.encodedPath
            capturedBody = req.body.toByteArray().toString(Charsets.UTF_8)
            respond(
                content = ByteReadChannel("""{"id": 123, "status": "queued"}"""),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val sonarr = SonarrClient("http://sonarr:8989", "key", client)

        val resp = sonarr.triggerSeasonSearch(seriesId = 7L, seasonNumber = 3)

        assertEquals(123L, resp["id"]!!.jsonPrimitive.content.toLong())
        assertEquals("/api/v3/command", capturedPath)
        val parsed = Json.parseToJsonElement(capturedBody!!).jsonObject
        assertEquals("SeasonSearch", parsed["name"]!!.jsonPrimitive.content)
        assertEquals(7L, parsed["seriesId"]!!.jsonPrimitive.content.toLong())
        assertEquals(3, parsed["seasonNumber"]!!.jsonPrimitive.content.toInt())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```
cd D:/docker/prioritarr/prioritarr ; ./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.clients.SonarrSeasonSearchTest"
```
Expected: FAIL with "Unresolved reference: triggerSeasonSearch".

- [ ] **Step 3: Implement the method**

In `Sonarr.kt`, add after `triggerSeriesSearch` (around line 61):

```kotlin
    suspend fun triggerSeasonSearch(seriesId: Long, seasonNumber: Int): JsonObject =
        post("/api/v3/command", buildJsonObject {
            put("name", "SeasonSearch")
            put("seriesId", seriesId)
            put("seasonNumber", seasonNumber)
        }) as JsonObject
```

- [ ] **Step 4: Run the test to verify it passes**

```
cd D:/docker/prioritarr/prioritarr ; ./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.clients.SonarrSeasonSearchTest"
```
Expected: PASS.

- [ ] **Step 5: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/clients/Sonarr.kt \
        prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/clients/SonarrSeasonSearchTest.kt
git commit -m "feat(sonarr-client): triggerSeasonSearch(seriesId, seasonNumber)"
```

---

## Phase 4 — Unified enforcement model

### Task 7: Define the unified types

**Files:**
- Create: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/enforcement/EnforcementModel.kt`

- [ ] **Step 1: Create the file**

```kotlin
package org.yoshiz.app.prioritarr.backend.enforcement

/**
 * Client-agnostic view of one tracked download. Built from a
 * DownloadClient.snapshotDownloads() raw row joined with the local
 * managed_downloads metadata (seriesId, seasonNumber, episodeNumber,
 * pausedByUs flag).
 *
 * Lives at the abstraction level so that [computeEnforcement] never
 * knows whether it's reasoning about a qBit hash or a SAB nzo_id.
 */
data class ManagedDownloadView(
    val client: String,            // "qbit" | "sab" | future
    val clientId: String,          // hash | nzo_id
    val priority: Int,             // 1..5
    val seriesId: Long?,
    val seasonNumber: Int?,        // null if cross-ref hasn't landed yet
    val episodeNumber: Int?,
    val state: ManagedState,
    val etaSeconds: Long?,
)

/**
 * Lifecycle state of a managed download as the enforcement layer cares
 * about it. `RUNNING` is the catch-all for "actively downloading or
 * uploading"; the rest are exclusion gates.
 */
enum class ManagedState {
    RUNNING,
    PAUSED_BY_US,        // we paused it; safe to resume when band rules allow
    PAUSED_BY_USER,      // user paused; never touch
    ERRORED,             // qBit error / SAB failed — leave alone
    NEAR_DONE,           // within etaBufferMinutes of finishing
}

/**
 * What [computeEnforcement] decided for one download. The client's
 * [DownloadClient.applyEnforcement] translates this to native verbs.
 *
 * `orderHint` ranks items globally (lower wins). For clients that only
 * honour pause/resume (qBit), the hint orders the ACTIVE set so the
 * implementation can call setTopPriority in reverse-hint order. For
 * clients that order within a bucket (SAB), the hint drives queue/switch.
 */
data class EnforcementDecision(
    val targetState: TargetState,
    val orderHint: Int,
)

enum class TargetState { ACTIVE, DEFERRED }

/**
 * Inputs to [computeEnforcement] that aren't carried per-download:
 * the bandwidth signal, the P5 ratchet flag, and predicates for the
 * existing peer-limit + near-done escape hatches. Default predicates
 * preserve today's behaviour.
 */
data class ComputeEnforcementContext(
    /** True when the bandwidth-policy signal says "pipe is contended". */
    val bandwidthSaturated: Boolean = false,
    /** True when [P5RatchetConfig.enabled] is true AND bandwidthSaturated. */
    val p5SeasonRatchetActive: Boolean = false,
    /** P1 average speed signal — if [bandwidthSaturated] but P1 is peer-limited, don't pause others. */
    val p1IsPeerLimited: Boolean = false,
    /** Per-candidate near-done predicate. Default: no item is near-done. */
    val isNearDone: (ManagedDownloadView) -> Boolean = { false },
)

/**
 * Stable raw shape returned by [DownloadClient.snapshotDownloads].
 * The client supplies what it knows natively; the reconciler enriches
 * with seriesId/seasonNumber/episodeNumber from managed_downloads +
 * Sonarr's queue, then projects to [ManagedDownloadView].
 */
data class RawDownload(
    val client: String,
    val clientId: String,
    val rawState: String,
    val pausedByUs: Boolean,
    val etaSeconds: Long?,
)
```

- [ ] **Step 2: Build to verify**

```
cd D:/docker/prioritarr/prioritarr ; ./gradlew :backend:compileKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/enforcement/EnforcementModel.kt
git commit -m "feat(enforcement): unified ManagedDownloadView + EnforcementDecision types"
```

---

### Task 8: `computeEnforcement` Layer 1 (cross-band parity with `computeQBitPauseActions`)

**Files:**
- Create: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/enforcement/ComputeEnforcement.kt`
- Test: `prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/enforcement/ComputeEnforcementTest.kt` (new)

- [ ] **Step 1: Write failing tests for Layer 1 — mirror existing EnforcementTest cases on the new shape**

Create `ComputeEnforcementTest.kt`:

```kotlin
package org.yoshiz.app.prioritarr.backend.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals

class ComputeEnforcementTest {

    private fun dl(
        clientId: String,
        priority: Int,
        client: String = "qbit",
        seriesId: Long? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        state: ManagedState = ManagedState.RUNNING,
        etaSeconds: Long? = null,
    ) = ManagedDownloadView(
        client = client, clientId = clientId, priority = priority,
        seriesId = seriesId, seasonNumber = seasonNumber, episodeNumber = episodeNumber,
        state = state, etaSeconds = etaSeconds,
    )

    // ---------- Layer 1 — cross-band pause rules ----------

    @Test fun layer1_p1_active_defers_p4_and_p5() {
        val decisions = computeEnforcement(
            listOf(dl("p1", 1), dl("p4", 4), dl("p5", 5)),
            ComputeEnforcementContext(),
        )
        assertEquals(TargetState.ACTIVE, decisions["p1"]!!.targetState)
        assertEquals(TargetState.DEFERRED, decisions["p4"]!!.targetState)
        assertEquals(TargetState.DEFERRED, decisions["p5"]!!.targetState)
    }

    @Test fun layer1_p2_active_no_p1_defers_only_p5() {
        val decisions = computeEnforcement(
            listOf(dl("p2", 2), dl("p4", 4), dl("p5", 5)),
            ComputeEnforcementContext(),
        )
        assertEquals(TargetState.ACTIVE, decisions["p2"]!!.targetState)
        assertEquals(TargetState.ACTIVE, decisions["p4"]!!.targetState)
        assertEquals(TargetState.DEFERRED, decisions["p5"]!!.targetState)
    }

    @Test fun layer1_only_p3_through_p5_no_defers() {
        val decisions = computeEnforcement(
            listOf(dl("p3", 3), dl("p4", 4), dl("p5", 5)),
            ComputeEnforcementContext(),
        )
        decisions.values.forEach { assertEquals(TargetState.ACTIVE, it.targetState) }
    }

    @Test fun layer1_paused_by_user_does_not_count_as_active_band_member() {
        // P1 only "active" via PAUSED_BY_USER → P1 doesn't count → no defer
        val decisions = computeEnforcement(
            listOf(
                dl("p1", 1, state = ManagedState.PAUSED_BY_USER),
                dl("p5", 5),
            ),
            ComputeEnforcementContext(),
        )
        assertEquals(TargetState.ACTIVE, decisions["p5"]!!.targetState)
    }

    @Test fun layer1_p1_peer_limited_skips_layer1_defer() {
        // P1 is peer-limited → freeing bandwidth on P5 won't help → don't defer P5
        val decisions = computeEnforcement(
            listOf(dl("p1", 1), dl("p5", 5)),
            ComputeEnforcementContext(p1IsPeerLimited = true),
        )
        assertEquals(TargetState.ACTIVE, decisions["p5"]!!.targetState)
    }

    @Test fun layer1_near_done_p5_skips_defer() {
        val nearDone = dl("p5", 5)
        val decisions = computeEnforcement(
            listOf(dl("p1", 1), nearDone),
            ComputeEnforcementContext(isNearDone = { it.clientId == "p5" }),
        )
        assertEquals(TargetState.ACTIVE, decisions["p5"]!!.targetState)
    }
}
```

- [ ] **Step 2: Run tests, expect FAIL**

```
cd D:/docker/prioritarr/prioritarr ; ./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.enforcement.ComputeEnforcementTest"
```
Expected: FAIL with "Unresolved reference: computeEnforcement".

- [ ] **Step 3: Create `ComputeEnforcement.kt` with Layer 1 only (no P5 sub-band yet)**

```kotlin
package org.yoshiz.app.prioritarr.backend.enforcement

/**
 * Pure decision function over the unified [ManagedDownloadView] list.
 * Two layers:
 *   1. Cross-band pause rules (existing pause-band semantics).
 *   2. P5 sub-band rule (per-series earlier-season-first, only when
 *      [ComputeEnforcementContext.p5SeasonRatchetActive] is true).
 *
 * Returns a per-clientId decision; downstream client adapters
 * translate to native API calls (qBit pause/resume + setTopPriority,
 * SAB priority bucket + queue/switch).
 */
fun computeEnforcement(
    downloads: List<ManagedDownloadView>,
    ctx: ComputeEnforcementContext,
): Map<String, EnforcementDecision> {
    val result = LinkedHashMap<String, EnforcementDecision>(downloads.size)

    val crossBandActives = downloads
        .filter { it.state == ManagedState.RUNNING }
        .map { it.priority }
        .toSet()
    val hasP1 = 1 in crossBandActives
    val hasP2 = 2 in crossBandActives
    val crossBandDeferLevels: Set<Int> = when {
        hasP1 -> setOf(4, 5)
        hasP2 -> setOf(5)
        else -> emptySet()
    }

    for (d in downloads) {
        val deferByCrossBand = d.priority in crossBandDeferLevels &&
            !ctx.p1IsPeerLimited &&
            !ctx.isNearDone(d) &&
            d.state == ManagedState.RUNNING

        val target = if (deferByCrossBand) TargetState.DEFERRED else TargetState.ACTIVE
        result[d.clientId] = EnforcementDecision(
            targetState = target,
            orderHint = orderHintOf(d),
        )
    }
    return result
}

internal fun orderHintOf(d: ManagedDownloadView): Int =
    d.priority * 1_000_000 +
        (d.seasonNumber ?: 99) * 1_000 +
        (d.episodeNumber ?: 0)
```

- [ ] **Step 4: Run tests to verify Layer 1 passes**

```
cd D:/docker/prioritarr/prioritarr ; ./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.enforcement.ComputeEnforcementTest"
```
Expected: PASS for all six Layer 1 tests.

- [ ] **Step 5: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/enforcement/ComputeEnforcement.kt \
        prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/enforcement/ComputeEnforcementTest.kt
git commit -m "feat(enforcement): computeEnforcement Layer 1 (cross-band parity)"
```

---

### Task 9: `computeEnforcement` Layer 2 (P5 sub-band ratchet)

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/.../enforcement/ComputeEnforcement.kt`
- Modify: `prioritarr/backend/src/test/kotlin/.../enforcement/ComputeEnforcementTest.kt`

- [ ] **Step 1: Write failing tests for Layer 2**

Append to `ComputeEnforcementTest.kt`, inside the same class:

```kotlin
    // ---------- Layer 2 — P5 sub-band ----------

    @Test fun layer2_off_when_ratchet_inactive() {
        // Same series, two seasons, P5 — ratchet inactive → both ACTIVE
        val decisions = computeEnforcement(
            listOf(
                dl("s1", 5, seriesId = 1L, seasonNumber = 1),
                dl("s2", 5, seriesId = 1L, seasonNumber = 2),
            ),
            ComputeEnforcementContext(p5SeasonRatchetActive = false),
        )
        assertEquals(TargetState.ACTIVE, decisions["s1"]!!.targetState)
        assertEquals(TargetState.ACTIVE, decisions["s2"]!!.targetState)
    }

    @Test fun layer2_per_series_lowest_season_active_higher_deferred() {
        val decisions = computeEnforcement(
            listOf(
                dl("s1e1", 5, seriesId = 1L, seasonNumber = 1, episodeNumber = 1),
                dl("s1e2", 5, seriesId = 1L, seasonNumber = 1, episodeNumber = 2),
                dl("s2e1", 5, seriesId = 1L, seasonNumber = 2, episodeNumber = 1),
                dl("s3e1", 5, seriesId = 1L, seasonNumber = 3, episodeNumber = 1),
            ),
            ComputeEnforcementContext(p5SeasonRatchetActive = true),
        )
        assertEquals(TargetState.ACTIVE, decisions["s1e1"]!!.targetState)
        assertEquals(TargetState.ACTIVE, decisions["s1e2"]!!.targetState)
        assertEquals(TargetState.DEFERRED, decisions["s2e1"]!!.targetState)
        assertEquals(TargetState.DEFERRED, decisions["s3e1"]!!.targetState)
    }

    @Test fun layer2_two_series_independent_min_seasons() {
        val decisions = computeEnforcement(
            listOf(
                dl("a-s2", 5, seriesId = 1L, seasonNumber = 2),
                dl("a-s3", 5, seriesId = 1L, seasonNumber = 3),
                dl("b-s5", 5, seriesId = 2L, seasonNumber = 5),
                dl("b-s6", 5, seriesId = 2L, seasonNumber = 6),
            ),
            ComputeEnforcementContext(p5SeasonRatchetActive = true),
        )
        // A's min=2 active, A's S3 deferred. B's min=5 active, B's S6 deferred.
        assertEquals(TargetState.ACTIVE, decisions["a-s2"]!!.targetState)
        assertEquals(TargetState.DEFERRED, decisions["a-s3"]!!.targetState)
        assertEquals(TargetState.ACTIVE, decisions["b-s5"]!!.targetState)
        assertEquals(TargetState.DEFERRED, decisions["b-s6"]!!.targetState)
    }

    @Test fun layer2_null_seasonNumber_stays_active() {
        val decisions = computeEnforcement(
            listOf(
                dl("known", 5, seriesId = 1L, seasonNumber = 1),
                dl("unknown", 5, seriesId = 1L, seasonNumber = null),
            ),
            ComputeEnforcementContext(p5SeasonRatchetActive = true),
        )
        assertEquals(TargetState.ACTIVE, decisions["known"]!!.targetState)
        assertEquals(TargetState.ACTIVE, decisions["unknown"]!!.targetState)
    }

    @Test fun layer2_null_seriesId_skips_rule() {
        val decisions = computeEnforcement(
            listOf(
                dl("orphan-s2", 5, seriesId = null, seasonNumber = 2),
                dl("orphan-s5", 5, seriesId = null, seasonNumber = 5),
            ),
            ComputeEnforcementContext(p5SeasonRatchetActive = true),
        )
        // No seriesId → no grouping → both ACTIVE
        assertEquals(TargetState.ACTIVE, decisions["orphan-s2"]!!.targetState)
        assertEquals(TargetState.ACTIVE, decisions["orphan-s5"]!!.targetState)
    }

    @Test fun layer2_does_not_touch_non_p5() {
        val decisions = computeEnforcement(
            listOf(
                dl("p3-s1", 3, seriesId = 1L, seasonNumber = 1),
                dl("p3-s5", 3, seriesId = 1L, seasonNumber = 5),
            ),
            ComputeEnforcementContext(p5SeasonRatchetActive = true),
        )
        assertEquals(TargetState.ACTIVE, decisions["p3-s1"]!!.targetState)
        assertEquals(TargetState.ACTIVE, decisions["p3-s5"]!!.targetState)
    }

    @Test fun layer2_excludes_paused_user_from_min_calc() {
        // S1 is paused-by-user; the "effective" min should be S2.
        // Only S3+ get deferred; S1 stays untouched (PAUSED_BY_USER).
        val decisions = computeEnforcement(
            listOf(
                dl("s1", 5, seriesId = 1L, seasonNumber = 1, state = ManagedState.PAUSED_BY_USER),
                dl("s2", 5, seriesId = 1L, seasonNumber = 2),
                dl("s3", 5, seriesId = 1L, seasonNumber = 3),
            ),
            ComputeEnforcementContext(p5SeasonRatchetActive = true),
        )
        assertEquals(TargetState.ACTIVE, decisions["s1"]!!.targetState)  // user-paused = ACTIVE target, never touch
        assertEquals(TargetState.ACTIVE, decisions["s2"]!!.targetState)
        assertEquals(TargetState.DEFERRED, decisions["s3"]!!.targetState)
    }

    @Test fun layer2_layer1_wins_when_both_apply() {
        // P1 is active in some other series → Layer 1 defers all P5 anyway
        val decisions = computeEnforcement(
            listOf(
                dl("p1", 1, seriesId = 9L),
                dl("s1", 5, seriesId = 1L, seasonNumber = 1),
                dl("s2", 5, seriesId = 1L, seasonNumber = 2),
            ),
            ComputeEnforcementContext(p5SeasonRatchetActive = true),
        )
        assertEquals(TargetState.DEFERRED, decisions["s1"]!!.targetState)
        assertEquals(TargetState.DEFERRED, decisions["s2"]!!.targetState)
    }

    // ---------- orderHint ----------

    @Test fun orderHint_priority_dominates_season() {
        val p1 = dl("p1", 1, seasonNumber = 9, episodeNumber = 99)
        val p5 = dl("p5", 5, seasonNumber = 1, episodeNumber = 1)
        val decisions = computeEnforcement(listOf(p1, p5), ComputeEnforcementContext())
        // Lower hint = earlier; P1 must outrank P5 regardless of season
        kotlin.test.assertTrue(decisions["p1"]!!.orderHint < decisions["p5"]!!.orderHint)
    }

    @Test fun orderHint_within_priority_uses_season_then_episode() {
        val s1e2 = dl("s1e2", 5, seasonNumber = 1, episodeNumber = 2)
        val s1e1 = dl("s1e1", 5, seasonNumber = 1, episodeNumber = 1)
        val s2e1 = dl("s2e1", 5, seasonNumber = 2, episodeNumber = 1)
        val decisions = computeEnforcement(listOf(s1e2, s1e1, s2e1), ComputeEnforcementContext())
        val sorted = listOf("s1e2", "s1e1", "s2e1").sortedBy { decisions[it]!!.orderHint }
        kotlin.test.assertEquals(listOf("s1e1", "s1e2", "s2e1"), sorted)
    }
```

- [ ] **Step 2: Run the tests, expect FAIL**

```
cd D:/docker/prioritarr/prioritarr ; ./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.enforcement.ComputeEnforcementTest"
```
Expected: Layer 2 tests fail (`s2e1` ACTIVE instead of DEFERRED, etc).

- [ ] **Step 3: Implement Layer 2**

Replace the body of `computeEnforcement` in `ComputeEnforcement.kt`:

```kotlin
fun computeEnforcement(
    downloads: List<ManagedDownloadView>,
    ctx: ComputeEnforcementContext,
): Map<String, EnforcementDecision> {
    val result = LinkedHashMap<String, EnforcementDecision>(downloads.size)

    // ---------- Layer 1: cross-band ----------
    val crossBandActives = downloads
        .filter { it.state == ManagedState.RUNNING }
        .map { it.priority }
        .toSet()
    val hasP1 = 1 in crossBandActives
    val hasP2 = 2 in crossBandActives
    val crossBandDeferLevels: Set<Int> = when {
        hasP1 -> setOf(4, 5)
        hasP2 -> setOf(5)
        else -> emptySet()
    }

    // ---------- Layer 2: P5 sub-band, per-series ----------
    // For each P5 series, find the minimum seasonNumber among items
    // that are RUNNING and have a known seasonNumber. Items with a
    // greater seasonNumber and matching seriesId become DEFERRED.
    // Items with seasonNumber == null or seriesId == null are not
    // affected by Layer 2 (they fall through ACTIVE).
    val p5MinSeasonBySeries: Map<Long, Int> =
        if (!ctx.p5SeasonRatchetActive) emptyMap()
        else downloads
            .asSequence()
            .filter { it.priority == 5 }
            .filter { it.seriesId != null && it.seasonNumber != null }
            .filter { it.state == ManagedState.RUNNING }
            .groupBy { it.seriesId!! }
            .mapValues { (_, items) -> items.minOf { it.seasonNumber!! } }

    for (d in downloads) {
        // Layer 1 first
        val deferByCrossBand = d.priority in crossBandDeferLevels &&
            !ctx.p1IsPeerLimited &&
            !ctx.isNearDone(d) &&
            d.state == ManagedState.RUNNING

        // Layer 2 only kicks in when ratchet is active AND Layer 1
        // didn't already defer the item AND the item is RUNNING
        // (PAUSED_BY_USER / ERRORED stay ACTIVE-target as a no-op signal)
        val deferByP5SubBand = !deferByCrossBand &&
            ctx.p5SeasonRatchetActive &&
            d.priority == 5 &&
            d.state == ManagedState.RUNNING &&
            d.seriesId != null &&
            d.seasonNumber != null &&
            p5MinSeasonBySeries[d.seriesId]?.let { min -> d.seasonNumber > min } == true

        val target = if (deferByCrossBand || deferByP5SubBand) TargetState.DEFERRED else TargetState.ACTIVE
        result[d.clientId] = EnforcementDecision(
            targetState = target,
            orderHint = orderHintOf(d),
        )
    }
    return result
}
```

- [ ] **Step 4: Run tests, expect ALL PASS**

```
cd D:/docker/prioritarr/prioritarr ; ./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.enforcement.ComputeEnforcementTest"
```
Expected: PASS (all Layer 1 + Layer 2 + orderHint tests).

- [ ] **Step 5: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/enforcement/ComputeEnforcement.kt \
        prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/enforcement/ComputeEnforcementTest.kt
git commit -m "feat(enforcement): computeEnforcement Layer 2 (P5 sub-band ratchet)"
```

---

## Phase 5 — DownloadClient interface + implementations

### Task 10: Extend `DownloadClient` interface

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/clients/DownloadClient.kt`

- [ ] **Step 1: Add the two new methods**

Append to the `DownloadClient` interface (before its closing brace):

```kotlin
    /**
     * Snapshot the client's queue, normalised to [RawDownload]. The
     * caller (reconciler) joins these against [Database.listManagedDownloads]
     * and Sonarr's queue to produce [ManagedDownloadView]s for
     * [computeEnforcement]. Returning an empty list on transient
     * errors is preferred over throwing — the reconciler is supervised
     * but a hot-loop stack trace is noise.
     */
    suspend fun snapshotDownloads(): List<org.yoshiz.app.prioritarr.backend.enforcement.RawDownload>

    /**
     * Apply the calculated decisions for items belonging to this
     * client. The implementation is free to translate ACTIVE/DEFERRED
     * + orderHint to whatever native API achieves the same observable
     * effect with minimum churn.
     *
     * Decisions for items not belonging to this client (different
     * `client` field) MUST be ignored — the reconciler filters before
     * dispatching, but defence in depth is cheap.
     */
    suspend fun applyEnforcement(
        decisions: Map<String, org.yoshiz.app.prioritarr.backend.enforcement.EnforcementDecision>,
    )
```

- [ ] **Step 2: Update the doc-comment at the top of the file**

Edit the top-of-file KDoc to remove the "Not unified at the enforcement-strategy level" claim, since that's now stale. Replace the third paragraph (starts "**Not** unified at the enforcement-strategy level") with:

```
 * Enforcement strategy IS unified at the calculation level
 * ([org.yoshiz.app.prioritarr.backend.enforcement.computeEnforcement]).
 * Each client's [applyEnforcement] translates the calculated
 * decisions to its native API; qBit emits pause/resume + setTopPriority,
 * SAB walks its priority bucket via queue/switch.
```

- [ ] **Step 3: Build — qBit and SAB will fail to compile until Tasks 11+12**

```
cd D:/docker/prioritarr/prioritarr ; ./gradlew :backend:compileKotlin
```
Expected: FAIL — "Class 'QBitClient' is not abstract and does not implement abstract member..." This is the expected scaffolding failure; we'll fix it in the next two tasks.

- [ ] **Step 4: Commit interface-only change**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/clients/DownloadClient.kt
git commit -m "feat(download-client): interface gains snapshotDownloads + applyEnforcement"
```

(Tree won't compile yet — next tasks complete the implementations.)

---

### Task 11: Implement `snapshotDownloads` + `applyEnforcement` for qBit

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/.../clients/QBittorrent.kt`

- [ ] **Step 1: Inspect existing `getTorrents`, `pause`, `resume`, `topPriority` methods**

Open `QBittorrent.kt` and find `class QBitClient`. Note the names of the existing methods: `getTorrents`, `pause(hashes: List<String>)`, `resume(hashes: List<String>)`, `topPriority(hashes: List<String>)`.

- [ ] **Step 2: Implement `snapshotDownloads`**

Add to the `QBitClient` class (alphabetical with existing methods is fine — placement isn't strict):

```kotlin
    override suspend fun snapshotDownloads():
        List<org.yoshiz.app.prioritarr.backend.enforcement.RawDownload> {
        val torrents = try { getTorrents() } catch (_: Exception) { return emptyList() }
        return torrents.mapNotNull { el ->
            val o = el.jsonObject
            val hash = o["hash"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val state = o["state"]?.jsonPrimitive?.contentOrNull ?: "downloading"
            val eta = o["eta"]?.jsonPrimitive?.longOrNull
            org.yoshiz.app.prioritarr.backend.enforcement.RawDownload(
                client = "qbit",
                clientId = hash,
                rawState = state,
                pausedByUs = false, // joined from managed_downloads in the reconciler
                etaSeconds = eta,
            )
        }
    }
```

You may need to add imports at the top:
```kotlin
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
```

- [ ] **Step 3: Implement `applyEnforcement`**

```kotlin
    override suspend fun applyEnforcement(
        decisions: Map<String, org.yoshiz.app.prioritarr.backend.enforcement.EnforcementDecision>,
    ) {
        // Filter to this client. The reconciler usually pre-filters,
        // but defence in depth is cheap.
        val mine = decisions.filterKeys { hash ->
            // No way to know which client a hash "belongs to" from a
            // bare map — we trust the caller filtered. The overload
            // [applyEnforcement(views, decisions)] in the reconciler
            // path scopes the views to qBit before calling.
            true
        }

        // Pause/resume verbs.
        val toPause = mutableListOf<String>()
        val toResume = mutableListOf<String>()
        for ((hash, decision) in mine) {
            when (decision.targetState) {
                org.yoshiz.app.prioritarr.backend.enforcement.TargetState.DEFERRED -> toPause += hash
                org.yoshiz.app.prioritarr.backend.enforcement.TargetState.ACTIVE -> toResume += hash
            }
        }
        if (toPause.isNotEmpty()) try { pause(toPause) } catch (_: Exception) {}
        if (toResume.isNotEmpty()) try { resume(toResume) } catch (_: Exception) {}

        // Order: among ACTIVE items, set the lowest orderHint at the
        // top of qBit's queue. qBit's setTopPriority places at top,
        // so call from highest hint to lowest — the lowest ends up at
        // the very top.
        val orderedActives = mine
            .filterValues { it.targetState == org.yoshiz.app.prioritarr.backend.enforcement.TargetState.ACTIVE }
            .entries
            .sortedByDescending { it.value.orderHint }
            .map { it.key }
        if (orderedActives.isNotEmpty()) try { topPriority(orderedActives) } catch (_: Exception) {}
    }
```

- [ ] **Step 4: Build to verify**

```
cd D:/docker/prioritarr/prioritarr ; ./gradlew :backend:compileKotlin
```
Expected: SAB still fails (next task), but qBit's lines should clear.

- [ ] **Step 5: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/clients/QBittorrent.kt
git commit -m "feat(qbit): implement snapshotDownloads + applyEnforcement"
```

---

### Task 12: Implement `snapshotDownloads` + `applyEnforcement` for SAB

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/.../clients/Sabnzbd.kt`

- [ ] **Step 1: Inspect existing SAB client methods**

Open `Sabnzbd.kt` and find `class SABClient`. Note the existing `getQueue`, `setPriority`, `PRIORITY_MAP`. Also find the queue-switch endpoint shape (SAB's `mode=switch&value=<nzo1>&value2=<nzo2>` API). If not yet wrapped, you'll add it.

- [ ] **Step 2: Add a `queueSwitch` low-level helper if not present**

Inside `SABClient`, add (find the other private/internal helpers and place near them):

```kotlin
    /**
     * Swap two queue items by nzo_id. SAB's API: mode=switch with
     * `value` = first nzo_id and `value2` = second. Used by the
     * P5-ratchet reorder to walk Low-bucket items into season order.
     */
    suspend fun queueSwitch(nzoA: String, nzoB: String) {
        try {
            http.get("$root/api") {
                parameter("apikey", apiKey)
                parameter("mode", "switch")
                parameter("output", "json")
                parameter("value", nzoA)
                parameter("value2", nzoB)
            }
        } catch (_: Exception) {
            // Best-effort; SAB's switch is idempotent on the next tick
            // and a transient failure isn't worth bubbling up.
        }
    }
```

If `http.get` / `parameter` / `root` aren't already imported / declared, mirror what `setPriority` does in the same file.

- [ ] **Step 3: Implement `snapshotDownloads`**

```kotlin
    override suspend fun snapshotDownloads():
        List<org.yoshiz.app.prioritarr.backend.enforcement.RawDownload> {
        val slots = try { getQueue() } catch (_: Exception) { return emptyList() }
        return slots.mapNotNull { el ->
            val o = el.jsonObject
            val nzo = o["nzo_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val status = o["status"]?.jsonPrimitive?.contentOrNull ?: "Queued"
            org.yoshiz.app.prioritarr.backend.enforcement.RawDownload(
                client = "sab",
                clientId = nzo,
                rawState = status,
                pausedByUs = false,        // joined from managed_downloads in the reconciler
                etaSeconds = null,         // SAB exposes timeleft as text; we don't parse
            )
        }
    }
```

- [ ] **Step 4: Implement `applyEnforcement`**

For SAB, the priority bucket is the primary signal. Within a bucket, queue order is honoured serially. Strategy: trust `setPriority` for the bucket mapping (already done elsewhere on a different reconcile path — see Task 13 below; this method only handles ordering hints), and use `queueSwitch` to sort within the bucket.

```kotlin
    override suspend fun applyEnforcement(
        decisions: Map<String, org.yoshiz.app.prioritarr.backend.enforcement.EnforcementDecision>,
    ) {
        // SAB priority buckets are set by [setPriority] in the
        // reconciler's per-row pass (mapped from priority via
        // computeSabPriority). This method handles the within-bucket
        // ordering: walk the current queue in order, compare to
        // desired-by-orderHint, and emit queue/switch swaps where they
        // differ.
        val slots = try { getQueue() } catch (_: Exception) { return }
        val currentOrder: List<String> = slots.mapNotNull {
            it.jsonObject["nzo_id"]?.jsonPrimitive?.contentOrNull
        }
        // Only consider items we have decisions for AND that are ACTIVE
        // (DEFERRED items are demoted via the priority bucket — no
        // sense reordering within a bucket if the item is also paused).
        val active = decisions.filter {
            it.value.targetState == org.yoshiz.app.prioritarr.backend.enforcement.TargetState.ACTIVE
        }
        val desired = currentOrder
            .filter { it in active }
            .sortedBy { active[it]!!.orderHint }

        // Walk currentOrder; for each item not already in its desired
        // position by more than one slot, swap with the actual desired
        // neighbour. This minimises churn under SAB's slightly jittery
        // position reporting.
        val currentManaged = currentOrder.filter { it in active }
        for (i in desired.indices) {
            val want = desired[i]
            val have = currentManaged.getOrNull(i) ?: continue
            if (want != have) {
                queueSwitch(have, want)
            }
        }
    }
```

- [ ] **Step 5: Build to verify the tree compiles end-to-end**

```
cd D:/docker/prioritarr/prioritarr ; ./gradlew :backend:compileKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/clients/Sabnzbd.kt
git commit -m "feat(sab): snapshotDownloads + applyEnforcement + queueSwitch helper"
```

---

## Phase 6 — `reconcileAll`

### Task 13: Cross-ref enrichment helper

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/.../reconcile/Reconcile.kt`

- [ ] **Step 1: Add a helper that returns enriched `(seriesId, seasonNumber, episodeNumber)` for a downloadId**

The existing `fetchSonarrQueueLookup` only returns `(seriesId, episodeId)`. We need to enrich with `seasonNumber` + `episodeNumber`. Sonarr's `/queue` includes `episode.seasonNumber` and `episode.episodeNumber` on each entry — extend the lookup function to return a richer shape.

Replace `fetchSonarrQueueLookup` with:

```kotlin
data class SonarrQueueInfo(
    val seriesId: Long,
    val episodeId: Long,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
)

/** Build the `download_id (lowercase) → SonarrQueueInfo` map from Sonarr's /queue. */
suspend fun fetchSonarrQueueLookup(sonarr: SonarrClient): Map<String, SonarrQueueInfo> {
    val queue = try {
        sonarr.getQueue()
    } catch (e: Exception) {
        logger.warn("fetchSonarrQueueLookup: failed to fetch sonarr queue: ${e.message}")
        return emptyMap()
    }
    val out = mutableMapOf<String, SonarrQueueInfo>()
    for (el in queue) {
        val obj = el.jsonObject
        val dlId = obj["downloadId"]?.jsonPrimitive?.contentOrNull ?: continue
        val seriesId = obj["seriesId"]?.jsonPrimitive?.longOrNull ?: continue
        val episodeId = obj["episodeId"]?.jsonPrimitive?.longOrNull ?: 0L
        val episode = obj["episode"]?.jsonObject
        val season = episode?.get("seasonNumber")?.jsonPrimitive?.intOrNull
            ?: obj["seasonNumber"]?.jsonPrimitive?.intOrNull
        val epNum = episode?.get("episodeNumber")?.jsonPrimitive?.intOrNull
        out[dlId.lowercase()] = SonarrQueueInfo(seriesId, episodeId, season, epNum)
    }
    return out
}
```

You may need additional imports:
```kotlin
import kotlinx.serialization.json.intOrNull
```

- [ ] **Step 2: Update existing call sites that destructure the old `Pair`**

Search the codebase for `sonarrQueueLookup[`. Two call sites in `Reconcile.kt` (lines ~145 and ~150 in the old version) destructure the old `Pair<Long, Long>`. Update them:

Old:
```kotlin
val sonarrInfo = sonarrQueueLookup[clientId.lowercase()]
if (sonarrInfo == null) { ... }
val (seriesId, episodeId) = sonarrInfo
```

New:
```kotlin
val sonarrInfo = sonarrQueueLookup[clientId.lowercase()]
if (sonarrInfo == null) { ... }
val seriesId = sonarrInfo.seriesId
val episodeId = sonarrInfo.episodeId
```

Also update the `reconcileQbit` and `reconcileSab` parameter type from `Map<String, Pair<Long, Long>>` to `Map<String, SonarrQueueInfo>`. And update `reconcileImpl`'s parameter the same way.

- [ ] **Step 3: Update Main.kt callers if any reference the old `Pair` shape**

```
grep -n "sonarrQueueLookup\|Pair<Long, Long>" prioritarr/backend/src/main/kotlin/
```
Update any other callers to use `SonarrQueueInfo` field access instead of `Pair` destructuring.

- [ ] **Step 4: Build to verify**

```
cd D:/docker/prioritarr/prioritarr ; ./gradlew :backend:compileKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/reconcile/Reconcile.kt
git commit -m "refactor(reconcile): SonarrQueueInfo carries season + episode numbers"
```

---

### Task 14: Add `reconcileAll` and route through `computeEnforcement`

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/.../reconcile/Reconcile.kt`
- Modify: `prioritarr/backend/src/main/kotlin/.../Main.kt` (only the QUEUE_RECONCILE job wiring)

- [ ] **Step 1: Add `reconcileAll` alongside the existing per-client functions**

Append to `Reconcile.kt`:

```kotlin
/**
 * Unified reconcile pass over all configured download clients. Replaces
 * the per-client `reconcileQbit` + `reconcileSab` pair: snapshots each
 * client, joins with managed_downloads + Sonarr queue, runs the unified
 * computeEnforcement, then dispatches each client's slice of decisions
 * to its applyEnforcement.
 *
 * Per-client `reconcileQbit` / `reconcileSab` are kept for now (the
 * scheduler call sites in Main.kt switch over in the next step) but are
 * scheduled for removal once the parity tests + a few production
 * cycles confirm the new path produces the same observable behaviour.
 */
suspend fun reconcileAll(
    qbit: org.yoshiz.app.prioritarr.backend.clients.QBitClient,
    sab: org.yoshiz.app.prioritarr.backend.clients.SABClient,
    sonarr: SonarrClient,
    db: Database,
    priorityService: PriorityService,
    bandwidth: org.yoshiz.app.prioritarr.backend.config.BandwidthSettings,
    p5Ratchet: org.yoshiz.app.prioritarr.backend.config.P5RatchetConfig,
    telemetry: org.yoshiz.app.prioritarr.backend.enforcement.DownloadTelemetry?,
    dryRun: Boolean,
) {
    val sonarrQueueLookup = fetchSonarrQueueLookup(sonarr)

    // Run the existing per-client reconcileImpl (orphan adoption,
    // priority recompute, cleanup). Don't call its enforcement step —
    // we'll do that ourselves below with the unified path.
    runReconcileImplOnly(
        qbit = qbit, sab = sab, db = db,
        sonarrQueueLookup = sonarrQueueLookup,
        priorityService = priorityService,
    )

    if (dryRun) return

    // Snapshot each client and project to ManagedDownloadView via
    // managed_downloads + the queue lookup.
    val rawByClient: Map<String, List<org.yoshiz.app.prioritarr.backend.enforcement.RawDownload>> =
        mapOf(
            "qbit" to qbit.snapshotDownloads(),
            "sab" to sab.snapshotDownloads(),
        )

    // Persist newly-seen seasonNumbers from Sonarr's queue lookup
    // into managed_downloads.season_number so the column is available
    // to subsequent ticks even if Sonarr's queue blips.
    for ((_, raws) in rawByClient) {
        for (r in raws) {
            val info = sonarrQueueLookup[r.clientId.lowercase()] ?: continue
            val season = info.seasonNumber ?: continue
            db.setManagedSeasonNumber(r.client, r.clientId, season)
        }
    }

    val views = rawByClient.flatMap { (clientName, raws) ->
        raws.mapNotNull { r ->
            val managed = db.getManagedDownload(clientName, r.clientId) ?: return@mapNotNull null
            val info = sonarrQueueLookup[r.clientId.lowercase()]
            val state = projectState(rawState = r.rawState, pausedByUs = managed.paused_by_us == 1L)
            org.yoshiz.app.prioritarr.backend.enforcement.ManagedDownloadView(
                client = clientName,
                clientId = r.clientId,
                priority = managed.current_priority.toInt(),
                seriesId = managed.series_id,
                seasonNumber = managed.season_number?.toInt() ?: info?.seasonNumber,
                episodeNumber = info?.episodeNumber,
                state = state,
                etaSeconds = r.etaSeconds,
            )
        }
    }

    // Bandwidth signal — read once.
    val ctx = buildEnforcementContext(views, bandwidth, p5Ratchet, telemetry)

    val decisions = org.yoshiz.app.prioritarr.backend.enforcement.computeEnforcement(views, ctx)

    // Per-client SAB priority bucket pass first (existing behaviour).
    runSabPriorityBucketPass(sab, db)

    // Dispatch enforcement to each client.
    val byClient = decisions.entries.groupBy { e ->
        views.firstOrNull { it.clientId == e.key }?.client ?: "qbit"
    }
    qbit.applyEnforcement(byClient["qbit"]?.associate { it.toPair() } ?: emptyMap())
    sab.applyEnforcement(byClient["sab"]?.associate { it.toPair() } ?: emptyMap())

    // pausedByUs flag in DB — keep in sync with the decision for each
    // qBit / SAB row that we touched.
    for ((cid, decision) in decisions) {
        val v = views.firstOrNull { it.clientId == cid } ?: continue
        val newFlag = decision.targetState ==
            org.yoshiz.app.prioritarr.backend.enforcement.TargetState.DEFERRED
        db.q.setManagedPaused(if (newFlag) 1L else 0L, v.client, cid)
        if (newFlag && v.priority == 5 &&
            ctx.p5SeasonRatchetActive &&
            // Only audit Layer-2 defers (Layer 1 audits stay where they were)
            views.any { it.priority == 5 && it.seriesId == v.seriesId && it.seasonNumber != null && it.seasonNumber!! < (v.seasonNumber ?: Int.MAX_VALUE) }
        ) {
            db.appendAudit(
                action = "p5_ratchet_defer",
                seriesId = v.seriesId,
                client = v.client,
                clientId = v.clientId,
                details = kotlinx.serialization.json.buildJsonObject {
                    kotlinx.serialization.json.put("season_number", v.seasonNumber ?: -1)
                },
            )
        }
    }
}

/**
 * Runs only the orphan-adoption + priority-recompute + cleanup phases
 * of the legacy per-client reconcile, skipping its enforcement step.
 * Lets [reconcileAll] do enforcement once over the unified view.
 */
private suspend fun runReconcileImplOnly(
    qbit: org.yoshiz.app.prioritarr.backend.clients.QBitClient,
    sab: org.yoshiz.app.prioritarr.backend.clients.SABClient,
    db: Database,
    sonarrQueueLookup: Map<String, SonarrQueueInfo>,
    priorityService: PriorityService,
) {
    val torrents = try { qbit.getTorrents() } catch (_: Exception) { JsonArray(emptyList()) }
    reconcileImpl(
        clientName = "qbit",
        queue = torrents,
        idField = "hash",
        stateField = "state",
        db = db,
        sonarrQueueLookup = sonarrQueueLookup,
        priorityService = priorityService,
        applyEnforcement = { /* no-op here; reconcileAll does it */ },
    )
    val slots = try { sab.getQueue() } catch (_: Exception) { JsonArray(emptyList()) }
    reconcileImpl(
        clientName = "sab",
        queue = slots,
        idField = "nzo_id",
        stateField = null,
        db = db,
        sonarrQueueLookup = sonarrQueueLookup,
        priorityService = priorityService,
        applyEnforcement = { /* no-op */ },
    )
}

/** Per-row SAB priority bucket pass, lifted out of applySabEnforcement. */
private suspend fun runSabPriorityBucketPass(
    sab: org.yoshiz.app.prioritarr.backend.clients.SABClient,
    db: Database,
) {
    val managed = db.listManagedDownloads("sab")
    for (row in managed) {
        val sabPriority = org.yoshiz.app.prioritarr.backend.enforcement.computeSabPriority(row.current_priority.toInt())
        try { sab.setPriority(row.client_id, sabPriority) } catch (_: Exception) {}
        db.appendAudit(
            action = org.yoshiz.app.prioritarr.backend.AuditAction.PRIORITY_SET,
            client = org.yoshiz.app.prioritarr.backend.DownloadClientName.SAB.wire,
            clientId = row.client_id,
            seriesId = row.series_id,
            details = kotlinx.serialization.json.buildJsonObject {
                kotlinx.serialization.json.put("sab_priority", sabPriority)
                kotlinx.serialization.json.put("source", "reconcile")
            },
        )
    }
}

private fun projectState(rawState: String, pausedByUs: Boolean): org.yoshiz.app.prioritarr.backend.enforcement.ManagedState {
    if (pausedByUs) return org.yoshiz.app.prioritarr.backend.enforcement.ManagedState.PAUSED_BY_US
    return when (rawState.lowercase()) {
        "paused", "paused_dl", "pauseddl", "pausedup" ->
            org.yoshiz.app.prioritarr.backend.enforcement.ManagedState.PAUSED_BY_USER
        "error", "missingfiles", "failed" ->
            org.yoshiz.app.prioritarr.backend.enforcement.ManagedState.ERRORED
        else -> org.yoshiz.app.prioritarr.backend.enforcement.ManagedState.RUNNING
    }
}

private fun buildEnforcementContext(
    views: List<org.yoshiz.app.prioritarr.backend.enforcement.ManagedDownloadView>,
    bandwidth: org.yoshiz.app.prioritarr.backend.config.BandwidthSettings,
    p5Ratchet: org.yoshiz.app.prioritarr.backend.config.P5RatchetConfig,
    telemetry: org.yoshiz.app.prioritarr.backend.enforcement.DownloadTelemetry?,
): org.yoshiz.app.prioritarr.backend.enforcement.ComputeEnforcementContext {
    if (bandwidth.maxMbps <= 0) {
        return org.yoshiz.app.prioritarr.backend.enforcement.ComputeEnforcementContext()
    }
    val totalBps = telemetry?.observedPeakTotalBps() ?: 0L
    val policy = org.yoshiz.app.prioritarr.backend.enforcement.BandwidthPolicy
    // P5 ratchet has its own threshold knob; if null, fall back to BandwidthSettings.
    val ratchetBandwidth = p5Ratchet.bandwidthThresholdPct?.let { bandwidth.copy(utilisationThresholdPct = it) }
        ?: bandwidth
    val saturated = policy.utilisationExceedsThreshold(bandwidth, totalBps)
    val ratchetSaturated = policy.utilisationExceedsThreshold(ratchetBandwidth, totalBps)
    val p1Hashes = views.filter { it.priority == 1 }.map { it.clientId }
    val p1AvgBps = p1Hashes.mapNotNull { telemetry?.averageBps(it) }.maxOrNull()
    val p1PeerLimited = policy.p1IsPeerLimited(bandwidth, p1AvgBps)
    val ratchetActive = p5Ratchet.enabled && ratchetSaturated
    return org.yoshiz.app.prioritarr.backend.enforcement.ComputeEnforcementContext(
        bandwidthSaturated = saturated,
        p5SeasonRatchetActive = ratchetActive,
        p1IsPeerLimited = p1PeerLimited,
        isNearDone = { v ->
            policy.closeToFinish(bandwidth, v.etaSeconds)
        },
    )
}
```

You'll need imports at the top for the freshly-referenced types:
```kotlin
import org.yoshiz.app.prioritarr.backend.config.P5RatchetConfig
```

- [ ] **Step 2: Wire `reconcileAll` into Main.kt**

Find the `JobId.QUEUE_RECONCILE` `JobDefinition` in `Main.kt` (search for `QUEUE_RECONCILE`). Replace the `run = { … reconcileQbit(...) … reconcileSab(...) … }` body with:

```kotlin
                run = {
                    val s = liveSettings(db, settings)
                    org.yoshiz.app.prioritarr.backend.reconcile.reconcileAll(
                        qbit = qbit,
                        sab = sab,
                        sonarr = sonarr,
                        db = db,
                        priorityService = priorityService,
                        bandwidth = liveBandwidth(db, settings),
                        p5Ratchet = s.p5Ratchet,
                        telemetry = downloadTelemetry,
                        dryRun = s.dryRun,
                    )
                    org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome()
                },
```

Read the surrounding code to confirm the names of existing helpers (`liveSettings`, `liveBandwidth`, `downloadTelemetry`). If `liveBandwidth` doesn't exist, mirror `liveSettings`'s pattern — the existing reconcile code passes `BandwidthSettings(...)` constructed from settings + DB override.

- [ ] **Step 3: Build**

```
cd D:/docker/prioritarr/prioritarr ; ./gradlew :backend:compileKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the full test suite — make sure nothing in EnforcementTest broke**

```
cd D:/docker/prioritarr/prioritarr ; ./gradlew :backend:test
```
Expected: PASS. Existing `EnforcementTest` is still on the old `computeQBitPauseActions`; we'll deprecate it in Phase 9.

- [ ] **Step 5: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/reconcile/Reconcile.kt \
        prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/Main.kt
git commit -m "feat(reconcile): reconcileAll dispatches via unified computeEnforcement"
```

---

## Phase 7 — Search-side ratchet

### Task 15: `buildP5RatchetPlan` — pure planner

**Files:**
- Create: `prioritarr/backend/src/main/kotlin/.../sweep/P5Ratchet.kt`
- Create: `prioritarr/backend/src/test/kotlin/.../sweep/P5RatchetTest.kt`

- [ ] **Step 1: Stub the planner with input/output types**

Create `P5Ratchet.kt`:

```kotlin
package org.yoshiz.app.prioritarr.backend.sweep

import org.yoshiz.app.prioritarr.backend.config.P5RatchetConfig
import org.yoshiz.app.prioritarr.backend.database.Database

/** Per-(series, season) attempt history, mirrors Database.P5SweepAttempt. */
typealias P5Attempt = Database.P5SweepAttempt

/**
 * One missing-episode record fed to the planner — stripped down from
 * Sonarr's /wanted/missing payload to the fields the picker actually
 * uses. Built by the runner before calling [buildP5RatchetPlan].
 */
data class MissingRecord(
    val seriesId: Long,
    val seasonNumber: Int,
    val episodeId: Long,
    val airDateUtc: String,            // ISO date or "9999" for sort fallback
    val priority: Int,                 // recompute-time band (only priority=5 reaches this planner)
)

/**
 * Sonarr /queue cross-ref needed to detect "queue presence" without
 * a separate fetch. The planner only cares about
 * `(seriesId, seasonNumber)` → has-queue-entry?; the runner builds it.
 */
data class QueueSeasonHit(val seriesId: Long, val seasonNumber: Int)

data class P5RatchetInputs(
    val p5Records: List<MissingRecord>,
    val queueHits: Set<QueueSeasonHit>,
    val cooldownRows: List<P5Attempt>,
    val nowEpochSeconds: Long,
    val cfg: P5RatchetConfig,
    val budgetRemaining: Int,
    val ratchetActive: Boolean,
)

sealed interface RatchetAction {
    val seriesId: Long
    data class SeasonSearch(override val seriesId: Long, val seasonNumber: Int) : RatchetAction
    data class EpisodeSearch(
        override val seriesId: Long,
        val seasonNumber: Int,
        val episodeIds: List<Long>,
    ) : RatchetAction
    data class SeriesSearch(override val seriesId: Long) : RatchetAction
}

data class P5RatchetPlan(
    val actions: List<RatchetAction>,
    val cooldownWrites: List<P5Attempt>,
)

/** Pure decision function. See spec §Algorithms. */
fun buildP5RatchetPlan(inputs: P5RatchetInputs): P5RatchetPlan {
    TODO("implement in next steps")
}
```

- [ ] **Step 2: Write the failing tests**

Create `P5RatchetTest.kt`:

```kotlin
package org.yoshiz.app.prioritarr.backend.sweep

import org.yoshiz.app.prioritarr.backend.config.P5RatchetConfig
import org.yoshiz.app.prioritarr.backend.database.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class P5RatchetTest {

    private val cfg = P5RatchetConfig(
        enabled = true,
        searchCooldownHours = 24,
        longCooldownHours = 168,
        escalationThreshold = 5,
        includeSpecials = false,
        bandwidthThresholdPct = null,
    )
    private val now = 10_000_000L
    private fun hoursAgo(h: Int): Long = now - h * 3600L

    private fun rec(seriesId: Long, season: Int, episodeId: Long, airDate: String = "2020-01-01") =
        MissingRecord(seriesId, season, episodeId, airDate, priority = 5)

    private fun attempt(
        seriesId: Long, season: Int,
        lastAttemptedAt: Long = hoursAgo(25),
        lastMissingCount: Int? = 5,
        consecutive: Int = 0,
    ) = Database.P5SweepAttempt(seriesId, season, lastAttemptedAt, lastMissingCount, consecutive)

    private fun inputs(
        records: List<MissingRecord> = emptyList(),
        cooldowns: List<Database.P5SweepAttempt> = emptyList(),
        queueHits: Set<QueueSeasonHit> = emptySet(),
        budget: Int = 10,
        active: Boolean = true,
        cfgOverride: P5RatchetConfig = cfg,
    ) = P5RatchetInputs(records, queueHits, cooldowns, now, cfgOverride, budget, active)

    // ---------- bandwidth-headroom shortcut ----------

    @Test fun ratchet_inactive_returns_seriesSearch_per_series_in_oldest_air_date_order() {
        val plan = buildP5RatchetPlan(inputs(
            records = listOf(
                rec(1L, 5, 1L, "2024-01-01"),
                rec(2L, 1, 2L, "2018-01-01"),
                rec(3L, 1, 3L, "2020-01-01"),
            ),
            active = false,
        ))
        // Oldest air date first: series 2 (2018) → series 3 (2020) → series 1 (2024)
        assertEquals(
            listOf(2L, 3L, 1L),
            plan.actions.map { it.seriesId },
        )
        assertTrue(plan.actions.all { it is RatchetAction.SeriesSearch })
        assertTrue(plan.cooldownWrites.isEmpty()) // no state writes when ratchet off
    }

    // ---------- happy path ----------

    @Test fun fresh_series_picks_lowest_season_with_seasonSearch() {
        val plan = buildP5RatchetPlan(inputs(
            records = listOf(
                rec(1L, 1, 11L), rec(1L, 1, 12L),
                rec(1L, 2, 21L),
                rec(1L, 3, 31L),
            ),
        ))
        assertEquals(1, plan.actions.size)
        val a = plan.actions.single()
        assertTrue(a is RatchetAction.SeasonSearch)
        assertEquals(1L, a.seriesId)
        assertEquals(1, (a as RatchetAction.SeasonSearch).seasonNumber)
        // First-ever attempt → row written with consecutive=0, count=2
        val w = plan.cooldownWrites.single()
        assertEquals(2, w.lastMissingCount)
        assertEquals(0, w.consecutiveEmptyAttempts)
    }

    @Test fun season_in_cooldown_skipped_picker_advances() {
        val plan = buildP5RatchetPlan(inputs(
            records = listOf(rec(1L, 1, 11L), rec(1L, 2, 21L)),
            cooldowns = listOf(attempt(1L, 1, lastAttemptedAt = hoursAgo(2))), // S1 still cooling
        ))
        val a = plan.actions.single() as RatchetAction.SeasonSearch
        assertEquals(2, a.seasonNumber)
    }

    @Test fun all_seasons_in_cooldown_emits_no_action() {
        val plan = buildP5RatchetPlan(inputs(
            records = listOf(rec(1L, 1, 11L), rec(1L, 2, 21L)),
            cooldowns = listOf(
                attempt(1L, 1, lastAttemptedAt = hoursAgo(2)),
                attempt(1L, 2, lastAttemptedAt = hoursAgo(2)),
            ),
        ))
        assertTrue(plan.actions.isEmpty())
        assertTrue(plan.cooldownWrites.isEmpty())
    }

    // ---------- escalation ladder ----------

    @Test fun no_progress_no_queue_increments_to_seasonSearch_retry() {
        val plan = buildP5RatchetPlan(inputs(
            records = listOf(rec(1L, 1, 11L), rec(1L, 1, 12L), rec(1L, 1, 13L)),
            cooldowns = listOf(attempt(1L, 1, lastAttemptedAt = hoursAgo(25), lastMissingCount = 3, consecutive = 0)),
        ))
        val a = plan.actions.single()
        assertTrue(a is RatchetAction.SeasonSearch, "counter=1 → SeasonSearch retry")
        val w = plan.cooldownWrites.single()
        assertEquals(1, w.consecutiveEmptyAttempts)
    }

    @Test fun counter_2_escalates_to_episodeSearch() {
        val plan = buildP5RatchetPlan(inputs(
            records = listOf(rec(1L, 1, 11L), rec(1L, 1, 12L)),
            cooldowns = listOf(attempt(1L, 1, lastAttemptedAt = hoursAgo(25), lastMissingCount = 2, consecutive = 1)),
        ))
        val a = plan.actions.single()
        assertTrue(a is RatchetAction.EpisodeSearch)
        assertEquals(setOf(11L, 12L), (a as RatchetAction.EpisodeSearch).episodeIds.toSet())
        assertEquals(2, plan.cooldownWrites.single().consecutiveEmptyAttempts)
    }

    @Test fun progress_resets_counter_to_zero() {
        val plan = buildP5RatchetPlan(inputs(
            records = listOf(rec(1L, 1, 11L)),  // 1 missing now
            cooldowns = listOf(attempt(1L, 1, lastAttemptedAt = hoursAgo(25), lastMissingCount = 5, consecutive = 3)),
        ))
        val a = plan.actions.single()
        assertTrue(a is RatchetAction.SeasonSearch, "counter reset → fresh SeasonSearch")
        assertEquals(0, plan.cooldownWrites.single().consecutiveEmptyAttempts)
    }

    @Test fun queue_presence_resets_counter_even_without_missing_drop() {
        val plan = buildP5RatchetPlan(inputs(
            records = listOf(rec(1L, 1, 11L), rec(1L, 1, 12L), rec(1L, 1, 13L)),
            queueHits = setOf(QueueSeasonHit(1L, 1)),
            cooldowns = listOf(attempt(1L, 1, lastAttemptedAt = hoursAgo(25), lastMissingCount = 3, consecutive = 4)),
        ))
        assertEquals(0, plan.cooldownWrites.single().consecutiveEmptyAttempts)
    }

    @Test fun threshold_reached_skips_into_long_cooldown() {
        // counter == threshold = 5; lastAttempt 25h ago → still inside long cooldown (168h)
        val plan = buildP5RatchetPlan(inputs(
            records = listOf(rec(1L, 1, 11L), rec(1L, 2, 21L)),
            cooldowns = listOf(attempt(1L, 1, lastAttemptedAt = hoursAgo(25), lastMissingCount = 1, consecutive = 5)),
        ))
        val a = plan.actions.single() as RatchetAction.SeasonSearch
        assertEquals(2, a.seasonNumber, "S1 in long cooldown → advance to S2")
    }

    @Test fun threshold_reached_long_cooldown_elapsed_runs_episodeSearch_again() {
        val plan = buildP5RatchetPlan(inputs(
            records = listOf(rec(1L, 1, 11L)),
            cooldowns = listOf(attempt(1L, 1, lastAttemptedAt = hoursAgo(200), lastMissingCount = 1, consecutive = 5)),
        ))
        // Long cooldown elapsed (200h > 168h); counter still high → EpisodeSearch
        val a = plan.actions.single()
        assertTrue(a is RatchetAction.EpisodeSearch)
    }

    // ---------- ordering + budget ----------

    @Test fun budget_caps_actions() {
        val plan = buildP5RatchetPlan(inputs(
            records = (1L..15L).map { rec(it, 1, it * 10) },
            budget = 5,
        ))
        assertEquals(5, plan.actions.size)
    }

    @Test fun one_season_per_series_per_sweep() {
        val plan = buildP5RatchetPlan(inputs(
            records = listOf(
                rec(1L, 1, 11L), rec(1L, 2, 21L), rec(1L, 3, 31L),
                rec(2L, 1, 12L), rec(2L, 2, 22L),
            ),
        ))
        assertEquals(2, plan.actions.size)
        // Each action is for a distinct series
        assertEquals(setOf(1L, 2L), plan.actions.map { it.seriesId }.toSet())
    }

    // ---------- specials ----------

    @Test fun specials_excluded_by_default() {
        val plan = buildP5RatchetPlan(inputs(
            records = listOf(rec(1L, 0, 1L)),
        ))
        assertTrue(plan.actions.isEmpty())
    }

    @Test fun specials_included_sorts_after_numbered_seasons() {
        val plan = buildP5RatchetPlan(inputs(
            records = listOf(rec(1L, 0, 1L), rec(1L, 2, 22L)),
            cfgOverride = cfg.copy(includeSpecials = true),
        ))
        val a = plan.actions.single() as RatchetAction.SeasonSearch
        assertEquals(2, a.seasonNumber, "Numbered seasons before specials")
    }

    @Test fun specials_only_with_includeSpecials_picks_S0() {
        val plan = buildP5RatchetPlan(inputs(
            records = listOf(rec(1L, 0, 1L)),
            cfgOverride = cfg.copy(includeSpecials = true),
        ))
        val a = plan.actions.single() as RatchetAction.SeasonSearch
        assertEquals(0, a.seasonNumber)
    }

    // ---------- per-series isolation ----------

    @Test fun series_X_progress_does_not_reset_series_Y_counter() {
        val plan = buildP5RatchetPlan(inputs(
            records = listOf(rec(1L, 1, 11L), rec(2L, 1, 21L), rec(2L, 1, 22L)),
            cooldowns = listOf(
                attempt(2L, 1, lastAttemptedAt = hoursAgo(25), lastMissingCount = 2, consecutive = 0),
            ),
        ))
        // Series 2 row exists with count=2 last time, count is still 2 now → counter increments
        val s2 = plan.cooldownWrites.first { it.seriesId == 2L }
        assertEquals(1, s2.consecutiveEmptyAttempts)
    }
}
```

- [ ] **Step 3: Run tests, expect FAIL (TODO)**

```
cd D:/docker/prioritarr/prioritarr ; ./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.sweep.P5RatchetTest"
```
Expected: FAIL with "kotlin.NotImplementedError: implement in next steps".

- [ ] **Step 4: Implement `buildP5RatchetPlan`**

Replace the `TODO()` body in `P5Ratchet.kt`:

```kotlin
fun buildP5RatchetPlan(inputs: P5RatchetInputs): P5RatchetPlan {
    if (!inputs.ratchetActive) {
        // Bandwidth headroom: today's behaviour, expressed as
        // SeriesSearch per series in oldest-air-date order. Up to budget.
        val byOldest = inputs.p5Records
            .groupBy { it.seriesId }
            .mapValues { (_, recs) -> recs.minOf { it.airDateUtc } }
        val ordered = byOldest.entries.sortedBy { it.value }.map { it.key }
        val actions = ordered.take(inputs.budgetRemaining)
            .map { RatchetAction.SeriesSearch(it) as RatchetAction }
        return P5RatchetPlan(actions = actions, cooldownWrites = emptyList())
    }

    val cooldownIndex = inputs.cooldownRows
        .associateBy { it.seriesId to it.seasonNumber }

    // Group missing records by series → season → list-of-records
    val bySeries: Map<Long, Map<Int, List<MissingRecord>>> = inputs.p5Records
        .groupBy { it.seriesId }
        .mapValues { (_, recs) -> recs.groupBy { it.seasonNumber } }

    // Series order: oldest air date first (consistent with the ratchet-off path)
    val seriesOrder = bySeries.entries.sortedBy { (_, seasons) ->
        seasons.values.flatten().minOf { it.airDateUtc }
    }.map { it.key }

    val cooldownSeconds = inputs.cfg.searchCooldownHours * 3600L
    val longCooldownSeconds = inputs.cfg.longCooldownHours * 3600L
    val now = inputs.nowEpochSeconds

    val actions = mutableListOf<RatchetAction>()
    val writes = mutableListOf<P5Attempt>()
    var budget = inputs.budgetRemaining

    seriesLoop@ for (seriesId in seriesOrder) {
        if (budget == 0) break
        val seasonsMap = bySeries[seriesId] ?: continue

        // Sort seasons: numbered ascending; specials (0) last when included; excluded otherwise
        val seasonOrder = seasonsMap.keys
            .filter { inputs.cfg.includeSpecials || it != 0 }
            .sortedWith(compareBy(
                { if (it == 0) 1 else 0 },  // 0=specials sort after numbered
                { it },
            ))

        for (season in seasonOrder) {
            val key = seriesId to season
            val prev = cooldownIndex[key]
            val missingNow = seasonsMap[season]!!.size
            val queuePresent = QueueSeasonHit(seriesId, season) in inputs.queueHits

            // ---- short cooldown gate ----
            if (prev != null && prev.lastAttemptedAt + cooldownSeconds > now) continue

            // ---- compute next counter (progress detection) ----
            val nextCounter = when {
                prev == null -> 0
                prev.lastMissingCount == null -> 0
                missingNow < prev.lastMissingCount || queuePresent -> 0
                else -> prev.consecutiveEmptyAttempts + 1
            }

            // ---- long cooldown gate ----
            if (nextCounter >= inputs.cfg.escalationThreshold &&
                prev != null &&
                prev.lastAttemptedAt + longCooldownSeconds > now
            ) continue

            // ---- pick strategy ----
            val action: RatchetAction = when {
                nextCounter == 0 -> RatchetAction.SeasonSearch(seriesId, season)
                nextCounter == 1 -> RatchetAction.SeasonSearch(seriesId, season)
                else -> RatchetAction.EpisodeSearch(
                    seriesId = seriesId,
                    seasonNumber = season,
                    episodeIds = seasonsMap[season]!!.map { it.episodeId },
                )
            }
            actions += action
            writes += P5Attempt(
                seriesId = seriesId,
                seasonNumber = season,
                lastAttemptedAt = now,
                lastMissingCount = missingNow,
                consecutiveEmptyAttempts = nextCounter,
            )
            budget--
            continue@seriesLoop  // one season per series per sweep
        }
    }

    return P5RatchetPlan(actions = actions, cooldownWrites = writes)
}
```

- [ ] **Step 5: Run tests — all should pass**

```
cd D:/docker/prioritarr/prioritarr ; ./gradlew :backend:test --tests "org.yoshiz.app.prioritarr.backend.sweep.P5RatchetTest"
```
Expected: PASS.

- [ ] **Step 6: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/sweep/P5Ratchet.kt \
        prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/sweep/P5RatchetTest.kt
git commit -m "feat(p5-ratchet): pure season picker buildP5RatchetPlan + tests"
```

---

### Task 16: Runner — execute the plan against Sonarr + DB

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/.../sweep/P5Ratchet.kt`

- [ ] **Step 1: Add the runner**

Append to `P5Ratchet.kt`:

```kotlin
/**
 * Execute a [P5RatchetPlan] against Sonarr + persist the cooldown
 * writes. Returns the number of search commands actually fired —
 * caller subtracts from the shared sweep budget.
 *
 * Plan execution is best-effort: a Sonarr error on action N stops the
 * loop and leaves cooldownWrites for unpicked seasons un-applied.
 * That's intentional — failing fast lets the next sweep retry without
 * polluting the cooldown table with attempts that may not have
 * actually hit indexers.
 */
suspend fun runP5SeasonRatchet(
    plan: P5RatchetPlan,
    sonarr: org.yoshiz.app.prioritarr.backend.clients.SonarrClient,
    db: Database,
    delaySeconds: Int,
    dryRun: Boolean,
): Int {
    if (plan.actions.isEmpty()) return 0
    var fired = 0
    val logger = org.slf4j.LoggerFactory.getLogger("p5-ratchet")
    var seasonSearches = 0
    var episodeSearches = 0
    var seriesSearches = 0
    val firstWritesByAction = plan.actions.zip(plan.cooldownWrites).toMap()

    for (action in plan.actions) {
        val attemptStrategy = when (action) {
            is RatchetAction.SeasonSearch -> "season"
            is RatchetAction.EpisodeSearch -> "episode"
            is RatchetAction.SeriesSearch -> "series"
        }
        if (dryRun) {
            logger.info("[p5-ratchet] DRY RUN: would {} for series {} action={}",
                attemptStrategy, action.seriesId, action::class.simpleName)
        } else {
            try {
                when (action) {
                    is RatchetAction.SeasonSearch ->
                        sonarr.triggerSeasonSearch(action.seriesId, action.seasonNumber).also { seasonSearches++ }
                    is RatchetAction.EpisodeSearch ->
                        sonarr.triggerEpisodeSearch(action.episodeIds).also { episodeSearches++ }
                    is RatchetAction.SeriesSearch ->
                        sonarr.triggerSeriesSearch(action.seriesId).also { seriesSearches++ }
                }
            } catch (e: Exception) {
                logger.warn("[p5-ratchet] {} for series {} failed: {}", attemptStrategy, action.seriesId, e.message)
                break
            }
            firstWritesByAction[action]?.let { write ->
                db.upsertP5Attempt(
                    seriesId = write.seriesId,
                    seasonNumber = write.seasonNumber,
                    lastAttemptedAt = write.lastAttemptedAt,
                    lastMissingCount = write.lastMissingCount,
                    consecutiveEmptyAttempts = write.consecutiveEmptyAttempts,
                )
            }
            db.appendAudit(
                action = "p5_ratchet_search",
                seriesId = action.seriesId,
                details = kotlinx.serialization.json.buildJsonObject {
                    kotlinx.serialization.json.put("strategy", attemptStrategy)
                    when (action) {
                        is RatchetAction.SeasonSearch ->
                            kotlinx.serialization.json.put("season_number", action.seasonNumber)
                        is RatchetAction.EpisodeSearch -> {
                            kotlinx.serialization.json.put("season_number", action.seasonNumber)
                            kotlinx.serialization.json.put("episode_count", action.episodeIds.size)
                        }
                        is RatchetAction.SeriesSearch -> { /* nothing extra */ }
                    }
                },
            )
            if (delaySeconds > 0) kotlinx.coroutines.delay(delaySeconds * 1_000L)
        }
        fired++
    }
    logger.info("[p5-ratchet] sweep: {} actions ({} season + {} episode + {} series)",
        fired, seasonSearches, episodeSearches, seriesSearches)
    return fired
}
```

- [ ] **Step 2: Build to verify**

```
cd D:/docker/prioritarr/prioritarr ; ./gradlew :backend:compileKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/sweep/P5Ratchet.kt
git commit -m "feat(p5-ratchet): runner executes plan against Sonarr + persists cooldowns"
```

---

### Task 17: Wire Pass A + Pass B into `runBackfillSweep`

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/.../sweep/Sweep.kt`
- Modify: `prioritarr/backend/src/main/kotlin/.../Main.kt` (sweep job wiring)

- [ ] **Step 1: Refactor `runBackfillSweep` to accept the new dependencies**

In `Sweep.kt`, replace the existing `runBackfillSweep` with a two-pass version. The simplest shape:

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
): Int {
    // ---- Pass A: P1-P4 today's behaviour ----
    val records = try { sonarr.getWantedMissing() } catch (e: Exception) {
        logger.warn("[backfill] fetch failed: {}", e.message); return 0
    }
    if (records.isEmpty()) {
        logger.info("[backfill] nothing to search")
        return 0
    }
    val order = buildSweepOrder(records, priorityService)
    logger.info("[backfill] {} records across {} series (max {})", records.size, order.size, maxSearches)
    var fired = 0
    val passARemaining = order.filter { it.priority < 5 }
    for (entry in passARemaining) {
        if (fired >= maxSearches) {
            logger.info("[backfill] hit max searches ({}), deferring rest", maxSearches)
            return fired
        }
        if (dryRun) {
            logger.info("[backfill] DRY RUN: would search series {} ({})", entry.seriesId, entry.label)
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

    // ---- Pass B: P5 ratchet ----
    if (fired >= maxSearches) return fired
    val p5Records = order.filter { it.priority == 5 }
    if (p5Records.isEmpty()) return fired

    val ratchetActive = p5Ratchet.enabled && run {
        if (bandwidth.maxMbps <= 0) return@run false
        val totalBps = telemetry?.observedPeakTotalBps() ?: 0L
        val ratchetBandwidth = p5Ratchet.bandwidthThresholdPct?.let {
            bandwidth.copy(utilisationThresholdPct = it)
        } ?: bandwidth
        org.yoshiz.app.prioritarr.backend.enforcement.BandwidthPolicy
            .utilisationExceedsThreshold(ratchetBandwidth, totalBps)
    }

    val planInputs = P5RatchetInputs(
        p5Records = p5Records.flatMap { sweepEntry ->
            // Each SweepEntry only carries one series + oldestAirDate; the
            // raw Sonarr records have one row per missing episode. Re-walk
            // the raw `records` JsonArray for P5-priority entries.
            records.mapNotNull { el ->
                val o = el.jsonObject
                val sid = o["seriesId"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                if (sid != sweepEntry.seriesId) return@mapNotNull null
                val season = o["seasonNumber"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val episodeId = o["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                val air = o["airDateUtc"]?.jsonPrimitive?.contentOrNull ?: "9999"
                MissingRecord(sid, season, episodeId, air, priority = 5)
            }
        }.distinctBy { it.episodeId },
        queueHits = run {
            val q = try { sonarr.getQueue() } catch (_: Exception) { JsonArray(emptyList()) }
            q.mapNotNull {
                val o = it.jsonObject
                val sid = o["seriesId"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                val season = o["episode"]?.jsonObject?.get("seasonNumber")?.jsonPrimitive?.intOrNull
                    ?: o["seasonNumber"]?.jsonPrimitive?.intOrNull
                    ?: return@mapNotNull null
                QueueSeasonHit(sid, season)
            }.toSet()
        },
        cooldownRows = db.listP5Attempts(),
        nowEpochSeconds = System.currentTimeMillis() / 1000L,
        cfg = p5Ratchet,
        budgetRemaining = maxSearches - fired,
        ratchetActive = ratchetActive,
    )
    val plan = buildP5RatchetPlan(planInputs)
    fired += runP5SeasonRatchet(
        plan = plan, sonarr = sonarr, db = db,
        delaySeconds = delaySeconds, dryRun = dryRun,
    )
    return fired
}
```

You'll need imports at the top of `Sweep.kt`:
```kotlin
import kotlinx.serialization.json.intOrNull
```

- [ ] **Step 2: Update Main.kt's BACKFILL_SWEEP job wiring**

Find `JobId.BACKFILL_SWEEP` in Main.kt and update its `run` block:

```kotlin
                run = {
                    val s = liveSettings(db, settings)
                    org.yoshiz.app.prioritarr.backend.sweep.runBackfillSweep(
                        sonarr = sonarr,
                        priorityService = priorityService,
                        db = db,
                        p5Ratchet = s.p5Ratchet,
                        bandwidth = liveBandwidth(db, settings),
                        telemetry = downloadTelemetry,
                        maxSearches = s.intervals.backfillMaxSearchesPerSweep,
                        delaySeconds = s.intervals.backfillDelayBetweenSearchesSeconds,
                        dryRun = s.dryRun,
                    )
                    org.yoshiz.app.prioritarr.backend.scheduler.JobOutcome()
                },
```

- [ ] **Step 3: Build + run all tests**

```
cd D:/docker/prioritarr/prioritarr ; ./gradlew :backend:test
```
Expected: PASS.

- [ ] **Step 4: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/sweep/Sweep.kt \
        prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/Main.kt
git commit -m "feat(p5-ratchet): backfill sweep splits into Pass A + Pass B"
```

---

## Phase 8 — Settings UI surface (backend route)

### Task 18: `/api/v2/settings/p5-ratchet` route

**Files:**
- Modify: `prioritarr/backend/src/main/kotlin/.../api/v2/V2Routes.kt`
- Modify: `prioritarr/backend/src/main/kotlin/.../schemas/V2.kt` (request/response types)

- [ ] **Step 1: Add request/response DTOs to `V2.kt`**

In `schemas/V2.kt`, near the existing `BandwidthSettings` request DTO:

```kotlin
@kotlinx.serialization.Serializable
data class P5RatchetRequest(
    val enabled: Boolean,
    val searchCooldownHours: Int,
    val longCooldownHours: Int,
    val escalationThreshold: Int,
    val includeSpecials: Boolean,
    val bandwidthThresholdPct: Double?,
)

@kotlinx.serialization.Serializable
data class P5RatchetResponse(
    val enabled: Boolean,
    val searchCooldownHours: Int,
    val longCooldownHours: Int,
    val escalationThreshold: Int,
    val includeSpecials: Boolean,
    val bandwidthThresholdPct: Double?,
    /** Real-time signal: would the ratchet be active right now? */
    val ratchetWouldBeActive: Boolean,
    val currentUtilisationPct: Double,
)
```

- [ ] **Step 2: Add the route handlers in V2Routes.kt**

In `V2Routes.kt`, mirror the bandwidth-settings route block. Add:

```kotlin
        get("/settings/p5-ratchet") {
            val s = liveSettings(db, baseSettings).p5Ratchet
            val bandwidth = liveBandwidth(db, baseSettings)
            val totalBps = telemetry?.observedPeakTotalBps() ?: 0L
            val capBps = org.yoshiz.app.prioritarr.backend.enforcement.BandwidthPolicy
                .effectiveCapBps(bandwidth)
            val util = if (capBps > 0) totalBps.toDouble() / capBps else 0.0
            val ratchetBw = s.bandwidthThresholdPct?.let { bandwidth.copy(utilisationThresholdPct = it) } ?: bandwidth
            val active = s.enabled &&
                org.yoshiz.app.prioritarr.backend.enforcement.BandwidthPolicy
                    .utilisationExceedsThreshold(ratchetBw, totalBps)
            call.respond(P5RatchetResponse(
                enabled = s.enabled,
                searchCooldownHours = s.searchCooldownHours,
                longCooldownHours = s.longCooldownHours,
                escalationThreshold = s.escalationThreshold,
                includeSpecials = s.includeSpecials,
                bandwidthThresholdPct = s.bandwidthThresholdPct,
                ratchetWouldBeActive = active,
                currentUtilisationPct = util,
            ))
        }

        put("/settings/p5-ratchet") {
            val req = call.receive<P5RatchetRequest>()
            val cfg = org.yoshiz.app.prioritarr.backend.config.P5RatchetConfig(
                enabled = req.enabled,
                searchCooldownHours = req.searchCooldownHours,
                longCooldownHours = req.longCooldownHours,
                escalationThreshold = req.escalationThreshold,
                includeSpecials = req.includeSpecials,
                bandwidthThresholdPct = req.bandwidthThresholdPct,
            )
            db.setP5RatchetOverride(
                kotlinx.serialization.json.Json.encodeToString(
                    org.yoshiz.app.prioritarr.backend.config.P5RatchetConfig.serializer(), cfg))
            call.respond(io.ktor.http.HttpStatusCode.NoContent)
        }

        delete("/settings/p5-ratchet") {
            db.clearP5RatchetOverride()
            call.respond(io.ktor.http.HttpStatusCode.NoContent)
        }
```

You'll also need to update `liveSettings` (or wherever the override merge happens) to deserialize `getP5RatchetOverride()` payload onto the baseline. Search for `getBandwidthOverride` to find the pattern; mirror it for `p5_ratchet`.

- [ ] **Step 3: Build + run tests**

```
cd D:/docker/prioritarr/prioritarr ; ./gradlew :backend:test
```
Expected: PASS.

- [ ] **Step 4: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/api/v2/V2Routes.kt \
        prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/schemas/V2.kt
git commit -m "feat(api): /api/v2/settings/p5-ratchet GET/PUT/DELETE"
```

---

## Phase 9 — Frontend (Settings panel + drawer tile)

### Task 19: Frontend integration

**Files (all React/TS):**
- Modify: `prioritarr/frontend/src/pages/Settings/PrioritySettings.tsx` (or whichever sub-page hosts priority knobs — search the frontend for "Priority thresholds" to find the file)
- Modify: `prioritarr/frontend/src/components/SeriesDrawer/PriorityWhy.tsx` (or wherever drawer tiles are composed — search for "Why this priority")
- Modify: API client TS (search for `bandwidth-settings` in the frontend to find the matching pattern)

- [ ] **Step 1: Find the existing Settings → Priority sub-page**

```
cd D:/docker/prioritarr/prioritarr/frontend ; grep -rn "Priority thresholds" src
```
Note the file path. Open it; locate where a sub-section block is rendered (e.g., the bandwidth settings sub-section).

- [ ] **Step 2: Add the API client function**

In the frontend's API client (search for `getBandwidthSettings` to find the right file):

```typescript
export type P5RatchetResponse = {
  enabled: boolean;
  searchCooldownHours: number;
  longCooldownHours: number;
  escalationThreshold: number;
  includeSpecials: boolean;
  bandwidthThresholdPct: number | null;
  ratchetWouldBeActive: boolean;
  currentUtilisationPct: number;
};

export async function getP5RatchetSettings(): Promise<P5RatchetResponse> {
  const res = await apiFetch("/api/v2/settings/p5-ratchet");
  return res.json();
}

export async function putP5RatchetSettings(cfg: Omit<P5RatchetResponse, "ratchetWouldBeActive" | "currentUtilisationPct">): Promise<void> {
  await apiFetch("/api/v2/settings/p5-ratchet", { method: "PUT", body: JSON.stringify(cfg) });
}

export async function resetP5RatchetSettings(): Promise<void> {
  await apiFetch("/api/v2/settings/p5-ratchet", { method: "DELETE" });
}
```

- [ ] **Step 3: Add the Settings sub-section component**

In the Priority Settings page, alongside the bandwidth sub-section, add a new collapsible / sectioned block "P5 backfill ratchet" with form inputs for each field plus a live preview line "Current bandwidth utilisation: XX.X%; ratchet would currently be: ACTIVE/INACTIVE". Reuse the existing form-row primitives (number-input row, boolean-toggle row) from the surrounding code — don't introduce new UI primitives.

The exact JSX is straightforward; mirror the bandwidth sub-section's structure.

- [ ] **Step 4: Add the Series drawer tile**

In the drawer component (e.g., `PriorityWhy.tsx`), add a conditional block — only renders when:
- `series.priority === 5` AND
- `p5Ratchet.enabled === true`

The tile shows the data backed by a new endpoint `/api/v2/series/:id/p5-ratchet` that returns the per-(series, season) cooldown rows + missing-counts. Add that endpoint mirroring step 2 of Task 18 in `V2Routes.kt`. (Lean on the existing `db.listP5AttemptsForSeries(seriesId)` and Sonarr `/wanted/missing` per-series filter.)

- [ ] **Step 5: Manual smoke test in browser**

Start the dev environment per the project's README. Navigate to Settings → Priority → P5 backfill ratchet; toggle enabled, see the values persist. Open a P5 series drawer; see the tile render only when ratchet is enabled.

- [ ] **Step 6: Commit**

```
git add prioritarr/frontend/src
git commit -m "feat(ui): P5 ratchet Settings sub-section + Series drawer tile"
```

---

## Phase 10 — Cleanup

### Task 20: Migrate legacy enforcement test, retire `computeQBitPauseActions`

**Files:**
- Modify: `prioritarr/backend/src/test/kotlin/.../enforcement/EnforcementTest.kt`
- Modify: `prioritarr/backend/src/main/kotlin/.../enforcement/Enforcement.kt`
- Modify: `prioritarr/backend/src/main/kotlin/.../reconcile/Reconcile.kt`

- [ ] **Step 1: Verify no live callers of `computeQBitPauseActions` remain**

```
cd D:/docker/prioritarr/prioritarr ; grep -rn "computeQBitPauseActions" backend/src
```
Expected: matches in `EnforcementTest.kt` and `Enforcement.kt` (definition) and possibly the legacy `applyQbitEnforcement` if not yet removed in Task 14. If any production-path matches remain, route them through `computeEnforcement` first.

- [ ] **Step 2: Remove `applyQbitEnforcement` and `applySabEnforcement` from `Reconcile.kt`**

Both helpers are now superseded by `reconcileAll` + the per-client `applyEnforcement`. Delete them.

- [ ] **Step 3: Migrate `EnforcementTest.kt` cases to `computeEnforcement`**

Each `EnforcementTest` case has a parity test in `ComputeEnforcementTest`. Delete the legacy file:

```
rm prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/enforcement/EnforcementTest.kt
```

(If a case in `EnforcementTest` lacks coverage in `ComputeEnforcementTest`, port it over before deleting.)

- [ ] **Step 4: Delete `computeQBitPauseActions` and `QBitAction`/`QBitDownloadView`/`EnforcementContext` from `Enforcement.kt`**

Replace the entire content of `Enforcement.kt` with just the `computeSabPriority` helper, since the rest is now in `EnforcementModel.kt` + `ComputeEnforcement.kt`:

```kotlin
package org.yoshiz.app.prioritarr.backend.enforcement

import org.yoshiz.app.prioritarr.backend.clients.SABClient

/** Map internal P1..P5 → SAB priority value. */
fun computeSabPriority(prioritarrLevel: Int): Int =
    SABClient.PRIORITY_MAP[prioritarrLevel] ?: 0
```

- [ ] **Step 5: Build + run all tests**

```
cd D:/docker/prioritarr/prioritarr ; ./gradlew :backend:test
```
Expected: PASS. Any compile error indicates a stray reference to a deleted type — chase it down.

- [ ] **Step 6: Commit**

```
git add prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/enforcement/Enforcement.kt \
        prioritarr/backend/src/main/kotlin/org/yoshiz/app/prioritarr/backend/reconcile/Reconcile.kt \
        prioritarr/backend/src/test/kotlin/org/yoshiz/app/prioritarr/backend/enforcement/EnforcementTest.kt
git commit -m "refactor(enforcement): retire computeQBitPauseActions + applyQbitEnforcement"
```

---

## Final verification

- [ ] **Run the full test suite**

```
cd D:/docker/prioritarr/prioritarr ; ./gradlew :backend:test
```
Expected: PASS — all new + migrated tests green.

- [ ] **Smoke-test in DRY_RUN against a live deployment**

1. Set `PRIORITARR_DRY_RUN=true`, restart the container.
2. Confirm the backfill sweep logs include `[p5-ratchet] DRY RUN: would …` lines after Pass A.
3. Open Settings → Priority → P5 backfill ratchet; toggle enabled, save, verify the live preview updates.
4. Open a P5 series drawer; confirm the tile renders only when ratchet is enabled.

- [ ] **Flip to live**

Set `PRIORITARR_DRY_RUN=false`. Watch the audit log for one or two sweep cycles to confirm `p5_ratchet_search` entries appear with sensible season numbers, and `p5_ratchet_defer` entries appear when bandwidth is contended.

If anything looks wrong, set `p5_ratchet.enabled = false` via the Settings UI — the system reverts on the next tick. No restart needed, no DB rollback to do.
