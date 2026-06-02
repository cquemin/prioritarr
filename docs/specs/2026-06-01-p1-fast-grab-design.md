# P1 Fast-Grab & Import-Aware Priority — Design

**Date:** 2026-06-01
**Status:** Approved (design)
**Author:** prioritarr maintainer + Claude

## Problem

Two related gaps in how prioritarr handles a **live-following (P1)** show
that just aired a new episode:

1. **The grab isn't aggressive enough.** The only path that fires
   `EpisodeSearch` for P1/P2 missing episodes is `backfill-sweep`, which
   runs every **2 hours** (`intervals.backfillSweepHours`). Its
   per-episode P1/P2 cooldown is already 30 min, but the 2 h sweep
   cadence means that cooldown is never exercised — in practice a fresh
   P1 episode is searched at most once every 2 h. Observed concretely:
   *That Time I Got Reincarnated as a Slime* aired a new episode, was
   correctly computed P1 (`release=0d, missing=1`), but the last
   `backfill-sweep` had run ~1 h *before* the episode existed, so nothing
   had searched for it yet.

2. **Priority doesn't update immediately on import.** The Sonarr
   `Download` (import) webhook handler (`Module.kt`) **invalidates** the
   priority cache row (`DELETE`) but **never recomputes** it. The series
   list/detail endpoints read `series_priority_cache` directly and do
   **not** recompute on a miss. So a freshly-imported episode's series
   only gets a fresh priority on the next 30-min `refresh-priorities`
   batch. Observed concretely: *One Piece* was P1 with one episode
   missing; after that episode downloaded + imported, the UI still showed
   P1 after a refresh, because no immediate recompute happened.

The user wants: from **1 h after release**, search a P1 episode **every
20 min** until it's grabbed; once grabbed, make sure it downloads and
imports **as fast as possible** (force-priority + recover from stalls);
and have the series' priority **adjust immediately on import**.

## Goals

- A dedicated, tight (20-min) search loop for freshly-released **P1**
  episodes, bounded to a 48 h post-release window.
- The grabbed download is top-priority in the client and never paused by
  the bandwidth enforcer (largely already true — verify + nudge).
- A stalled/failed P1 grab is detected fast (~30 min, vs the current
  48 h) and an alternate release is searched.
- A series' priority is recomputed **immediately** when an episode is
  imported, so the UI reflects "nothing to download → P5" within seconds.
- Everything is gated by an `enabled` flag and live-editable knobs;
  P2–P5 cadence and the existing backfill/cutoff sweeps are untouched.

## Non-goals

- Changing the P1–P5 classification logic in `Compute.kt`. P1 already
  means "live-following with a release to grab"; this design only changes
  *how aggressively* prioritarr acts on it and *how promptly* it
  re-evaluates after import.
- Applying the aggressive treatment to P2 (explicitly P1-only).
- Movies / Radarr.
- Replacing polling with pure webhooks — Sonarr has no reliable
  "episode released" event, so "search every 20 min until found" is
  inherently a poll. The existing OnGrab follow-up webhook is retained
  and complements this.

## Current architecture (relevant pieces)

- **`Sweep.kt::runBackfillSweep`** — every 2 h. Pass A1 builds P1/P2
  episode candidates (`buildPriorityEpisodeCandidates`, `priorities=1..2`)
  and fires `EpisodeSearch` via `runPriorityEpisodePass`, skipping queued
  episodes and episodes inside the `BAND_P1P2` cooldown window. Pass A2 =
  P3/P4 series search, Pass B = P5 ratchet.
- **`PriorityEpisodeSearch.kt`** — pure planner (`buildPriorityEpisodeCandidates`)
  + executor (`runPriorityEpisodePass`). Reusable. Records cooldown rows
  into `priority_episode_attempts(priority_band, episode_id, …)`.
