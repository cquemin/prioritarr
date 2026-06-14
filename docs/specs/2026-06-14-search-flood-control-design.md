# Search-Flood Control — Design

**Date:** 2026-06-14
**Status:** Approved (pending spec review)

## Problem

prioritarr's backfill machinery fires a flood of low-priority searches at
Sonarr. Sonarr runs commands on a small (~3) thread pool, so the flood fills
the executor and queue, and **time-sensitive P1/P2 episode searches (and the
user's manual searches, and `RefreshMonitoredDownloads`) get stuck behind it** —
or the executor wedges entirely. Observed today: today's One Piece episode (P1)
never got searched because the queue was saturated with backfill `SeriesSearch`
commands. The sonarr-watchdog recovers a full wedge, but the underlying flood
keeps inducing them and starves P1/P2.

## Goal

1. **Throttle** the backfill flood so it can never pile more than a few searches
   onto Sonarr at once.
2. **Preempt**: when P1/P2 work appears, cancel the in-flight backfill searches
   so the urgent episode searches run immediately.

### Non-goals

- Changing how priorities are computed, or which episodes are searched.
- Touching the download queue (this is about Sonarr's *command* queue).
- Cancelling P1/P2 searches — they are never cancelled.

## Key insight — command types separate cleanly

| Priority work | Sonarr command | Cancellable? |
|---|---|---|
| P1/P2 episode searches | `EpisodeSearch` | **Never** |
| P3/P4 backfill (Pass A2) | `SeriesSearch` | Yes |
| P5 ratchet (Pass B) | `SeriesSearch`, `SeasonSearch` (+ rare `EpisodeSearch`) | Yes (Series/Season only) |
| Cutoff sweep | `CutoffUnmetSearch` | Yes |

So "cancel the backfill searches" = cancel `started`/`queued`
`SeriesSearch` + `SeasonSearch` + `CutoffUnmetSearch`. Because P1/P2 use
`EpisodeSearch`, they are **structurally guaranteed** never to be cancelled.
(A rare P5-episode `EpisodeSearch` is also left alone — acceptable.)

Risk accepted: a user's *manual* full-series search is a `SeriesSearch` and
could be cancelled by the preemption. It is re-runnable; acceptable for a
homelab.

## Components

### SonarrClient (clients/Sonarr.kt)
- Add `cancelCommand(id: Long)` → `DELETE /api/v3/command/{id}` (reuses the
  existing private `delete` helper). `getCommands()` already exists.

### Pure helpers (orchestration/SearchQueueControl.kt)
Reuse the watchdog's `SonarrCommand` + `parseCommands` (already in the
`orchestration` package).

```
val BACKFILL_SEARCH_COMMANDS = setOf("SeriesSearch", "SeasonSearch", "CutoffUnmetSearch")
val ALL_SEARCH_COMMANDS      = BACKFILL_SEARCH_COMMANDS + "EpisodeSearch"
val ACTIVE_STATUSES          = setOf("queued", "started")

fun pendingSearchCount(cmds: List<SonarrCommand>): Int =
    cmds.count { it.name in ALL_SEARCH_COMMANDS && it.status in ACTIVE_STATUSES }

fun cancellableBackfillSearchIds(cmds: List<SonarrCommand>): List<Long> =
    cmds.filter { it.name in BACKFILL_SEARCH_COMMANDS && it.status in ACTIVE_STATUSES }.map { it.id }
```

### SearchQueueControl class (seam-injected, testable without HTTP)
```
class SearchQueueControl(
    private val getCommands: suspend () -> List<SonarrCommand>,
    private val cancelCommand: suspend (Long) -> Unit,
    private val threshold: () -> Int,        // searchCongestionThreshold, live
    private val dryRun: () -> Boolean,
) {
    suspend fun isCongested(): Boolean = pendingSearchCount(getCommands()) >= threshold()

    /** Cancel all cancellable backfill searches. Returns the count targeted. */
    suspend fun cancelBackfill(): Int {
        val ids = cancellableBackfillSearchIds(getCommands())
        if (!dryRun()) ids.forEach { cancelCommand(it) }
        return ids.size
    }
}
```
On a Sonarr API error in `getCommands`, both methods fail safe: `isCongested`
returns `false` (don't block sweeps on a transient error) and `cancelBackfill`
returns 0 — the caller proceeds as before. (The class catches and logs.)

## Behaviour

### Throttle — congestion gate (default threshold 3)
Before **low-priority** search work fires, if `isCongested()` (pending search
commands ≥ `searchCongestionThreshold`, default **3**, matching Sonarr's ~3
executor threads), it is skipped this cycle and logged as a noop:

- **BACKFILL_SWEEP**: skip Pass A2 (P3/P4) **and** Pass B (P5 ratchet). **Pass A1
  (P1/P2) always runs** — it is never gated.
- **CUTOFF_SWEEP**: skipped entirely when congested.

Counting `started`+`queued` (not just `started`) bounds the *backlog*, not just
the active set.

### Preempt — clear backfill for P1/P2
On **P1_FAST_SWEEP**, when `cancelBackfillForPriority` is enabled (default true)
**and the sweep has P1 candidates to fire**, it calls `cancelBackfill()` first,
then fires the P1 `EpisodeSearch`es into the freed executor slots. Cancellation
is gated on "P1 work exists this sweep" so backfill is not killed during quiet
periods (which would prevent backfill from ever completing).

The on-grab P1/P2 follow-up and the QueueJanitor P1-fast re-search are left as-is
(they already fire `EpisodeSearch` and are low-volume).

## Settings (mirror tdarrPause*/watchdog plumbing)

| Setting | Type | Default | Location |
|---|---|---|---|
| `searchCongestionThreshold` | Int | 3 | `Intervals` |
| `cancelBackfillForPriority` | Bool | true | `Settings` |

Env: `PRIORITARR_SEARCH_CONGESTION_THRESHOLD`,
`PRIORITARR_CANCEL_BACKFILL_FOR_PRIORITY`. YAML under `intervals:` /
top-level, same pattern as `tdarr_pause_minutes` / `tdarrPauseEnabled`.
DB-override via `EditableSettings` + `applySettingsOverride`. Respects global
`dryRun`.

## Wiring (Main.kt)

Construct one `SearchQueueControl` (uses `sonarr.getCommands` /
`sonarr.cancelCommand`, threshold + dryRun from `liveSettings`). Then:

- **BACKFILL_SWEEP** `run`: pass a `lowPriorityCongested = searchQueueControl.isCongested()`
  flag into `runBackfillSweep(...)`; the sweep skips Pass A2 + Pass B when true
  (Pass A1 unaffected). Summarise skips.
- **CUTOFF_SWEEP** `run`: `if (searchQueueControl.isCongested()) noop else runCutoffSweep(...)`.
- **P1_FAST_SWEEP** `run`: pass `searchQueueControl` into `runFastP1Sweep(...)`;
  after it computes candidates, if candidates non-empty and
  `cancelBackfillForPriority`, call `cancelBackfill()` before the fire loop.

`runBackfillSweep` and `runFastP1Sweep` get minimal new parameters
(`lowPriorityCongested: Boolean` / `searchQueueControl: SearchQueueControl?` +
`cancelBackfillForPriority: Boolean`) — additive, defaulted so existing tests
compile.

## Testing (TDD)

Pure / seam-based, mirroring the watchdog tests:

1. **pendingSearchCount** — counts started+queued across all 4 search types;
   ignores non-search commands and completed ones.
2. **cancellableBackfillSearchIds** — returns Series/Season/CutoffUnmet ids;
   **never includes EpisodeSearch** (explicit regression: a queued P1
   `EpisodeSearch` is never in the set).
3. **SearchQueueControl.isCongested** — true at/above threshold, false below;
   false on getCommands error.
4. **SearchQueueControl.cancelBackfill** — cancels each backfill id, returns
   count; dry-run cancels nothing but still returns the count; 0 on error.
5. **Backfill gate** — when congested, Pass A2/B are skipped and Pass A1 still
   runs (test the `runBackfillSweep` branch via its existing test seams).
6. **P1-fast preempt** — with P1 candidates + backfill in queue, cancelBackfill
   is invoked before the EpisodeSearch fire; with no candidates, it is not.

Full backend suite stays green.

## Rollout

Defaults make it active immediately (threshold 3, preemption on) — but both are
live-tunable and can be disabled via settings. No infra change. Deploy via the
local build + `compose up --no-deps prioritarr` flow (and prioritarr is now
excluded from watchtower, so the build won't be reverted).
