# P5 Backfill Season Ratchet — Search & Download

Date: 2026-05-05
Status: Design — search-side and download-side ratchet for P5 series, with
        a shared bandwidth-availability override.

## Background

Prioritarr classifies every monitored series into P1–P5. P5 is the lowest
band — full-backfill / dormant shows. Today the backfill sweep
(`sweep/Sweep.kt::runBackfillSweep`) treats every priority the same
shape: group `/wanted/missing` records by series, sort by
`(priority asc, oldestAirDate asc)`, fire `SeriesSearch` per series up
to a budget of `backfill_max_searches_per_sweep` (default 10).

Two consequences of that uniform shape, specifically for P5:

1. **Search is not season-ordered.** `SeriesSearch` tells Sonarr to look
   for *every* missing episode of the show at once. Sonarr grabs
   whatever its indexers find first, which can be S5E12 before S1E03
   for a show being backfilled from scratch.
2. **Downloads in qBit/SAB are not season-ordered either.** The
   enforcement layer (`computeQBitPauseActions` + the SAB priority map)
   only operates on the P1–P5 band; within P5 there is no notion of
   season tier, so the order in which P5 torrents finish is whatever
   qBit/SAB happens to start first.

The user wants P5 backfill to favour earlier seasons first, gradually
working forward to later seasons — but only when bandwidth is genuinely
contended. When the pipe has headroom, the historical broad-search /
broad-pull behaviour is correct, because ordering matters less than
keeping the queue full.

## Goals

- Within a single P5 series, prefer to backfill S1 before S2 before S3,
  and so on (per-series ratchet, not cross-series).
