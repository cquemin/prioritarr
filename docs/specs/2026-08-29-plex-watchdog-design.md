# Plex Analysis Watchdog — Design

**Date:** 2026-08-29
**Status:** Implemented

## Problem

On 2026-08-29 a freshly imported episode (Bleach S17E46) showed no English
subtitle in Plex and refused to play, although the MKV carried six embedded
SRT tracks and an `.en.srt` sidecar sat next to it. Plex had recorded the item
with **zero media streams** — no video, no audio, no subs — so it could not
build a playback decision (`Failed to get a decision` in the server log).

Root cause: the Plex library database runs in SQLite WAL mode and its
`com.plexapp.plugins.library.db-wal` / `-shm` sidecars had vanished from the
9p-mounted `/config`. The long-running server process kept working on its
already-open handle, but every **child** `Plex Media Scanner` process
(analysis, credits detection, chapter thumbnails) failed at startup with
`unable to open database file … db-wal: No such file or directory`. Every item
added after that point (four episodes over ~24 h) was stored with no streams.
The server log said only `part has no video stream`.

Manual recovery was: `docker restart plex` (recreates the WAL on open), then
`PUT /library/metadata/{key}/analyze` on each affected item, then `/refresh`
so sidecar subtitles are registered.

## Goal

A prioritarr job that notices this state from the outside and performs that
same recovery automatically, with the same kind of confirmation and cooldown
discipline as the Sonarr watchdog so it can't restart-loop Plex or interrupt
someone's playback.

### Non-goals

- Finding out *what* deletes the WAL files (Docker Desktop / 9p suspicion;
  Plex logs had rotated past the moment it happened).
- Repairing the database or touching `/config` — prioritarr doesn't mount it
  and shouldn't.
- Watching anything other than "item has zero streams".

## Detection

`PlexWatchdog` reconcile job, `LIGHT`, every `plexWatchdogIntervalMinutes`
(default **5**). Each tick:

1. `GET /library/sections` → every `show` and `movie` section.
2. For each, `GET /library/sections/{id}/all?type={4|1}&sort=addedAt:desc`
   with `X-Plex-Container-Size: plexWatchdogRecentItems` (default **20**).
   The size must be a **header** — Plex ignores it as a query parameter and
   returns the whole library. Merge, newest first, take the top N overall.
3. Drop items younger than `plexWatchdogGraceMinutes` (default **10**) — a
   file mid-copy legitimately has no streams for a moment.
4. `GET /library/metadata/{key}` for the rest and count `<Stream>` elements.
   0 → *broken*.

A healthy tick therefore costs one sections call + one listing per library
+ ≤ N metadata gets, all read-only.

## Escalation ladder (`decidePlexWatchdog`, pure)

Per-key state: `analyzeRequestedAt: Map<key, Instant>`, plus
`containerRestartAt` and `cooldownUntil` for the incident.

| Condition | Action |
|---|---|
| no broken items | `NONE`, state reset ("healthy") |
| in cooldown | `NONE` |
| some broken key has no analyze yet (or its analyze predates the container restart) | `ANALYZE` those keys → `PUT …/analyze` then `PUT …/refresh` each |
| all analyzed, some analyze younger than `plexWatchdogAnalyzeWaitMinutes` (default **5**) | `NONE` — wait |
| all analyzed and stale, no restart yet, docker proxy configured, Plex sessions == 0 | `CONTAINER_RESTART` |
| … sessions > 0, or session probe failed (unknown = busy) | `NONE` — hold |
| restarted already (or no proxy) and still broken | `EXHAUSTED` → cooldown `plexWatchdogCooldownMinutes` (default **120**), state reset |

Sequence for a dead scanner, ticks 6 min apart:
`ANALYZE → CONTAINER_RESTART → ANALYZE → (recovered: healthy | EXHAUSTED)`.

### Why this is safe

- Nothing is written to Plex until an item has sat with zero streams past the
  grace window. Analyze/refresh are idempotent metadata operations.
- The container restart fires **once per incident**, only after an analyze
  demonstrably did nothing, and only with no active sessions. A failed
  session probe counts as busy.
- `dryRun` logs every rung without calling any seam.
- Plex unreachable (listing throws) → `noop`, state untouched. A single
  failed metadata probe skips that item only.

## Components

- `clients/Plex.kt` — `getRecentlyAdded`, `getStreamCount`, `analyzeItem`,
  `refreshItem` (new); `activeSessionCountOrNull` (existing).
- `orchestration/PlexWatchdogDecision.kt` — config/state/decision, pure.
- `orchestration/PlexWatchdog.kt` — reconciler with suspend seams.
- `clients/DockerRestart.kt` — existing; reused with `plexContainerName`.
- `Main.kt` — wiring + `JobId.PLEX_WATCHDOG` registration; prerequisite is
  `plexWatchdogEnabled && plexClient != null`.

## Settings

Env: `PLEX_WATCHDOG_ENABLED` (default false), `PLEX_CONTAINER_NAME` (default
`plex`), shares `DOCKER_PROXY_URL`. YAML/DB intervals:
`plex_watchdog_interval_minutes`, `plex_watchdog_grace_minutes`,
`plex_watchdog_analyze_wait_minutes`, `plex_watchdog_cooldown_minutes`,
`plex_watchdog_recent_items`. All editable at runtime via `EditableSettings`.

## Testing

- `PlexWatchdogDecisionTest` — every rung, busy/unknown session hold,
  re-analyze after restart, cooldown, full sequence.
- `PlexWatchdogReconcilerTest` — grace filtering, seam invocation order,
  session probe skipped when healthy, dry-run, unreachable Plex, per-item
  probe failure.
- `PlexClientTest` — header-based paging, episode naming, stream counting,
  PUT endpoints.
- `PlexWatchdogSettingsTest` — defaults + override.

## Rollout

Enabled in `media-stack-v3.yml` (`PRIORITARR_PLEX_WATCHDOG_ENABLED: "true"`).
Verify with the job's `job_runs` rows: a healthy library logs `healthy` noops;
the 2026-08-29 incident would have produced `analyze 4 item(s) with zero
streams` followed by `container-restart plex` within ~10 minutes.
