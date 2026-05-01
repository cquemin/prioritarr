# Subtitle Orchestration, Provider Health Banners, and Priority Coverage Flags

Date: 2026-04-30
Status: Design — captures Phase 2 + Phase 3 of the subtitle stack plus
two new requests (provider-down banner, no-priority-info flag).

## Background

After Phase 1 (deploying Whisper ASR + Lingarr alongside Bazarr), three
cross-cutting Prioritarr capabilities are needed:

1. **Priority-aware subtitle orchestration.** Drive Bazarr searches in
   the order Prioritarr already ranks series, fast lane on import for
   high-priority items, slow-lane backfill for the rest. Two new user
   thresholds — one for fast-lane eligibility, one for AI-fallback
   eligibility — surfaced in the Settings page as priority dropdowns.
2. **Provider health banner.** When an upstream Prioritarr depends on
   (Plex, Tautulli, Trakt, Sonarr, qBit, SAB) is unavailable or
   unauthenticated, surface a dismissable banner with one entry per
   broken provider. Each entry deep-links to the matching settings
   detail view so the user can re-auth. Dismissal is per-session;
   reload re-shows.
3. **Priority coverage flag.** When a series has no signal Prioritarr
   can use to score it (e.g. *Re:Zero* — files exist on disk, watched
   on Plex+Trakt, but neither provider is reporting that watch
   activity to Prioritarr because of a sync gap), flag it visibly so
   the user can investigate. Distinguish from a genuinely
   never-watched series.

Phase 3 (anime-fansub-style translation via Whisper-JP → Lingarr) is
covered separately in the original spec (linked at the bottom).

## Goals

- Subtitle search runs in priority order, automatically, without
  manual triggers
- Two threshold knobs in the existing Settings UI (no separate page)
- User notices when an integration breaks before they go look in
  Sonarr/Radarr/Plex
- User notices when Prioritarr has no data to score a series, so they
  can act (re-link Plex, fix Trakt token, etc.)

## Non-goals

- Replacing Bazarr's provider config or duplicating its language
  profile UX
- Solving "anime fansub style" — see the original Phase 3 spec
- Cross-instance failover for Whisper / Lingarr

## Architecture overview

```
                     ┌──────────────────────────────────────┐
   Sonarr OnImport ──▶│ SubSearchOrchestrator (new in P2)   │
                     │                                      │
                     │  ┌─ fast lane (P ≤ fastThreshold) ───┼──▶ POST Bazarr /api/episodes search
                     │  │                                    │
                     │  └─ slow lane drainer (rest)          │──▶ POST Bazarr /api/episodes search
                     │                                       │
                     │  AI providers toggled per-search      │
                     │  based on aiThreshold                 │
                     └───────────────────────────────────────┘

   Periodic poll (every 5 min) ──▶ HealthMonitor (new) ──▶ /api/v2/health/providers
                                                              ▲
   UI ── poll on load + on focus ─────────────────────────────┘
        ▼
        ┌─ banner stack: 1 entry per ProviderStatus.unhealthy
        └─ click → routes to /settings#<provider-anchor>

   PriorityCalc (existing) ──▶ for each series:
        if no signal AND files-on-disk AND not-recently-added:
            mark coverageFlag = "missing_signal"
            surface in /api/v2/series and UI
```

## Component details

### A — Subtitle orchestrator (Phase 2)

#### New settings (extend existing `Settings` model)

| Key | Type | Default | UI |
|---|---|---|---|
| `subs.fastLanePriorityThreshold` | enum P1..P5 \| Disabled | `P3` | dropdown |
| `subs.aiFallbackPriorityThreshold` | enum P1..P5 \| Disabled | `P3` | dropdown |

Persisted in the same store the existing settings live (DB or YAML —
match the pattern in `prioritarr/backend/src/main/kotlin/.../config/`).

#### New backend pieces

- `BazarrClient.kt` — Ktor client for:
  - `POST /api/episodes` (manual search, `seriesid`+`episodeid` form)
  - `POST /api/movies` (manual search, `radarrid` form)
  - `GET /api/episodes/wanted?length=N` (paged backfill source)
  - `POST /api/system/settings` (toggle `whisperai`/`translator` in
    `enabled_providers` for a single search — wrapped in a mutex)
  - `GET /api/system/health` for the provider-health monitor
- `SubSearchOrchestrator.kt` — coroutine-based, two channels:
  - `fastLane: Channel<EpisodeRef>` — capacity 50, drained by 2
    workers
  - `slowLane: scheduled` — every 30 min if fast lane idle for 60s,
    pulls oldest 10 from `wanted`
- Hook into existing Sonarr OnGrab/OnImport receiver — extend to
  branch by priority lookup before enqueuing
- `/api/v2/subs/status` endpoint — queue depths, last-run timestamps,
  current AI toggle state. Used by Settings status card.

#### UI changes

- Settings page → new "Subtitles" section (or extend an existing
  "Integrations" section if it fits) with:
  - Two priority dropdowns described above
  - Status card: queue depths + last whisper run timestamp
- Generated TS API types via `npm run gen:api`

### B — Provider health banner (new)

#### Detection: `HealthMonitor.kt`

Polls every 5 minutes (configurable). For each provider Prioritarr
already reads from, runs a tiny health probe:

