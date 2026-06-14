# Sonarr Watchdog — Design

**Date:** 2026-06-14
**Status:** Approved (pending spec review)

## Problem

Sonarr's command executor can wedge: one or more commands get stuck in the
`started` state indefinitely (observed: a `SeriesSearch` blocked for **8.5
hours** on an unresponsive indexer call that ignored its cancellation token).
Sonarr runs commands on a small thread pool, so a few wedged tasks gridlock the
whole queue — `RefreshMonitoredDownloads`, `ManualImport`, `RssSync`, everything
backs up behind them and never executes. Concretely this stalled the import of a
completed download (Wistoria S02E10): SAB had finished it, but Sonarr never
imported it and even a manually-triggered import sat `queued` forever.

The command API stays *responsive* during this failure (we could read
`/api/v3/command` fine) — only the executor threads are wedged. The soft-cancel
(`DELETE /api/v3/command/{id}`) does **not** clear them. The only reliable
recovery is restarting Sonarr.

## Goal

A prioritarr watchdog that detects this wedge and auto-recovers it via an
escalating restart ladder, with enough confirmation and cooldown to avoid false
positives and restart loops.

### Non-goals

- Diagnosing *why* commands wedge (bad indexer, etc.) — out of scope.
- Watchdogging other services (Radarr/Lidarr) — Sonarr only for now; the design
  should be straightforward to generalise later but we don't build that.
- Replacing Sonarr's own health system or autoheal.

## Detection

A new **`SonarrWatchdog`** reconcile job, `LIGHT` weight, runs every
`sonarrWatchdogIntervalMinutes` (default **5**). Each tick:

1. `GET /api/v3/command` → collect commands where `status == "started"` and
   `now − started > stallMinutes` (default **30**).
2. Non-empty set ⇒ **wedged**. (No legitimate Sonarr command — search, refresh,
   rescan — runs 30 minutes; the wedged one we saw ran 8.5h.)

The `started` timestamp (not `queued`) is the reference. Commands without a
`started` timestamp (still queued) are ignored — a backed-up *queue* is a
*symptom*; the stuck `started` command is the *cause* we key on.

## Escalation ladder

Recovery escalates: **app-restart ×3, then container-restart**, exactly as
requested. State is carried across ticks by the reconciler instance:

| Field | Meaning |
|---|---|
| `confirmStreak` | consecutive wedged ticks (capped at `CONFIRM_CHECKS`) |
| `appRestartCount` | app-restarts performed in the current episode (0–3) |
| `containerRestartDone` | whether the container fallback has fired this episode |
| `lastActionAt` | time of the last restart action (grace timer) |
| `cooldownUntil` | if set, hold all action until this time (ladder exhausted) |

`CONFIRM_CHECKS = 2`. Timers (all configurable):

- **`restartGraceMinutes`** = 10 — wait between rungs so a restart can take
  effect (Sonarr returns in ~1 min) before we re-evaluate and escalate.
- **`cooldownMinutes`** = 120 — after the full ladder is exhausted and Sonarr is
  *still* wedged, hold for this long before starting a fresh campaign. Prevents
  restart loops. (This is a *post-ladder* cooldown, **not** between every rung —
  2h between rungs would make the ladder take 8h.)

### Per-tick decision (`reconcile()` at time `now`)

```
stuck = getStuckCommands()                 // may throw if Sonarr unreachable
wedged = stuck.isNotEmpty()

if Sonarr API call failed (exception):
    return noop("sonarr unreachable; skipping")   // NO state change — likely mid-restart

if not wedged:
    if any counter non-zero: log "sonarr recovered"
    reset confirmStreak, appRestartCount, containerRestartDone, lastActionAt, cooldownUntil
    return noop("healthy")

// wedged
confirmStreak = min(confirmStreak + 1, CONFIRM_CHECKS)

if cooldownUntil != null and now < cooldownUntil:
    return noop("ladder exhausted; cooling down until {cooldownUntil}")

if confirmStreak < CONFIRM_CHECKS:
    return noop("wedge seen; confirming (streak={confirmStreak})")

if lastActionAt != null and now < lastActionAt + restartGraceMinutes:
    return noop("waiting for last restart to take effect")

// confirmed wedge, grace elapsed → escalate one rung
if appRestartCount < 3:
    if dryRun: log "would app-restart (#{appRestartCount+1}); stuck={stuck ids}"
    else:      sonarr.restartApp()
    appRestartCount++ ; lastActionAt = now
    return action("app-restart #{appRestartCount}")

if not containerRestartDone:
    if containerRestart == null:                  // docker proxy not configured
        log.warn "container fallback unavailable (no docker proxy); skipping rung"
        containerRestartDone = true               // fall through to exhausted/cooldown
    else:
        if dryRun: log "would container-restart sonarr"
        else:      docker.restartContainer(sonarrContainerName)
        containerRestartDone = true ; lastActionAt = now
        return action("container-restart fallback")

// ladder exhausted: 3 app-restarts + 1 container-restart (or proxy absent), still wedged
log.error "sonarr watchdog: ladder exhausted, sonarr still wedged; holding {cooldownMinutes}m"
cooldownUntil = now + cooldownMinutes
appRestartCount = 0 ; containerRestartDone = false ; lastActionAt = null
return action("ladder exhausted; cooldown until {cooldownUntil}")
```

