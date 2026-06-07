# Tdarr First-Class Integration (Phase 1)

**Date:** 2026-06-07
**Status:** Approved (design)

## Context

prioritarr already orchestrates Sonarr, Plex, Tautulli, qBittorrent, SAB, and
Trakt. A first iteration added a **Plex-aware Tdarr pause** job: a 1-minute
scheduler job that sets Tdarr's global `pauseAllNodes` flag `true` while Plex
has active sessions and `false` when idle, so background CPU transcoding yields
to live playback (this host has no GPU, so a Plex software-transcode and a Tdarr
encode otherwise starve each other). That first cut is **env-configured only**:
`PRIORITARR_TDARR_URL` + `PRIORITARR_TDARR_PAUSE_ENABLED`, a hardcoded 1-minute
cadence, no API key, and no presence in the UI.

This is **Phase 1 of three**:

1. **Tdarr first-class integration (this spec)** — promote the existing
   integration to match Sonarr/Plex/Trakt: Connections panel + test button,
   live-editable enable/cadence, optional API key, jobs catalog entry, README.
2. *(future)* Priority-driven AV1 conversion — order Tdarr's queue by prioritarr
   priority (P1 first), convert-on-import, convert-on-stream fallback.
3. *(future)* Priority-driven Bazarr subtitle search.

Phases 2–3 get their own specs. This spec covers Phase 1 only.

## Goal

Make Tdarr a first-class, UI-manageable integration consistent with every other
external service in prioritarr, with no behavioural change to the pause logic
itself beyond making its cadence live-editable.

## Non-goals

- Any priority-based queue ordering, convert-on-import, or convert-on-stream
  (Phase 2).
- Bazarr (Phase 3).
- Securing Tdarr itself. Tdarr auth is off by default; the API-key field is
  optional and forward-looking.

## Design

### Backend (`prioritarr/backend`)

**Connection test**
- Add `TDARR("tdarr")` to the `ConnectionService` enum (`Constants.kt`).
- Add `testTdarr(rawUrl, apiKey)` to `ConnectionTester.kt`, mirroring
  `testPlex`: POST `{rawUrl}/api/v2/cruddb` with body
  `{"data":{"collection":"SettingsGlobalJSONDB","mode":"getAll"}}`.
  - 2xx + body parses as a non-empty JSON array → `CONNECTED`.
  - 2xx but not a JSON array → `VERSION_FAILED` (wrong URL / unsupported Tdarr).
  - transport error → `CONNECTION_FAILED`.
  - If `apiKey` is set, send it as a header (best-effort; untested while Tdarr
    auth is off).
- Add the `TDARR` case to the `/connections/{service}/test` dispatch in
  `V2Routes.kt`, reading the draft `tdarrUrl`/`tdarrApiKey` like other services.

**Settings**
- Add `tdarrApiKey: String? = null` to `Settings` (alongside existing
  `tdarrUrl`, `tdarrPauseEnabled`), to `EditableSettings`, to
  `applySettingsOverride`, and to env loading (`PRIORITARR_TDARR_API_KEY`).
- Add `tdarrPauseMinutes: Int = 1` to the `Intervals` data class (+ YAML
  overlay), `EditableSettings`, and `applySettingsOverride`, so the cadence is
  live-editable like other job cadences.
- Expose `tdarrUrl` and a **masked** `tdarrApiKey` in `GET /settings`
  (`SettingsRedacted` schema in `schemas/V2.kt`) and accept them in
  `POST /settings` (`mergeEditable`), following the existing secret-masking
  pattern (`***` on read; null = "no change" on write).

**Client + job**
- `TdarrClient` gains an optional `apiKey`; when set it is sent as a header on
  each request (no-op while Tdarr auth is off).
- The `tdarr-plex-pause` job's `cadenceMinutes` reads
  `liveSettings(db, settings).intervals.tdarrPauseMinutes` instead of the
  hardcoded `1L`. Prerequisite is unchanged
  (`tdarrPauseEnabled && plexClient != null && tdarrClient != null`).
- `Main.kt` passes `settings.tdarrApiKey` into `TdarrClient`.

### Frontend (`prioritarr/frontend`)

- **Connections panel** (`SettingsPage.tsx`): add a Tdarr `ConnectionCard`
  (fields: `tdarrUrl` URL, `tdarrApiKey` optional secret) with the shared
  **Test connection** button (`service="tdarr"`).
- **Jobs catalog** (`lib/jobs.tsx`): add a `tdarr-plex-pause` entry (id matches
  backend `JobId.TDARR_PLEX_PAUSE`) — name, icon, `trigger: 'auto'`,
  short/description/why text, an **Enabled** toggle bound to `tdarrPauseEnabled`,
  and a cadence input bound to `tdarrPauseMinutes`. The existing settings UI
  renders enable/cadence from the catalog automatically.

### Documentation

- `README.md`: add a Tdarr line to the feature highlights and a row to the
  Background-jobs table describing the pause job + how to configure it
  (Connections → Tdarr, enable in Background jobs).

## Data flow (unchanged from Phase 0)

Every `tdarrPauseMinutes` interval, if enabled and both clients exist: the job
reads Plex `/status/sessions` (`activeSessionCount`), reads Tdarr's
`pauseAllNodes`, and writes the opposite only when they differ
(idempotent), logging transitions (`tdarr paused/resumed (plex sessions=N)`).

## Error handling

- Connection test failures map to the existing `ConnectionTestStatus` codes the
  UI already renders hints for.
- The job runs under the scheduler, which catches and records per-job
  exceptions as `JobStatus.ERROR` rows — a transient Plex/Tdarr error logs and
  retries next tick; it never crashes the app or other jobs.
- A short HTTP timeout (15s) on the Tdarr client keeps a hung Tdarr from
  stalling a scheduler tick.

## Testing

- **Unit:** `testTdarr` result mapping (connected / version-failed /
  connection-failed) using the existing connection-test test harness
  (MockEngine), mirroring the Plex test.
- **Build:** `:backend:compileKotlin` + existing suite; frontend type-check.
- **Manual / deploy verification:**
  - Connections → Tdarr → **Test connection** returns *connected*.
  - Toggling **Enabled** and changing cadence in the Background-jobs UI persists
    and takes effect within one tick (no restart).
  - Build image, deploy with a rollback tag, confirm `/health` healthy and the
    job still pauses/resumes (manually pause Tdarr → prioritarr auto-resumes
    when Plex idle, as verified in Phase 0).

## Rollout

Local image build (`scripts/build-image.sh`) + `compose up -d --no-deps
prioritarr`, with the prior image retained as a rollback tag. The compose env
(`PRIORITARR_TDARR_URL`, `PRIORITARR_TDARR_PAUSE_ENABLED`) stays as the baseline;
the new settings are DB-overridable on top.