- **`ComputeEnforcement.kt`** — P1 is **never** in the defer set; when a
  P1 is RUNNING, P4/P5 are deferred (paused) and the client adapter does
  `setTopPriority`. Confirmed live: `pause`/`resume`/`top_priority`/
  `reorder` audit actions are firing, `DRY_RUN=false`.
- **`QueueJanitor.kt`** — every 30 min. Detects stuck items (qBit
  `last_activity` older than `stuckAfter=48h` or terminal state; SAB
  Paused > 48 h; SAB history Failed), removes + blocklists them in Sonarr,
  and fires an `EpisodeSearch` for an alternate. `handleStuck` is the
  reusable remediation. Currently a single flat 48 h threshold for all
  priorities.
- **`Module.kt` `POST /api/sonarr/on-grab`** — routes by `eventType`:
  - `Grab` → `handleOnGrab` + async `runOnGrabFollowup`.
  - `Download` (import) → `invalidatePriorityCache(seriesId)` +
    `clearPriorityAttempt(BAND_P1P2, …)` + publish `episode-imported`
    SSE. **No recompute.**
- **`PriorityService.priorityForSeries`** — read-through cache: returns
  cached if `now < expires_at`, else `buildSnapshot` (live Sonarr
  `getSeries`+`getEpisodes`, reads per-episode `hasFile`) → `computePriority`
  → `upsertPriorityCache` with TTL `cache.priorityTtlMinutes` (60).
- **`refresh-priorities` job** — every 30 min, recomputes all series and
  overwrites the whole cache table.

## Design

### Part 1 — `p1-fast-sweep` job (search loop)

A new scheduler job, default cadence **20 min**.

Per tick (skipped entirely when `enabled=false`):

1. `records = sonarr.getWantedMissing()`. Early-return if empty.
2. Compute priority per series (`priorityService::priorityForSeries`,
   read-through cache) and keep **P1** series only.
3. **Episode-level release gate.** For each missing episode of a P1
   series, keep it only if its own `airDateUtc` satisfies:
   `releaseDelay ≤ (now − airDateUtc) ≤ fastWindow`
   with `releaseDelay = p1FastReleaseDelayMinutes` (60) and
   `fastWindow = p1FastWindowHours` (48 h). Episodes younger than 1 h are
   skipped (subs not posted yet); older than 48 h fall back to the normal
   2 h `backfill-sweep`.
4. Skip episodes already in Sonarr's download queue
   (`getQueue().toEpisodeIdSet()`) and episodes inside the
   **`BAND_P1_FAST`** cooldown window
   (`listPriorityAttemptedSince(BAND_P1_FAST, now − p1FastCooldownMinutes*60)`).
5. Fire `EpisodeSearch` and record `upsertPriorityAttempt(BAND_P1_FAST, …)`.

Implementation reuses `buildPriorityEpisodeCandidates` (with a new
`releaseWindow` filter parameter, or a thin pre-filter applied to
`records` before the existing planner) and `runPriorityEpisodePass(band =
BAND_P1_FAST, budget = p1FastMaxPerSweep)`. The queued-episode skip means
the loop **stops the instant a release is grabbed**; the 48 h window and
the P5 collapse (once `missing=0`) bound it.

New band constant `Database.BAND_P1_FAST = "p1_fast"` so its 20-min
cooldown is independent of the existing 30-min `BAND_P1P2` cooldown used
by `backfill-sweep` and `runOnGrabFollowup`.

### Part 2 — Force-priority download (verify + nudge)

`computeEnforcement` already (a) never defers P1, and (b) defers P4/P5
and `setTopPriority`s when a P1 is RUNNING. This runs on the 15-min
`reconcile` tick. Only change: on a **P1** `Grab` webhook, kick an
**immediate** enforcement/reconcile pass (async, after `handleOnGrab`) so
the fresh P1 download is forced to top-of-queue within seconds instead of
waiting up to 15 min for the next reconcile. No change to the enforcement
decision logic.

### Part 3 — Fast P1 stall re-search