### Why this is safe

- **Confirmation** (`CONFIRM_CHECKS = 2`): a command must be wedged across two
  ticks (~5 min apart) before any restart, so a merely-slow command that crosses
  30 min but then finishes never triggers a restart. Net: a command is wedged
  ~35–40 min before the first action.
- **Recovery resets the ladder**: a successful restart yields a tick with no
  `>30min` command (fresh commands are young) ⇒ the *healthy* branch resets all
  counters. Escalation therefore only continues if restarts genuinely fail.
- **Grace between rungs**: we never fire two restarts within `restartGraceMinutes`.
- **Post-ladder cooldown**: if even a container restart doesn't help, we stop and
  alert for 2h rather than looping.
- **Unreachable ≠ recovered**: while Sonarr is mid-restart the API throws; we
  treat that as "skip, no state change" so we neither lose ladder progress nor
  false-reset.

## Restart mechanisms

### App-restart (rungs 1–3) — `POST /api/v3/system/restart`

prioritarr's existing `SonarrClient` already authenticates to Sonarr; we add a
`restartApp()` method. This restarts the Sonarr *process* inside its container,
which is what cleared the wedge in the incident (verified manually). No infra
change.

### Container-restart (fallback) — via docker-socket-proxy

prioritarr has **no Docker access** today, and the stack has **no socket-proxy
container** (the `socket_proxy` network exists but is dormant; 4 services mount
`/var/run/docker.sock` directly). We add a scoped proxy rather than give
prioritarr the raw socket.

**Infra change (`media-stack-v3.yml`):**

- New service `dockerproxy` (`tecnativa/docker-socket-proxy`) on the
  `socket_proxy` network, mounting `/var/run/docker.sock:/var/run/docker.sock:ro`,
  env `CONTAINERS=1`, `POST=1` (everything else default-deny), listening on 2375.
- Attach `prioritarr` to the `socket_proxy` network (in addition to `t2_proxy`).
- prioritarr env: `PRIORITARR_DOCKER_PROXY_URL=http://dockerproxy:2375`,
  `PRIORITARR_SONARR_CONTAINER_NAME=sonarr`.

prioritarr's container fallback is then a plain HTTP `POST
http://dockerproxy:2375/containers/{name}/restart` via a tiny new
`DockerRestartClient` (reuses the existing ktor `HttpClient`). No Unix-socket
code, no docker-java dependency.

**Security note:** `docker-socket-proxy` with `POST=1` + `CONTAINERS=1` permits
container operations (restart/stop/kill) on *any* container, not strictly
"restart sonarr". That proxy has no finer grain, but it is still vastly narrower
than mounting the raw socket, and prioritarr only ever issues a restart of the
configured container name.

## Settings

Mirrors the `tdarrPause*` plumbing exactly (baseline `Settings` /
`Intervals`, nullable `EditableSettings` override, `applySettingsOverride`, env
parsing, YAML parsing):

| Setting | Type | Default | Where |
|---|---|---|---|
| `sonarrWatchdogEnabled` | bool | `false` | `Settings` |
| `sonarrWatchdogIntervalMinutes` | int | `5` | `Intervals` |
| `sonarrWatchdogStallMinutes` | int | `30` | `Intervals` |
| `sonarrWatchdogRestartGraceMinutes` | int | `10` | `Intervals` |
| `sonarrWatchdogCooldownMinutes` | int | `120` | `Intervals` |
| `dockerProxyUrl` | string? | `null` | `Settings` (connection) |
| `sonarrContainerName` | string | `sonarr` | `Settings` |

