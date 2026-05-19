# Sonarr Resilience + Indexer Health + P3/P4 Episode-Search Design

**Date:** 2026-05-19
**Branch:** `feat/provider-health-banner` (or successor)
**Status:** Approved for implementation planning

---

## Problem

Three observed pain points, addressed together because they reinforce each other:

1. **Sonarr's command queue can jam.** Long-running `SeriesSearch` commands (one per P3/P4 series) pile up behind each other when indexers are slow. The single-threaded `ProcessMonitoredDownloads` task that auto-imports completed downloads gets stuck behind them. Result: files downloaded by qBit/SAB sit on disk for hours without being imported, even though everything else is "working." Sonarr's API cannot cancel started commands (returns 409); only a restart aborts them.

2. **Slow / dead indexers compound the jam.** Anidex and EZTV were unhealthy for >6 hours in the recent incident. Every search waits for them to time out, multiplying the time per command. Sonarr has its own short-window auto-disable but no long-window backoff — once Sonarr's window closes the indexer is re-enabled and the cycle repeats.

3. **`SeriesSearch` is heavier than it needs to be for P3/P4.** Each `SeriesSearch` scans every monitored episode of a series against every indexer. P3/P4 series can have hundreds of episodes. Task 7 already moved P1/P2 to per-episode searches with a 5-episode cap; P3/P4 still use the old heavyweight command and contribute most of the jam pressure.

## Goal

Detect command-queue jams automatically, mitigate them with an escalating series of actions (clear queued → fire import → restart Sonarr as opt-in last resort), prevent them from forming by (a) capping concurrent prioritarr-triggered `SeriesSearch` commands and (b) migrating P3/P4 to per-episode searches, and (c) proactively manage indexer health with auto-disable + exponential-backoff probe-and-retry.

## Non-goals