Make `QueueJanitor` priority-aware:

- Add `p1StuckAfter` (default **30 min**) alongside the existing
  `stuckAfter` (48 h). In `findStuckQbit` / `findStuckSabQueue`, pick the
  threshold by the managed download's `current_priority`: P1 uses
  `p1StuckAfter`, everything else keeps 48 h.
- Add a **Sonarr-queue-status** check for P1 items: scan `sonarr.getQueue()`
  for entries whose `trackedDownloadStatus`/`trackedDownloadState` is
  `warning`/`error` (stalled, failed, import-blocked) and whose linked
  managed download is P1 — treat as stuck immediately (no time threshold).
- Stuck P1 items flow through the existing `handleStuck` (remove +
  blocklist the bad release, fire `EpisodeSearch` for an alternate).
- Run a **P1-only** stall pass on the **20-min** `p1-fast-sweep` cadence
  (e.g. `QueueJanitor.sweepP1Fast(dryRun)` that scopes detection to P1 +
  the 30-min/Sonarr-status rules and reuses `handleStuck`). The regular
  30-min `queue-janitor` job keeps doing the full 48 h sweep for all
  priorities, unchanged.

"No progress for ~30 min" maps to qBit `last_activity` idle ≥ 30 min;
"Sonarr-reported" maps to the queue-status check. Both per the user's
chosen stall rule.

### Part 4 — Recompute-on-import (immediate priority adjustment)

In `Module.kt` `"Download"` handler, after `invalidatePriorityCache`:

1. **Bounded hasFile confirmation.** Re-fetch the series' episodes and
   check the just-imported `episodeIds` report `hasFile=true`. If not yet
   (Sonarr import-vs-API race), retry up to **3 times** with a short
   delay (~1–2 s). Sonarr's `Download` event is post-import, so this is
   usually satisfied on the first try; the retry only guards the edge.
2. **Immediate recompute.** Call `priorityService.priorityForSeries(seriesId)`
   so a fresh snapshot (now `missing` reflecting the import) is computed
   and cached within seconds. For *One Piece* with its last missing
   episode imported, this yields P5 "Nothing to download" immediately.
3. The existing `episode-imported` SSE already prompts an open drawer to
   refetch; it now reads the just-written fresh value.

**Read-path safety net.** Make the series list and detail endpoints
**recompute-on-miss**: when `getPriorityCache(seriesId)` returns null
(e.g. invalidated but not yet recomputed), call `priorityForSeries` to
compute lazily rather than surfacing a blank/stale entry. (List endpoint:
recompute only the missing rows to keep the bulk read cheap.)

This does the recompute work asynchronously where it must not block the
always-200 webhook response (the handler launches the recompute in the
existing `application.launch { … }` style, mirroring `runOnGrabFollowup`).

## New settings

Added to `Intervals` (env + YAML + live-editable via `EditableSettings`
and `applySettingsOverride`, following the existing pattern). All
defaults chosen per the approved design:

| Setting | Default | Meaning |
|---|---|---|
| `p1FastEnabled` | `true` | Master switch for Parts 1 & 3 (fast sweep + fast stall). Off = pure pre-feature behaviour. |
| `p1FastSweepMinutes` | `20` | Cadence of the `p1-fast-sweep` job. |
| `p1FastReleaseDelayMinutes` | `60` | Don't search an episode until this long after its air date. |
| `p1FastWindowHours` | `48` | Stop the aggressive cadence this long after air date (then normal backfill). |
| `p1FastCooldownMinutes` | `20` | Per-episode cooldown for the `BAND_P1_FAST` band. |
| `p1FastMaxPerSweep` | `10` | Budget of `EpisodeSearch` calls per fast-sweep tick. |
| `p1StallMinutes` | `30` | P1-specific stuck threshold in `QueueJanitor` (vs 48 h default). |

YAML keys mirror the snake_case convention (`p1_fast_enabled`,
`p1_fast_sweep_minutes`, …) parsed in `loadSettingsFrom`.