| Provider | Probe | Unhealthy when |
|---|---|---|
| Sonarr | `GET /api/v3/system/status` | non-200 or auth-fail |
| Radarr | `GET /api/v3/system/status` | non-200 or auth-fail |
| Plex | `GET /identity` | non-200 |
| Tautulli | `GET /api/v2?cmd=arnold` | non-200 or `result != success` |
| Trakt | `GET /users/settings` (already used by app) | 401 indicates token expired |
| qBit | existing health code | unauthenticated/connection-refused |
| SAB | `GET /api?mode=version` | non-200 |
| Bazarr (new dep) | `GET /api/system/ping` | non-200 |
| Whisper (new dep) | `GET /docs` | non-200 |
| Lingarr (new dep) | TCP probe `lingarr:8080` | refused |

Status persisted to `provider_health` table (existing audit pattern):
`provider, status (ok/unauth/unreachable), last_ok, last_check,
detail`.

#### `GET /api/v2/health/providers`

Returns a list shaped:

```json
{
  "providers": [
    {
      "name": "trakt",
      "status": "unauth",
      "label": "Trakt",
      "settingsAnchor": "trakt-settings",
      "detail": "Token expired at 2026-04-29T03:12:00Z",
      "lastOkAt": "2026-04-28T22:00:00Z"
    },
    ...
  ]
}
```

#### UI

- New `<HealthBanner>` component, mounted at the app shell (visible on
  every page).
- Polls `/api/v2/health/providers` on mount + on window focus (every
  60s while focused).
- Renders one row per `status != ok` provider. Each row:
  - Provider label + status pill ("Re-auth required" / "Unreachable")
  - One-line detail (e.g. *"Trakt token expired 2 days ago"*)
  - Click → router push to `/settings#<settingsAnchor>` + open the
    matching detail view
  - "Dismiss" button per row (not "dismiss all"). Dismissal stored in
    `sessionStorage` so it reappears on full reload.
- Multi-provider stack: rows accumulate, each independently
  dismissable.
- Dismissal lifetime: cleared on page reload, on app restart, and on
  any provider transitioning healthy→unhealthy (re-shows so user
  notices a *new* failure even if they dismissed a different one).

### C — Priority coverage flag (new)

#### The detection problem

A genuine never-watched series and a "stuck-sync" series look
identical from Prioritarr's perspective: zero watch events, zero
priority signal. The user wants distinguishing **probable sync gaps**
(*Re:Zero*: many files on disk, oldest > 30 days, but Plex+Trakt both
return zero watch history) from **genuine new content** (recently
added, never opened).

Heuristic to flag a series:

```
flag = "missing_signal" iff ALL of:
  - episodeFileCount > 0
  - oldest episode file mtime > 30 days
  - Plex history for this series == 0 events
  - Trakt watched for this series == 0 events
  - sonarr.added > 60 days OR statistics.episodeFileCount > 5
```

The last clause weeds out genuinely brand-new shows you just got
around to grabbing.

#### Backend changes

- Extend `PriorityCalc` to compute `coverageFlag` per series:
  - `null` (data is fine, computed priority normally)
  - `"missing_signal"` (matches the heuristic above)
  - `"no_files"` (files all deleted but series kept — different case)
- Surface in the existing `/api/v2/series/{id}` payload as
  `coverageFlag: string | null`
- Aggregate count in `/api/v2/stats` so the homepage widget can
  display it

#### UI changes

- Series table: new column "Coverage" with an info-pill when
  `coverageFlag != null`. Tooltip explains:
  *"Files exist but no Plex/Trakt watch history was found.
   Possible Plex sync gap or Trakt token expired."*
- Series detail view: top-of-page warning callout with same text + a
  diagnostic block:
  - When did Plex last update its library? (from Tautulli /
    `Library.refresh` event)
  - When was the last Trakt sync? (from app's own audit log)
  - "Re-link Plex" / "Re-auth Trakt" CTA buttons (link to settings
    anchors via the same routing the banner uses)

#### Edge cases / open questions

- A series the user *intentionally* never watched but has on disk
  (downloaded for a friend, kept for archival): the heuristic would
  false-flag it. Need a per-series "ignore coverage flag" override.
  Add as a checkbox on the series detail view; persisted in DB.
- Series with sparse watch history (watched once 6 months ago):
  shouldn't trigger the flag. The heuristic only fires when watch
  count is zero.

## Dependencies between features

```
A (subtitle orchestrator)
  ├─ depends on: BazarrClient (new)
  └─ exposes: /api/v2/subs/status

B (health banner)
  ├─ depends on: BazarrClient + WhisperClient + LingarrClient (new
  │             clients for the new deps; existing for Sonarr/etc.)
  └─ exposes: /api/v2/health/providers

C (coverage flag)
  ├─ depends on: existing PriorityCalc + Plex/Trakt clients
  └─ extends: /api/v2/series, /api/v2/stats
```

A and B can be built in parallel. C depends on neither and can ship
first or last. Recommendation: ship in order **B → C → A** so the
user gets the most-immediate value (visibility into broken
integrations) before the orchestration that depends on those
integrations being healthy.

## Implementation phases (revised)

| Phase | What | Sessions |
|---|---|---|
| 1 ✅ | Whisper + Lingarr deployed (already done) | done |
| 2a | Provider health banner (B) | 1 session |
| 2b | Coverage flag (C) | 1 session |
| 2c | Subtitle orchestrator + settings dropdowns (A) | 1–2 sessions |
| 3 | Anime fansub-style translation (Whisper-JP + Lingarr prompts) | 1 session of prompt iteration + 1 session of integration |

## References

- Original three-phase spec: in conversation history,
  see message dated 2026-04-29 covering Phases 1–3.
- Existing Prioritarr API: `D:\docker\prioritarr\openapi.json`
- Bazarr settings POST format: see
  `/app/bazarr/bin/bazarr/api/system/settings.py` (form fields
  `settings-<section>-<key>`, plus `languages-enabled` and
  `languages-profiles` special cases).