- Changing P5 ratchet behaviour (P5 inactive-mode fallback continues to use `SeriesSearch`; the concurrent-search cap in Subsystem A throttles it).
- Tracking Sonarr health metrics beyond the command queue (general Sonarr observability is out of scope; we focus on the specific jam pattern).
- Prowlarr-side health probes (we read Sonarr's `/api/v3/indexerstatus`, which already reflects Prowlarr-managed indexers).
- A "deep-backfill mode" for P4 series with many missing episodes (P4 stays episode-search-only with `perSeriesCap=5`).
- Auto-tuning the indexer-backoff schedule based on past recovery patterns.

---

## Architecture overview

Three subsystems, all sharing one new Sonarr API client surface.

```
                Sonarr (HTTP API client extension)
                ───────────────────────────────────
       listCommands()  cancelCommand(id)  fireCommand(name, payload)
       systemRestart()  listIndexers()  indexerStatus()  toggleIndexer(id, enabled)
                ▲              ▲              ▲
                │              │              │
   ┌────────────┴────┐   ┌─────┴────────┐   ┌─┴──────────────┐
   │  Subsystem A    │   │ Subsystem B  │   │  Subsystem C   │
   │  Jam Guard      │   │ Indexer      │   │  P3/P4 →       │
   │                 │   │ Health Guard │   │  EpisodeSearch │
   │  every 5 min:   │   │              │   │                │
   │  - detect stuck │   │ every 30 min:│   │  modifies      │
   │    SeriesSearch │   │ - probe      │   │  Pass A2 of    │
   │  - cancel       │   │   indexer    │   │  runBackfill   │
   │    queued       │   │   status     │   │  Sweep         │
   │  - fire Process │   │ - auto-      │   │                │
   │    Monitored…   │   │   disable    │   │  reuses        │
   │  - opt-in       │   │   >6h        │   │  buildPriority │
   │    restart      │   │ - probe-and- │   │  Candidates    │
   │                 │   │   retry 2/4/ │   │  planner       │
   │                 │   │   6/12/24h   │   │                │
   └─────────────────┘   └──────────────┘   └────────────────┘
        │                       │                   │
        ▼                       ▼                   ▼
   sonarr_jam_       indexer_health_         priority_episode_
   interventions     history                 attempts (renamed
   (new table)       (new table)             from p1p2_search_attempts)
```

**Reinforcing dynamic:** Subsystem B prevents the upstream condition (slow indexers) that causes long searches. Subsystem C shrinks each search command. Subsystem A catches what slips through. All three can be individually disabled via a master toggle without breaking the others.

---

## Subsystem A — Sonarr Jam Guard

### New Sonarr client surface

```kotlin
// clients/Sonarr.kt
suspend fun listCommands(): JsonArray                       // GET  /api/v3/command
suspend fun cancelCommand(commandId: Long): Boolean         // DELETE /api/v3/command/{id}; true on 200, false on 409/404
suspend fun fireProcessMonitoredDownloads(): JsonObject     // POST /api/v3/command { name: "ProcessMonitoredDownloads" }
suspend fun systemRestart(): Boolean                        // POST /api/v3/system/restart; true on 200
```

`listCommands` is cached in-process for **30 seconds** to keep the concurrent-search-cap check cheap.

### Detection

A pure function `JamDetector.detect(commands: JsonArray, now: Long): JamVerdict` in `resilience/JamDetector.kt`:

```kotlin
sealed interface JamVerdict {
    object Healthy : JamVerdict
    data class Jammed(
        val stuckSeriesSearchIds: List<Long>,      // started > sonarrJamSearchAgeMinutes ago
        val queuedSeriesSearchIds: List<Long>,     // not started, SeriesSearch | MissingEpisodeSearch
        val importBlockedSince: Long?,             // epoch sec of oldest ProcessMonitoredDownloads queued > threshold, null if none
        val oldestStartedAt: Long,
    ) : JamVerdict
}
```

Sonarr `/api/v3/command` rows include `name`, `status` (`queued` | `started` | `completed` | `failed`), `started` (ISO), `queued` (ISO). The detector parses ISO timestamps, filters to `SeriesSearch` and `MissingEpisodeSearch` types, splits started-vs-queued.

Thresholds:
- `sonarrJamSearchAgeMinutes` default **30** — any started SeriesSearch/MissingEpisodeSearch older than this is "stuck"
- `sonarrJamImportBlockedMinutes` default **10** — ProcessMonitoredDownloads queued this long means imports are blocked

### Mitigation state machine

Persisted in a new table:

```sql
CREATE TABLE IF NOT EXISTS sonarr_jam_interventions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    attempted_at INTEGER NOT NULL,           -- epoch sec
    verdict TEXT NOT NULL,                   -- "jammed" | "healthy"
    action TEXT NOT NULL,                    -- "soft" | "restart" | "await_user_restart" | "cleared" | "noop"
    outcome TEXT,                            -- "ok" | "failed" | textual detail
    cancelled_count INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_jam_attempted_at ON sonarr_jam_interventions(attempted_at DESC);
```

Runner `JamGuard.runJamGuard(sonarr, db, settings, now)` in `resilience/JamGuard.kt`:

```
verdict = detect(listCommands(), now)
last    = db.lastJamIntervention()

if verdict is Healthy:
    if last exists AND last.action != "cleared":
        db.insertJamIntervention(now, "healthy", "cleared", "ok", 0)
    return Healthy

// Jammed. Escalate based on how recent the last attempt was.
val lastWindow = (last == null) || (now - last.attempted_at > 30 min)

if lastWindow:
    // Step 1: soft mitigation.
    val cancelled = verdict.queuedSeriesSearchIds.count { sonarr.cancelCommand(it) }
    runCatching { sonarr.fireProcessMonitoredDownloads() }
    db.insertJamIntervention(now, "jammed", "soft", "ok", cancelled)
    return SoftAttempted(cancelled)

if last.action == "soft" AND now - last.attempted_at >= 5 min:
    if settings.sonarrJamAutoRestart:
        val ok = sonarr.systemRestart()
        db.insertJamIntervention(now, "jammed", "restart", if (ok) "ok" else "failed", 0)
        return Restarted(ok)
    else:
        // Only insert "await_user_restart" once per soft-window to avoid spam.
        if last.action != "await_user_restart":
            db.insertJamIntervention(now, "jammed", "await_user_restart", "user_action_required", 0)
        return AwaitingUser

// Still inside soft-mitigation grace window — no-op this tick.
return Noop
```

Idempotent + bounded: at most one "soft" attempt per 30 min; at most one restart per "still jammed >5 min after soft."

### Preventative cap on concurrent SeriesSearch

Every prioritarr code path that fires `triggerSeriesSearch` (after Subsystem C lands, that's only the P5 ratchet inactive-mode fallback and the queue janitor's re-search-on-blocklist) calls a small helper first:

```kotlin
// In SonarrClient or a small wrapper:
suspend fun canFireSeriesSearch(cap: Int = settings.sonarrJamConcurrentSearchCap): Boolean {
    val commands = listCommands()  // cached 30s
    val inFlight = commands.count {
        val name = it["name"]?.jsonPrimitive?.contentOrNull
        val status = it["status"]?.jsonPrimitive?.contentOrNull
        (name == "SeriesSearch" || name == "MissingEpisodeSearch")
            && (status == "queued" || status == "started")
    }
    return inFlight < cap
}
```

Default cap **3**. When `false`, the caller logs `[jam-guard] skipped SeriesSearch for series X: cap reached` and skips. Next sweep tick re-evaluates.

### UI

- **Dashboard health row** gets a new "Sonarr resilience" card: green/amber/red based on most-recent intervention (red = "await_user_restart" with no follow-up; amber = soft mitigation < 30 min ago; green = healthy). Click expands to the actions panel.
- **Settings → Sonarr resilience** section:
  - Three one-click action buttons (always available): **Clear queued searches** (POST `/api/v2/sonarr/clear-queued`) · **Fire import scan** (POST `/api/v2/sonarr/fire-import`) · **Restart Sonarr** (POST `/api/v2/sonarr/restart`, with confirm dialog).
  - Toggle: **Auto-restart Sonarr when jammed** (default OFF, with text explaining the trade-off — restarts abort in-flight searches but DB-persisted commands resume).
  - Number inputs for the three thresholds.
  - Recent intervention history (last 10 rows from `sonarr_jam_interventions`).

### Settings

```yaml
sonarr_jam_search_age_minutes: 30
sonarr_jam_import_blocked_minutes: 10
sonarr_jam_guard_interval_minutes: 5
sonarr_jam_auto_restart: false              # opt-in
sonarr_jam_concurrent_search_cap: 3         # cap on in-flight SeriesSearch before skip
```

---

## Subsystem B — Indexer Health Guard

### New Sonarr API surface

```kotlin
suspend fun listIndexers(): JsonArray                          // GET  /api/v3/indexer
suspend fun indexerStatus(): JsonArray                         // GET  /api/v3/indexerstatus
suspend fun toggleIndexer(id: Long, enabled: Boolean): Boolean // PUT  /api/v3/indexer/{id} with full body, enabled=...
```

`/api/v3/indexerstatus` returns per-indexer rows: `{ id, indexerId, disabledTill, mostRecentFailure, escalationLevel, initialFailure }`. Combined with `/api/v3/indexer` (which contains the `enabled` flag), this gives the full picture.

`toggleIndexer` is a PATCH-via-PUT: read the existing indexer body, mutate `enabled`, PUT it back. Sonarr's API requires the full body for indexer mutations.

### State

```sql
CREATE TABLE IF NOT EXISTS indexer_health_history (
    indexer_id INTEGER PRIMARY KEY,
    indexer_name TEXT NOT NULL,
    status TEXT NOT NULL,                    -- "healthy" | "unhealthy" | "auto_disabled" | "pinned"
    first_unhealthy_at INTEGER,              -- epoch sec; null when status = "healthy"
    last_probe_at INTEGER NOT NULL,          -- epoch sec of last status read
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    next_probe_at INTEGER                    -- epoch sec; only set when status = "auto_disabled"
);
```

`pinned` is a manual "leave this indexer alone" state set by the user via UI. The guard never touches pinned indexers.

### State machine

Pure function `IndexerHealthEvaluator.evaluate(row, sonarrStatus, sonarrEnabled, settings, now): EvalResult` in `resilience/IndexerHealth.kt`:

```kotlin
data class EvalResult(
    val newRow: IndexerHealthRow,
    val action: Action,         // None | Disable | ReEnable | Audit(String)
)
```

Backoff sequence (`indexer_health_backoff_hours`): `[2, 4, 6, 12, 24]`. Capped at 24h.

State transitions:

```
sonarrSaysFailing = (indexerStatus.disabledTill > now)
                  OR (now - indexerStatus.mostRecentFailure < settings.unhealthyThresholdHours hours)

For (current state, sonarrSaysFailing, sonarrEnabled):

(healthy,  true,  *)     → newRow.status = "unhealthy", first_unhealthy_at = now
                            action = Audit("indexer_unhealthy")
(healthy,  false, *)     → action = None

(unhealthy, true, true)
  AND now - first_unhealthy_at > unhealthyThresholdHours hours:
                          → newRow.status = "auto_disabled"
                            newRow.consecutive_failures = 0
                            newRow.next_probe_at = now + backoff[0]
                            action = Disable + Audit("indexer_auto_disabled")
(unhealthy, false, *)    → newRow.status = "healthy", clear first_unhealthy_at
                            action = Audit("indexer_recovered") (if was unhealthy)
(unhealthy, *, false)    → no auto-action (user disabled it manually)

(auto_disabled, *, *)
  AND now >= newRow.next_probe_at:
                          → action = ReEnable
                            // Stays auto_disabled until next tick re-reads status
(auto_disabled, true, true)
  AND prev row.next_probe_at <= now (we just re-enabled, it failed):
                          → newRow.consecutive_failures = prev + 1
                            newRow.next_probe_at = now + backoff[min(failures, last)]
                            action = Disable + Audit("indexer_re_disable")
(auto_disabled, false, true)
  AND we just re-enabled (next_probe_at was <= now):
                          → newRow.status = "healthy", clear all timers
                            action = Audit("indexer_recovered_after_probe")

(pinned, *, *)           → action = None  (operator-managed)
```

### Runner

`IndexerHealthGuard.runIndexerHealthGuard(sonarr, db, settings, now)` in `resilience/IndexerHealthGuard.kt`:

1. Fetch `listIndexers()` + `indexerStatus()`, join on `indexer.id == indexerStatus.indexerId`.
2. For each (indexer, status) pair:
   - Read current row from DB (or create initial healthy row).
   - Call `evaluate(row, status, indexer.enabled, settings, now)`.
   - Apply action: `Disable` → `toggleIndexer(id, false)`; `ReEnable` → `toggleIndexer(id, true)`; `Audit` → `db.appendAudit(action, indexerId, …)`.
   - Upsert new row.

### UI

- **Dashboard health row** gets an "Indexer health" card: shows count of healthy / unhealthy / auto-disabled. Red badge if ≥1 auto-disabled. Click → Settings → Indexer health.
- **Settings → Indexer health** new section:
  - Master toggle: **Auto-disable unhealthy indexers** (default ON).
  - Table: name · status badge · failure count · time-in-current-state · next probe (when auto_disabled) · actions.
  - Per-row buttons: **Probe now** (immediate re-enable + force tick) · **Reset failures** (counter to 0) · **Pin disabled** (move to `pinned` state, never auto-touched).
  - Read-only display of the backoff schedule + "Reset to defaults" button.

### Settings

```yaml
indexer_health_unhealthy_threshold_hours: 6
indexer_health_probe_interval_minutes: 30
indexer_health_backoff_hours: [2, 4, 6, 12, 24]
indexer_health_auto_disable: true
```

---

## Subsystem C — Migrate P3/P4 to EpisodeSearch

### Refactor

Rename `sweep/P1P2EpisodeSearch.kt` → `sweep/PriorityEpisodeSearch.kt`. The planner gains a `priorities: IntRange` parameter:

```kotlin
internal fun buildPriorityEpisodeCandidates(
    records: JsonArray,
    priorityBySeriesId: Map<Long, Int>,
    queuedEpisodeIds: Set<Long>,
    cooldownEpisodeIds: Set<Long>,
    perSeriesCap: Int,
    priorities: IntRange,                     // new
): List<PriorityEpisodeCandidate>             // renamed from P1P2Candidate
```

Same filter logic (`priority in priorities` instead of `priority in 1..2`), same sort, same cap. The shared `PriorityEpisodeEpisode` (rename of `P1P2Episode`) / `PriorityEpisodeCandidate` types live alongside.

Runner `runPriorityEpisodePass` (rename of `runP1P2EpisodePass`) takes a `priorityBand: PriorityBand` enum (`P1P2` | `P3P4`) for cooldown-table addressing.

### Cooldown table consolidation

Rename `p1p2_search_attempts` → `priority_episode_attempts`. Add a `priority_band` column:

```sql
CREATE TABLE IF NOT EXISTS priority_episode_attempts (
    priority_band TEXT NOT NULL,             -- "p1p2" | "p3p4"
    episode_id INTEGER NOT NULL,
    last_attempted_at INTEGER NOT NULL CHECK (last_attempted_at > 0),
    attempts_count INTEGER NOT NULL DEFAULT 1 CHECK (attempts_count >= 1),
    PRIMARY KEY (priority_band, episode_id)
);
```

**Migration:** at boot, if `p1p2_search_attempts` exists and `priority_episode_attempts` does not, create the new table and copy rows with `priority_band = 'p1p2'`. Drop the old table. Wrap in a transaction; rollback leaves the old table intact. Idempotent — re-running the migration on already-migrated DBs is a no-op (table-exists check).

DB wrapper methods become band-aware:

```kotlin
fun upsertPriorityAttempt(band: String, episodeId: Long, lastAttemptedAt: Long)
fun listPriorityAttemptedSince(band: String, thresholdEpochSeconds: Long): List<Long>
fun clearPriorityAttempt(band: String, episodeId: Long)
fun getPriorityAttemptCount(band: String, episodeId: Long): Int?
```

### Sweep.kt changes

```kotlin
// Pass A1 — P1/P2 (mostly unchanged; uses band="p1p2"):
val cooldownP1P2 = db.listPriorityAttemptedSince("p1p2", now - p1p2CooldownMinutes * 60L).toSet()
val p1p2Candidates = buildPriorityEpisodeCandidates(
    records, priorityBySeriesId, queuedIds, cooldownP1P2, perSeriesCap = 5, priorities = 1..2,
)
fired += runPriorityEpisodePass(p1p2Candidates, sonarr, db, band = "p1p2", budget = p1p2MaxPerSweep, …)

// Pass A2 — P3/P4 (NEW: was triggerSeriesSearch loop, now EpisodeSearch):
val cooldownP3P4 = db.listPriorityAttemptedSince("p3p4", now - p3p4CooldownMinutes * 60L).toSet()
val p3p4Candidates = buildPriorityEpisodeCandidates(
    records, priorityBySeriesId, queuedIds, cooldownP3P4, perSeriesCap = 5, priorities = 3..4,
)
fired += runPriorityEpisodePass(p3p4Candidates, sonarr, db, band = "p3p4", budget = p3p4MaxPerSweep, …)

// Pass B — P5 ratchet (unchanged):
// budget = maxSearches - fired
// (still uses SeriesSearch in inactive-mode fallback; Subsystem A's cap throttles it)
```

The existing `backfillMaxSearchesPerSweep` is reinterpreted as "Pass B budget only" — meaning of the value (10) is preserved, label updates to `Max searches per sweep (P5)`.

### On-grab follow-up extension (optional, opt-in)

Module.kt's Grab branch currently fires `runOnGrabFollowup` only for `priority in 1..2`. Generalise to also fire for P3/P4 when `backfillP3P4FollowupEpisodes > 0`. Defaults to 0 (off) so behaviour is unchanged unless the operator opts in. The follow-up function takes a `band` parameter for cooldown writes.

### Settings

```yaml
backfill_p3_p4_max_per_sweep: 10
backfill_p3_p4_cooldown_minutes: 60          # longer than P1/P2's 30
backfill_p3_p4_followup_episodes: 0          # default off
```

---

## Consolidated changes

### New files

- `backend/.../clients/SonarrJamApi.kt` *(or inlined in Sonarr.kt)*
- `backend/.../resilience/JamDetector.kt`
- `backend/.../resilience/JamGuard.kt`
- `backend/.../resilience/IndexerHealth.kt`
- `backend/.../resilience/IndexerHealthGuard.kt`
- Test counterparts for each pure function + runner.

### Modified files

- `backend/.../clients/Sonarr.kt` — 8 new methods
- `backend/.../Main.kt` — register 2 new scheduler jobs (`SONARR_JAM_GUARD`, `INDEXER_HEALTH_GUARD`); update `BACKFILL_SWEEP` for P3/P4 args
- `backend/.../sweep/Sweep.kt` — Pass A2 now calls `buildPriorityEpisodeCandidates(priorities = 3..4)`
- `backend/.../sweep/P1P2EpisodeSearch.kt` → renamed `PriorityEpisodeSearch.kt`, parameterised
- `backend/.../webhooks/OnGrabFollowup.kt` — generalise to P3/P4 when opt-in
- `backend/.../config/Settings.kt` — 11 new fields + override + YAML
- `backend/.../database/Schema.sq` — 2 new tables, rename + migration of `p1p2_search_attempts`
- `backend/.../database/Database.kt` — wrappers
- `backend/.../schemas/V2.kt` — wire fields
- `backend/.../api/v2/V2Routes.kt` — patch + view + new POST routes (`/sonarr/clear-queued`, `/sonarr/fire-import`, `/sonarr/restart`, `/indexers/{id}/probe`, `/indexers/{id}/reset-failures`, `/indexers/{id}/pin`)
- `frontend/src/pages/SettingsPage.tsx` — 2 new sections (Sonarr resilience, Indexer health)
- `frontend/src/lib/jobs.tsx` — register 2 new jobs; update `backfill-sweep` labels
- `frontend/src/pages/Dashboard.tsx` (or wherever the health-banner row lives) — 2 new cards
- `frontend/src/hooks/queries.ts` — hooks for new endpoints
- `README.md` — see "Documentation" below

### Settings (consolidated)

```yaml
# Subsystem A
sonarr_jam_search_age_minutes: 30
sonarr_jam_import_blocked_minutes: 10
sonarr_jam_guard_interval_minutes: 5
sonarr_jam_auto_restart: false
sonarr_jam_concurrent_search_cap: 3

# Subsystem B
indexer_health_unhealthy_threshold_hours: 6
indexer_health_probe_interval_minutes: 30
indexer_health_backoff_hours: [2, 4, 6, 12, 24]
indexer_health_auto_disable: true

# Subsystem C
backfill_p3_p4_max_per_sweep: 10
backfill_p3_p4_cooldown_minutes: 60
backfill_p3_p4_followup_episodes: 0
```

`backfillMaxSearchesPerSweep` is **reinterpreted**, not renamed — value preserved, UI label changes to "Max searches per sweep (P5)".

---

## Tests

| File | Coverage |
|---|---|
| `JamDetectorTest.kt` *(new)* | healthy / single stuck SeriesSearch / multiple queued / import-blocked / mixed state / threshold boundaries / unparseable timestamps (≥8 tests) |
| `JamGuardTest.kt` *(new)* | state-machine over multiple ticks: soft → restart → healthy; soft → no restart when toggle off; idempotency under repeated jamming; never-jam-window (≥6 tests) |
| `IndexerHealthEvaluatorTest.kt` *(new)* | each state transition; backoff progression; pin-disabled override; manual-disable no-touch (≥10 tests) |
| `IndexerHealthGuardTest.kt` *(new)* | runner integration with fake Sonarr: join indexerStatus + indexer, apply actions, persist rows (≥4 tests) |
| `PriorityEpisodeSearchTest.kt` *(rename + extend)* | existing 7 planner tests parameterise over priority ranges; runner tests cover band="p3p4" writes to correct cooldown band; new test: p3p4 candidate with p1p2-banned episode IDs not filtered (separate cooldown spaces) |
| `SweepIntegrationTest.kt` *(extend)* | new case: `p3p4_fires_episode_search_not_series_search`; existing P5 case unchanged |
| `PriorityEpisodeAttemptsRoundTripTest.kt` *(rename)* | round-trip tests parameterised over band; new test: migration from `p1p2_search_attempts` populates `priority_band = 'p1p2'` |
| `SonarrClientTest.kt` *(extend)* | new endpoints: listCommands, cancelCommand, fireProcessMonitoredDownloads, systemRestart, indexerStatus, toggleIndexer, listIndexers |

---

## Documentation (README)

Concrete tasks (each is one bullet, expected ≤ 5-10 lines of README content):

1. **New "Resilience" top-level section** describing Subsystem A: jam detection, soft mitigation, opt-in restart, concurrent-search cap. Link to the spec.
2. **"Indexer health" subsection** under "Background jobs": probe schedule, auto-disable threshold, backoff ladder, manual overrides.
3. **"Background jobs" table** gains two new rows: `Sonarr jam guard` (5 min) · `Indexer health guard` (30 min).
4. **"What prioritarr does" bullet list** gets two new items: "Detects and recovers from Sonarr command-queue jams" and "Manages indexer health with auto-disable + backoff retry".
5. **"Queue enforcement" / priority decision graph** updated to note: P3/P4 now fires `EpisodeSearch` (5 oldest missing per series), P5 unchanged.
6. **Retroactive: P1/P2 fast-grab narrative.** Task 12 added only YAML keys to the config reference; add a ~5-sentence paragraph explaining the boot-ordering guarantee (priorities-refresh runs before any sweep), the per-episode cooldown (default 30 min), and the on-grab follow-up behaviour. Cross-reference `docs/specs/2026-05-15-p1p2-fast-grab-design.md`.
7. **Retroactive: Trakt token auto-refresh.** Short paragraph: prioritarr refreshes Trakt tokens proactively within the 7-day window, surfaces an inline Reconnect affordance when the Trakt app is revoked, and tracks the actual `traktTokenIssuedAt` so the UI's "Last refreshed" reflects reality.
8. **Configuration reference YAML block** expanded with all 11 new keys + brief inline `#` comments.

---

## Failure modes (consolidated)

| Failure | Mitigation |
|---|---|
| Sonarr unreachable | Both jobs no-op for this tick, warn-log, retry next tick. |
| `cancelCommand` returns 409 (already started) | Log as expected — soft mitigation moves on; that command will run to completion or be killed by restart. |
| `systemRestart` returns 5xx | Audit `restart_failed`, do not retry in the same window; UI surfaces the failure in intervention history. |
| `toggleIndexer` returns 5xx | Keep DB row at current state, retry next tick. State machine doesn't advance until a successful round trip. |
| DB migration of `p1p2_search_attempts → priority_episode_attempts` fails | Wrapped in transaction; rollback leaves old table intact; warn-log; subsequent boots retry. |
| Only one indexer total, it goes unhealthy | Auto-disable would break all searches. **Mitigation:** the `pinned` state — operator can pin the sole indexer, the guard never touches it. UI surfaces a warning when auto-disabling the last enabled indexer would result in zero healthy indexers — prompts before disabling. |
| Sonarr restart mid-jam-guard tick | Next tick re-reads command queue; if Sonarr is still rebooting, listCommands fails → no-op → retry next tick. |
| Concurrent search cap is set to 0 by misconfiguration | Treat as "skip all SeriesSearch" — log a warning at every skip, surface in UI as banner. |

---

## Edge cases

- **First boot, empty DB:** All three tables empty; jobs initialise rows on first run; first health check is "everything looks healthy" so no false positives.
- **User triggers a manual SeriesSearch from Sonarr UI during a jam:** counts toward the cap but prioritarr won't try to cancel it — only commands matching SeriesSearch/MissingEpisodeSearch type are auto-cancelled, and the soft mitigation explicitly targets only the queue (not started). User-triggered started commands run to completion.
- **Indexer is re-enabled by user mid-backoff:** next probe tick sees `enabled=true` + healthy → transitions to `healthy`, clears `next_probe_at`. No conflict.
- **Last refresh of a Trakt token failed during a jam-related outage:** unrelated to this spec; Trakt's own auto-refresh job handles it (see `docs/specs/2026-05-15-p1p2-fast-grab-design.md` and the inline Reconnect feature).

---

## Out of scope

- Prowlarr-side health probes — Sonarr's view already reflects them.
- "Deep backfill" mode for P4 — episode-search-only, period.
- P5 ratchet changes — leave alone; Subsystem A's cap throttles it.
- Auto-tuning the indexer backoff schedule based on historical recovery rates.
- A "central event bus" refactor — the existing SSE bus is sufficient for the new UI cards.
- Multi-server Sonarr support (assumes one Sonarr instance per prioritarr deployment).