Recompute-on-import (Part 4) has no knob — it's a straight correctness
fix, always on.

## Data model

- New cooldown band value `BAND_P1_FAST = "p1_fast"` written into the
  existing `priority_episode_attempts(priority_band, episode_id,
  last_attempted_at, attempts_count)` table. **No schema migration** —
  it's just another value in the existing `priority_band` column.
- No other schema changes.

## Behaviour walk-through (the Slime / One Piece cases)

**Fresh P1 episode (Slime):** airs → at +60 min the `p1-fast-sweep`
starts firing `EpisodeSearch` every 20 min → first release found is
grabbed and (Part 2) forced to top-of-queue within seconds → if that grab
goes idle ≥30 min or Sonarr flags it warning/error (Part 3), it's
blocklisted and an alternate is searched → Sonarr auto-imports on
completion → (Part 4) the import webhook recomputes the series → `missing=0`
→ **P5 "Nothing to download"** immediately. The aggressive loop stops the
moment the episode is queued, and unconditionally after 48 h.

**Last missing episode imported (One Piece):** import → `Download`
webhook → invalidate + bounded hasFile confirm + immediate recompute →
if no other monitored-aired episode is missing, `missing=0` → **P5**
shown within seconds instead of up to 30 min later.

## Testing

- **`buildPriorityEpisodeCandidates` release-window filter** — unit
  tests: episode younger than `releaseDelay` excluded; inside window
  included; older than `fastWindow` excluded; queued + `BAND_P1_FAST`
  cooldown excluded; P2–P5 series excluded.
- **`p1-fast-sweep` integration** — given a P1 series with a 2-h-old
  missing episode and an empty cooldown, one `EpisodeSearch` fires and a
  `BAND_P1_FAST` attempt row is written; a second tick inside 20 min
  fires nothing; a queued episode is skipped.
- **`QueueJanitor` priority-aware threshold** — P1 qBit torrent idle
  35 min is stuck; non-P1 idle 35 min is not; P1 with Sonarr queue status
  `warning` is stuck immediately; `handleStuck` blocklists + re-searches.
- **Recompute-on-import** — `Download` webhook for a series whose last
  missing episode now has `hasFile=true` results in a cached P5 within
  the handler; hasFile-race retry path covered with a mock that flips
  `hasFile` on the 2nd `getEpisodes` call; read-path recompute-on-miss
  returns a computed value when the cache row is absent.
- **Enforcement nudge** — P1 `Grab` triggers an immediate enforcement
  pass (assert the reconcile/enforce entrypoint is invoked once).
- Existing `backfill-sweep`, `OnGrabFollowup`, and `queue-janitor` tests
  must remain green (no behavioural change to P2–P5 paths).

## Rollback

Set `p1FastEnabled=false` (live) — the `p1-fast-sweep` job no-ops and the
P1-fast stall pass is skipped; the system reverts to the 2 h backfill +
48 h janitor behaviour. Part 4 (recompute-on-import) is a pure
correctness fix with no flag; if it ever needs disabling, revert the
handler change.

## Risks

- **Indexer rate-limits / bans.** Searching a P1 episode every 20 min for
  up to 48 h is ~144 searches worst-case if nothing is ever found. The
  1-h delay, the queued-skip (stops on grab), `p1FastMaxPerSweep` budget,
  and the 48 h ceiling bound this. P1-only keeps the candidate set small.
- **False-positive stall re-search.** A slow-starting healthy torrent
  could look idle at 30 min and get blocklisted. Mitigated by also
  requiring it to be a P1 managed download and by Sonarr's blocklist
  being reversible. 30 min is the user-chosen tradeoff for speed.
- **hasFile race on import.** Bounded retry handles the common case; if
  Sonarr is still mid-import past the retries, the next 30-min
  `refresh-priorities` corrects it (degrades to current behaviour, never
  worse).