Env keys: `PRIORITARR_SONARR_WATCHDOG_ENABLED`,
`PRIORITARR_SONARR_WATCHDOG_*_MINUTES`, `PRIORITARR_DOCKER_PROXY_URL`,
`PRIORITARR_SONARR_CONTAINER_NAME`. YAML mirrors under `intervals:` (snake_case)
like `tdarr_pause_minutes`.

**Prerequisite** for the job (live-read each tick, like `TDARR_PLEX_PAUSE`):
`sonarrWatchdogEnabled && sonarrClient != null`. The container fallback
additionally requires `dockerProxyUrl != null`; if it's unset, rungs 1–3
(app-restart) still run and the container rung is skipped with a logged warning
(graceful degradation to "option C" behaviour).

**Dry-run:** respects global `dryRun`. When true, every restart is logged as
"would …" and no HTTP call fires; ladder counters still advance so the dry-run
log shows the full escalation it *would* perform.

## Components

- **`SonarrClient`** (`clients/Sonarr.kt`): add
  - `getCommands(): List<SonarrCommand>` → `GET /api/v3/command`
  - `restartApp()` → `POST /api/v3/system/restart`
  - a small `SonarrCommand` shape (`id`, `name`, `status`, `started: Instant?`).
- **`DockerRestartClient`** (`clients/DockerRestart.kt`): `restartContainer(name)`
  → `POST {proxyUrl}/containers/{name}/restart`. Constructed only when
  `dockerProxyUrl` is set.
- **`SonarrWatchdog`** (`orchestration/SonarrWatchdog.kt`): the stateful
  reconciler. Dependencies are narrow suspend seams so it's unit-testable
  without HTTP, mirroring `TdarrPlexPause`:
  ```
  class SonarrWatchdog(
      getStuckCommands: suspend () -> List<SonarrCommand>,
      appRestart: suspend () -> Unit,
      containerRestart: (suspend () -> Unit)?,   // null when proxy not configured
      now: () -> Instant,
      stallMinutes / graceMinutes / cooldownMinutes / confirmChecks,
      dryRun: () -> Boolean,
  ) { suspend fun reconcile(): JobOutcome }
  ```
  The stall filter (`status==started && now−started>stall`) is a pure helper
  tested independently.
- **`Main.kt`**: construct `SonarrWatchdog` once (carries state), register a
  `JobDefinition` with `id = JobId.SONARR_WATCHDOG`, `cadenceMinutes` from
  settings, the live prerequisite, `LIGHT` weight, `run = { watchdog.reconcile() }`.
- **`Constants.kt`**: add `const val SONARR_WATCHDOG = "sonarr-watchdog"`.

## Testing (TDD)

Pure / seam-based unit tests (no HTTP), mirroring `TdarrPlexPauseReconcilerTest`:

1. **stall filter** — a `started` command older than threshold is detected; a
   young one and a `queued`/`completed` one are not.
2. **confirm gate** — one wedged tick takes no action; the second fires app-restart #1.
3. **escalation order** — sustained wedge across ticks (respecting grace) fires
   app-restart #1, #2, #3, then container-restart, in that order.
4. **recovery resets** — a healthy tick mid-ladder clears all counters; a later
   wedge starts from app-restart #1 again.
5. **grace** — no second restart fires within `graceMinutes` of the previous.
6. **cooldown** — after the ladder is exhausted, no action until
   `cooldownMinutes` passes, then a fresh campaign begins.
7. **dry-run** — no restart seam is invoked; counters still advance.
8. **proxy absent** — `containerRestart == null` ⇒ ladder stops after 3
   app-restarts and logs, never NPEs.
9. **unreachable** — `getStuckCommands` throwing ⇒ noop, no state change.

Full backend suite must stay green.

## Rollout

1. Ship code with `sonarrWatchdogEnabled=false` (default) — inert until enabled.
2. Add the `dockerproxy` service + network wiring to `media-stack-v3.yml`.
3. Enable with `dryRun=true` first to observe detection/escalation logging on a
   real wedge (or a forced one), then flip to live.