- The same ratchet applies to *both* the search side (which seasons
  Sonarr is told to look for) and the download side (which P5 torrents
  are allowed to run when more than one season's worth is in qBit/SAB).
- A bandwidth-availability override skips the ratchet entirely, in both
  halves, when the pipe has headroom — falling back to today's
  behaviour.
- The calculation lives in **one place** at the abstraction level. Each
  download client (qBit, SAB, future Transmission/NZBGet) only provides
  data and translates calculated decisions to its native API.
- All knobs are live-editable; nothing requires a restart. A single
  global `enabled` flag reverts the entire feature on the next tick.

## Non-goals

- Cutoff sweep (`runCutoffSweep`) is untouched — cutoff is upgrade
  territory, not backfill.
- Movies / Radarr — the feature is series/season-shaped; movies are
  single-file and don't have the ordering problem.
- Cross-series fairness within P5 beyond "one season per series per
  sweep" — left as a future iteration if the simple rule isn't enough.
- Specials (`seasonNumber == 0`) are excluded from the ratchet by
  default; an `include_specials` config flag opts in.

## Architecture

### Two ratchet halves, one shared signal

```
                         ┌─ utilisationExceedsThreshold ─┐
                         │   (read once per pass)         │
                         ▼                                ▼
   Search side ── Pass B in backfill sweep        Download side ── reconciler tick
   (every 2h)                                       (every 15m)
        │                                                │
        ├── ratchet active?                              ├── ratchet active?
        │   ├── yes → SeasonSearch + escalation ladder   │   ├── yes → per-series sub-band + SAB Low reorder
        │   └── no  → SeriesSearch (today's behaviour)   │   └── no  → today's pause-band only
```

The bandwidth signal is `BandwidthPolicy.utilisationExceedsThreshold`,
which already exists and is the canonical "is the pipe near its cap?"
check. It's read once at the top of each pass and locked in for the
duration — passes are short enough that re-reading mid-loop just adds
noise from instantaneous spikes.

### Files touched / added

| File | Change |
|------|--------|
| `sweep/Sweep.kt` | `runBackfillSweep` splits into Pass A (P1–P4, today's logic) + Pass B (P5, ratchet hand-off). Cutoff sweep unchanged. |
| `sweep/P5Ratchet.kt` *(new)* | Pure season-picker `buildP5RatchetPlan` + thin runner that executes against `SonarrClient`. |
| `clients/Sonarr.kt` | Adds `triggerSeasonSearch(seriesId, seasonNumber)`. `triggerEpisodeSearch` already exists. |
| `enforcement/Enforcement.kt` | `computeQBitPauseActions` is generalised into `computeEnforcement(downloads, ctx)` operating on a unified `ManagedDownloadView` and returning per-item `EnforcementDecision`. The qBit-specific verbs move into qBit's `applyEnforcement`. |
| `clients/DownloadClient.kt` | Interface gains `snapshotDownloads(): List<RawDownload>` (input) and `applyEnforcement(decisions: Map<String, EnforcementDecision>)` (output). Existing per-item verbs unchanged. |
| `clients/QBittorrent.kt` | Implements the two new methods. ACTIVE→resume/clear-flag, DEFERRED→pause/set-flag, `orderHint` drives `setTopPriority` calls in reverse-orderHint order. |
| `clients/SABClient.kt` | Implements the two new methods. Bucket mapping unchanged; `orderHint` drives `queue/switch` swaps within `Low`. |
| `reconcile/Reconcile.kt` | `reconcileQbit` + `reconcileSab` collapse into `reconcileAll`: snapshot → enrich (Sonarr cross-ref) → `computeEnforcement` → fan-out per client. |
| `database/Schema.sq` | New `p5_sweep_attempts` table; new nullable `season_number` column on `managed_downloads`. |
| `config/Settings.kt` | New `p5_ratchet` config block. |
| `Main.kt` | Wires bandwidth source + telemetry through to the sweep job; otherwise incidental. |

### Why the unified `computeEnforcement` abstraction

The current `DownloadClient` interface comment says enforcement strategy
is *not* unified at the interface level because qBit (pause-band) and
SAB (priority bucket) have different mental models. That comment was
written conservatively. The *application* of decisions is genuinely
client-specific (qBit translates ACTIVE→resume; SAB translates
ACTIVE→top-of-bucket), but the *calculation* — which item should be
ACTIVE vs DEFERRED, and in what order — does not depend on the client
at all. Bolting a P5 sub-band onto the existing qBit-only path *and* a
parallel SAB reorder would calcify the split and duplicate the
season-tier logic.

The unified shape:

```kotlin
data class ManagedDownloadView(
    val client: String,           // "qbit" | "sab" | future
    val clientId: String,         // hash | nzo_id
    val priority: Int,            // P1-P5
    val seriesId: Long?,          // nullable: orphan/unmatched
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val state: ManagedState,      // RUNNING | PAUSED_BY_US | PAUSED_BY_USER | ERRORED | NEAR_DONE
    val etaSeconds: Long?,
)

data class EnforcementDecision(
    val targetState: TargetState,  // ACTIVE | DEFERRED
    val orderHint: Int,            // lower = earlier; for clients that order within-band
)

fun computeEnforcement(
    downloads: List<ManagedDownloadView>,
    ctx: EnforcementContext,       // bandwidth signal, ratchet flag, peer-limit predicate
): Map<String, EnforcementDecision>
```

Adding a future Transmission/NZBGet client means implementing
`snapshotDownloads` + `applyEnforcement`. The calculation never
changes.

## Algorithms

### Search-side ratchet (`sweep/P5Ratchet.kt`)

**Inputs and outputs:**
```kotlin
data class P5RatchetInputs(
    val p5Records: List<MissingRecord>,         // /wanted/missing rows where priority==5
    val sonarrQueue: List<QueueEntry>,          // /api/v3/queue snapshot
    val cooldownRows: List<P5SweepAttempt>,     // current state of p5_sweep_attempts
    val now: Instant,
    val cfg: P5RatchetConfig,                   // cooldownHours, longCooldownHours, escalationThreshold, includeSpecials
    val budgetRemaining: Int,                   // from maxSearches minus Pass A consumption
    val ratchetActive: Boolean,                 // bandwidth signal, locked at pass start
)

data class P5RatchetPlan(
    val actions: List<RatchetAction>,
    val cooldownWrites: List<P5SweepAttempt>,
)

sealed interface RatchetAction {
    data class SeasonSearch(val seriesId: Long, val seasonNumber: Int) : RatchetAction
    data class EpisodeSearch(val seriesId: Long, val seasonNumber: Int, val episodeIds: List<Long>) : RatchetAction
    data class SeriesSearch(val seriesId: Long) : RatchetAction
}
```

`buildP5RatchetPlan(inputs): P5RatchetPlan` is pure. The runner
executes each action against `SonarrClient` with the existing
inter-search delay, then upserts cooldown rows in one transaction.

**Algorithm:**

1. **Short-circuit if bandwidth headroom.** If `!ratchetActive`, return
   a plan whose actions are `SeriesSearch` per P5 series in
   oldest-air-date order — today's behaviour, expressed as a
   `RatchetAction` variant for symmetry. No cooldown writes.

2. **Group `p5Records` by `seriesId`.** For each series, build
   `seasonsWithMissing: Map<Int, List<MissingRecord>>` keyed by
   `seasonNumber`. Skip series whose only missing records are
   `seasonNumber == 0` when `includeSpecials = false` (default).

3. **For each P5 series in oldest-air-date order**, iterate seasons in
   ascending `seasonNumber` (with `seasonNumber == 0` placed *after*
   numbered seasons when `includeSpecials = true`). For each candidate
   season:

   - **Cooldown check.** If a `P5SweepAttempt` row exists and
     `lastAttemptedAt + cooldownHours > now`, skip (still cooling).
   - **Long cooldown check.** If `consecutiveEmptyAttempts >= escalationThreshold`,
     check `lastAttemptedAt + longCooldownHours > now`. If still
     cooling, skip; otherwise allow a fresh attempt at the next
     strategy below.
   - **Progress detection** *(updates the row before deciding strategy,
     not after).* If a row exists with `lastMissingCount` set:
     - `currentMissing = seasonsWithMissing[seasonNumber].size`
     - `queuePresent = sonarrQueue` has any entry whose `episodeIds`
       intersect this season's missing episodes.
     - If `currentMissing < lastMissingCount` *or* `queuePresent` →
       reset `consecutiveEmptyAttempts = 0`.
     - Else → `consecutiveEmptyAttempts++`.
   - **Strategy choice from the (now updated) counter:**
     - `consecutiveEmptyAttempts == 0` → `SeasonSearch(seriesId, seasonNumber)`
     - `consecutiveEmptyAttempts == 1` → `SeasonSearch` again
     - `consecutiveEmptyAttempts >= 2` → `EpisodeSearch(seriesId, seasonNumber, episodeIds=missing)`
   - **Pick this season.** Append the action; append a cooldown write
     for `(seriesId, seasonNumber, lastAttemptedAt=now,
     lastMissingCount=currentMissing,
     consecutiveEmptyAttempts=updated)`. Decrement budget. **Break
     out of the season loop for this series** — only one season fires
     per series per sweep.

4. If `budgetRemaining == 0`, break the series loop.

**Why this shape:** the picker is monotone — when missing-count drops
or queue activity appears, the counter resets, so we re-try
`SeasonSearch` first instead of jumping straight to per-episode. The
long-cooldown gate puts a floor on indexer hits per
(series, season). And the "one season per series per sweep" rule means
the budget gets spread across all P5 shows fairly.

**`p5_sweep_attempts` schema:**
```sql
CREATE TABLE p5_sweep_attempts (
    series_id INTEGER NOT NULL,
    season_number INTEGER NOT NULL,
    last_attempted_at INTEGER NOT NULL,    -- epoch seconds
    last_missing_count INTEGER,            -- null on very first attempt
    consecutive_empty_attempts INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (series_id, season_number)
);
```

### Download-side ratchet (inside `computeEnforcement`)

Two layers:

**Layer 1 — Cross-band (today's pause-band, restated in the new shape):**
```
activePriorities = priorities of downloads NOT in {paused_by_user, errored, near_done}
hasP1 = 1 in activePriorities
hasP2 = 2 in activePriorities

bandsToDefer =
  hasP1 -> {4, 5}
  hasP2 -> {5}
  else  -> {}
```
Every download whose priority is in `bandsToDefer` gets
`targetState = DEFERRED`, subject to the existing peer-limit and
near-done predicates from `EnforcementContext`.

**Layer 2 — P5 sub-band (new, only when `ctx.p5SeasonRatchetActive`):**
For every P5 download not already DEFERRED by Layer 1:
- Group by `seriesId`. Items with `seriesId == null` skip the rule —
  they default to `targetState = ACTIVE` and emit a debug audit line so
  the gap is visible.
- Within each group, find `minSeason = min(seasonNumber)` across the
  group's items that have a non-null `seasonNumber` and aren't
  paused-by-user / errored.
- For each item in the group:
  - `seasonNumber == minSeason` → `targetState = ACTIVE`
  - `seasonNumber > minSeason` → `targetState = DEFERRED`
  - `seasonNumber == null` → `ACTIVE` + audit line.

**`orderHint` for everyone:**
```
orderHint =  priority * 1_000_000
           + (seasonNumber ?: 99) * 1_000
           + (episodeNumber ?: 0)
```
Lower wins. Stable across both layers.

**Bandwidth-aware skip predicates** (existing): `p1IsPeerLimited` and
`closeToFinish` still apply, evaluated after Layer 2, so the season
ratchet doesn't trigger a pause on a P5 torrent that's 90 % done.

### Per-client `applyEnforcement`

**qBit:**
- target=ACTIVE, state=pausedDL/pausedUP, pausedByUs=true → `resume(hash)` + clear flag.
- target=DEFERRED, state not paused → `pause(hash)` + set `pausedByUs=true`.
- For all ACTIVE items, sort by `orderHint` ascending; call
  `setTopPriority(hash)` from highest `orderHint` to lowest. qBit treats
  `top_priority` as "place above all others," so calling in reverse
  order leaves the lowest-`orderHint` item at the very top.
- User-paused / errored states are never touched.

**SAB:**
- `applyPriority` per item maps P1–P5 → bucket. Unchanged; runs first.
- Within each bucket (esp. `Low`), compute the desired ordering by
  `orderHint` ascending. Walk SAB's current queue; only emit a
  `queue/switch` swap when current position differs from desired by
  more than one slot — minimises churn under SAB's slightly jittery
  position reporting.

**Reconciler:**
```
1. snapshot per client → enrich (Sonarr cross-ref for seriesId/season/episode) → unified list
2. read bandwidth signal once → set ratchetActive in EnforcementContext
3. decisions = computeEnforcement(unifiedList, ctx)
4. for each client: applyEnforcement(decisions.filter { it.client == thisClient })
5. write managed_downloads updates (pausedByUs flag, season_number, …) in one tx
```

## Configuration

```yaml
p5_ratchet:
  enabled: true                          # global kill switch — false = today's behaviour
  search_cooldown_hours: 24              # min spacing per (series, season) attempt
  long_cooldown_hours: 168               # 7d nap after escalation_threshold empties
  escalation_threshold: 5                # consecutive empties → long cooldown
  include_specials: false                # skip seasonNumber==0 in the picker
  bandwidth_threshold_pct: 0.85          # optional override; defaults to BandwidthSettings.utilisationThresholdPct
```

`enabled: false` short-circuits both Pass B *and* Layer 2 — calculation
reverts to today's exact behaviour. Settings UI gets a "P5 backfill
ratchet" sub-section under Settings → Priority, with the six fields
plus an inline preview: "Current bandwidth utilisation: X %; ratchet
would currently be: ACTIVE / INACTIVE."

## Audit & UI

**Search side audit:** `{kind: "p5_ratchet_search", series_id,
season_number, strategy: "season"|"episode"|"series", attempt: N,
dry_run: bool}`. One log line per sweep summary:
`[backfill] P5 ratchet: 7 series, 4 SeasonSearches + 1 EpisodeSearch + 2 cooldowned`.
Cooldown skips are summary-counted only, not per-row audited.

**Download side audit:** new kind `p5_ratchet_defer` when a P5 download
moves ACTIVE → DEFERRED purely due to the sub-band rule. Distinct from
the existing pause-band audit so "why was this paused?" stays
greppable. The `paused_by_us` flag on `managed_downloads` is reused
unchanged.

**Series detail drawer:** a new "P5 ratchet" tile, only when the
series is in P5 *and* the ratchet is active:

```
Currently backfilling: Season 1   (3 missing, last attempt 4h ago)
Cooldowns:             Seasons 5–8 (after long cooldown, retries 2026-05-12)
Strategy:              SeasonSearch (attempt 1)
```

Pure SELECT from `p5_sweep_attempts` joined with the missing-records
snapshot. No extra job.

## Testing

**Pure-function tests (the bulk):**

- `buildP5RatchetPlan`:
  - bandwidth headroom path returns `SeriesSearch` fallback
  - all seasons in cooldown → empty plan
  - S1 done (no missing) → picker advances to S2
  - S1 last attempt 25h ago, no progress → SeasonSearch retry (counter=1)
  - counter=2 → EpisodeSearch with the right episode-id list
  - counter==threshold → skipped via long cooldown, picker advances
  - missing-count drop between attempts → counter resets
  - queue presence between attempts → counter resets
  - budget exhausted mid-loop → remaining series get no actions
  - `includeSpecials=false` → S0 skipped; `=true` → S0 sorted last
  - per-series isolation: progress on series X doesn't reset counters on series Y

- `computeEnforcement`:
  - Layer 1 unchanged → existing tests stay green (regression guard)
  - `ratchetActive=false` → identical output to today's
    `computeQBitPauseActions` (parity test against shared fixtures)
  - `ratchetActive=true`, single series with S1+S2+S3 P5 torrents →
    S1 ACTIVE, S2/S3 DEFERRED
  - two series independent → each gets its own min-season
  - `seasonNumber=null` on one item → ACTIVE + audit-flagged, no crash
  - `orderHint` monotonicity test

**Smoke / integration tests:**
- `runP5SeasonRatchet` against a `FakeSonarrClient` — happy path,
  all-cooldown, Sonarr 5xx (graceful skip).
- `reconcileAll` against `FakeQbitClient` + `FakeSabClient` —
  decisions for hash X go to qBit, decisions for nzo Y go to SAB.

**No live indexer tests.** The escalation logic depends on indexer
behaviour, which is non-deterministic and rate-limited. We test the
calculation, the API translation, and trust integration with real
services to operator validation in `DRY_RUN=true`.

## Migration & rollout

**Schema migration (SQLDelight):** one new file creates
`p5_sweep_attempts` and adds nullable `season_number` column to
`managed_downloads`. Both nullable / default-zero; no backfill.

**Lazy population.** The reconciler's enrichment step populates
`season_number` on first cross-ref of each managed download. Within one
reconcile cycle (15 min), every active download has its column set.

**Rollout path:**
1. Ship with `p5_ratchet.enabled = false` as the default. Patch is
   invisible until intentionally enabled.
2. Operator runs in `DRY_RUN=true` for one sweep cycle, verifies the
   audit log shows sane plans.
3. Flip `enabled = true`, `DRY_RUN=false`. Watch the series-detail
   drawer for a few series.
4. If anything looks wrong, set `enabled = false`; system reverts on
   the next tick. No restart, no migration to undo.
