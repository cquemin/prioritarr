# Prioritarr

Priority-aware download queue orchestrator for Sonarr + qBittorrent + SABnzbd.

Automatically prioritizes downloads based on your Plex watch history. Shows you're actively watching get downloaded first; backfill work waits.

## How it works

Prioritarr sits between Sonarr and your download clients. When Sonarr grabs an episode, prioritarr computes a priority (P1-P5) based on your Plex watch history via Tautulli, then reorders the qBittorrent and SABnzbd queues accordingly.

### Priority levels

| Priority | Name | Condition | Example |
|----------|------|-----------|---------|
| **P1** | Live-following | >= 90% watched, last watched <= 14 days ago, new episode released <= 7 days (or after hiatus <= 28 days) | Weekly anime you're current on |
| **P2** | Caught-up but lapsed | >= 90% watched, last watched 14-60 days ago | Show you follow but haven't opened in a few weeks |
| **P3** | A few unwatched | 1-3 unwatched episodes, last watched <= 60 days ago | A couple episodes behind, still engaged |
| **P4** | Partial backfill | Some episodes watched, more than 3 unwatched | Catching up on a show you've started |
| **P5** | Full backfill / dormant | Never watched, or dormant > 60 days | New library additions, old shows |

The decision tree runs top-down (first match wins). All thresholds are configurable in `prioritarr.yaml`.

**Watch data sources (in order):**
1. **Plex direct** -- queries Plex for current episode watch status (`viewCount`, `lastViewedAt`). Always accurate, covers periods when Tautulli was offline.
2. **Tautulli history** -- play-by-play watch history. Tried first by Plex rating key, then by title match (handles Plex server migrations where rating keys changed).

**Series matching (Sonarr to Plex):**
1. **TVDB ID** -- via Tautulli `get_metadata` GUIDs. Unambiguous, covers 99% of shows.
2. **Folder path** -- Sonarr series folder name matched against Plex file paths.
3. **Title** -- normalized title comparison as last resort.

Matched mappings are persisted in SQLite (`mapping_cache` table) so subsequent refreshes skip the expensive `get_metadata` calls.

### Queue enforcement

**SABnzbd** -- maps directly to SAB's native priority system:

| Prioritarr | SAB |
|------------|-----|
| P1 | Force (bypasses pauses) |
| P2 | High |
| P3 | Normal |
| P4 | Low |
| P5 | Low (pushed to bottom) |

**qBittorrent** -- uses pause/resume since qBit allows 40+ concurrent downloads:

| Highest active priority | Action |
|------------------------|--------|
| P1 in flight | Pause P4 + P5 torrents |
| P2 in flight (no P1) | Pause P5 torrents |
| Only P3/P4/P5 | Nothing paused |

Torrents paused by prioritarr are tracked (`paused_by_us` flag) and automatically resumed when higher-priority work finishes. User-paused torrents are never touched.

### Backfill search ordering

Prioritarr takes over Sonarr's missing-episode search scheduling. Every 2 hours, it queries Sonarr for missing episodes, groups by series, computes priority, and triggers searches in P1-first order (max 10 per sweep, 30s between each).

## Setup

### Prerequisites

- Docker + Docker Compose
- Sonarr v4
- qBittorrent and/or SABnzbd
- Plex Media Server
- Tautulli (can restore from existing config or fresh install)

### 1. Docker Compose

Add to your `docker-compose.yml`:

```yaml
prioritarr-app:
  image: ghcr.io/cquemin/prioritarr-app:latest
  container_name: prioritarr-app
  volumes:
    - /path/to/docker/config/prioritarr-app:/config
  environment:
    PRIORITARR_SONARR_URL: http://sonarr:8989         # include /sonarr if using URL base
    PRIORITARR_SONARR_API_KEY: ${SONARR_API_KEY}
    PRIORITARR_TAUTULLI_URL: http://tautulli:8181
    PRIORITARR_TAUTULLI_API_KEY: ${TAUTULLI_API_KEY}
    PRIORITARR_QBIT_URL: http://vpn:8080               # or wherever qBit WebUI is
    PRIORITARR_QBIT_USERNAME: ${QBIT_USERNAME}
    PRIORITARR_QBIT_PASSWORD: ${QBIT_PASSWORD}
    PRIORITARR_SAB_URL: http://sabnzbd:8080
    PRIORITARR_SAB_API_KEY: ${SAB_API_KEY}
    PRIORITARR_PLEX_URL: http://plex:32400              # optional, for direct watch status
    PRIORITARR_PLEX_TOKEN: ${PLEX_TOKEN}                # optional
    PRIORITARR_API_KEY: ${PRIORITARR_API_KEY}           # required to access /api/v2/*
    PRIORITARR_DRY_RUN: "true"                          # start in dry-run!
    PRIORITARR_LOG_LEVEL: INFO                          # DEBUG for verbose
    TZ: ${TZ}
  healthcheck:
    test: ["CMD", "curl", "-fsS", "http://localhost:8000/health"]
    interval: 30s
    timeout: 5s
    retries: 3
    start_period: 60s
  labels:
    - autoheal=true
  depends_on:
    sonarr:
      condition: service_healthy
    tautulli:
      condition: service_healthy
  restart: unless-stopped
```

### 3. Configure webhooks

**Sonarr** -- Settings > Connect > Add > Webhook:
- Name: `Prioritarr`
- On Grab: enabled (everything else disabled)
- URL: `http://prioritarr:8000/api/sonarr/on-grab`
- Method: POST

**Tautulli** -- Settings > Notification Agents > Add > Webhook:
- Webhook URL: `http://prioritarr:8000/api/plex-event`
- Triggers: Watched
- Body (Watched):
```json
{
  "event": "watched",
  "user": "{user}",
  "grandparent_title": "{show_name}",
  "grandparent_rating_key": "{grandparent_rating_key}",
  "parent_media_index": "{season_num}",
  "media_index": "{episode_num}",
  "rating_key": "{rating_key}"
}
```

### 4. Deploy

```bash
# Start in dry-run mode first
docker-compose up -d prioritarr

# Watch the logs
docker logs -f prioritarr
```

### 5. Go live

Once you're satisfied with the dry-run output:

```bash
# Set PRIORITARR_DRY_RUN=false in your .env or compose
docker-compose up -d prioritarr
```

## Monitoring via logs

Prioritarr logs structured JSON to stdout. Use `docker logs prioritarr` to monitor.

### Key log messages to look for

**Startup:**
```
prioritarr (kotlin) starting (dry_run=false, test_mode=false)
Plex direct client configured at http://plex:32400
```

**Mapping refresh (hourly):**
```
refreshMappings: 424 sonarr series, 223 plex shows, 221 matched (cached=221, tvdb=0, path=0, title=0, unmatched=2)
```
- `cached` = loaded from the SQLite `mapping_cache` table (fast)
- `tvdb` = matched by TVDB ID this cycle
- `unmatched` = Plex shows with no Sonarr match (normal for non-Sonarr content)

**OnGrab webhook (when Sonarr grabs an episode):**
```
[grab] Attack on Titan -> P1 via qbit/ABCDEF12 (episodes: [101])
[grab] Bungo Stray Dogs -> P5 via sab/SABnzbd_nzo_ (episodes: [38269]) [DRY RUN]
```

**Plex watched event:**
```
[watched] cache invalidated for series 42 (Plex episode finished)
```

**Reconcile loop (every 15 min):**
```
[qbit] reconcile: 979 items in client queue
[qbit] adopted orphan abc123def4 -> series 42, assigned P1
[qbit] priority changed abc123def4: P5 -> P1 (watch=95%, last_watch=2d...)
```

**Queue enforcement:**
```
[qbit] PAUSE xyz789ghi0 (P5 torrent, higher priority active)
[qbit] RESUME xyz789ghi0 (no longer needs to be paused)
[qbit] TOP_PRIORITY abc123def4 (P1 item)
[sab] SET_PRIORITY SABnzbd_nzo_ -> Force (P1)
```

**Plex direct fallback:**
```
_build_series_snapshot: 'One Piece' Plex direct has more data (plex=84 vs tautulli=0 watched), using Plex
_build_series_snapshot: 'One Piece' Plex has newer watch date (plex=2026-04-07 vs tautulli=2022-01-09)
```

**Backfill sweep (every 2h):**
```
[backfill] 1000 missing episodes across 137 series (max 10 searches this sweep)
[backfill] priority breakdown: P1=2, P3=5, P4=10, P5=120
[backfill] triggered search: series 42 (P1 Live-following)
```

### Log levels

- `INFO` (default) -- all meaningful events: grabs, priority decisions, enforcement actions, sweeps
- `DEBUG` -- adds per-item reconcile details, cache hits, Plex fallback decisions
- `WARNING` -- dependency timeouts, stale heartbeats
- `ERROR` -- API failures, exceptions

Set via `PRIORITARR_LOG_LEVEL` environment variable.

## Configuration

All thresholds are in `prioritarr.yaml` (mounted at `/config/prioritarr.yaml`):

```yaml
priority_thresholds:
  p1_watch_pct_min: 0.90        # minimum % of monitored episodes watched for P1/P2
  p1_days_since_watch_max: 14   # P1: must have watched within this many days
  p1_days_since_release_max: 7  # P1: episode released within this many days
  p1_hiatus_gap_days: 14        # gap between episodes to qualify as hiatus
  p1_hiatus_release_window_days: 28  # P1 window extended to this for post-hiatus
  p2_days_since_watch_max: 60   # P2: watched within 14-60 days
  p3_unwatched_max: 3           # P3: max unwatched episodes
  p3_days_since_watch_max: 60   # P3: must have watched within 60 days
  p4_min_watched: 1             # P4: at least 1 episode watched

intervals:
  reconcile_minutes: 15
  backfill_sweep_hours: 2
  cutoff_sweep_hours: 24
  backfill_max_searches_per_sweep: 10
  backfill_delay_between_searches_seconds: 30
  cutoff_max_searches_per_sweep: 5

cache:
  priority_ttl_minutes: 60      # how long priority decisions are cached

audit:
  retention_days: 90            # audit log retention
  webhook_dedupe_hours: 24      # webhook dedup window
```

## API endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/health` | GET | Liveness probe (Docker healthcheck / autoheal) |
| `/ready` | GET | Readiness check with dependency status |
| `/api/sonarr/on-grab` | POST | Sonarr OnGrab webhook receiver |
| `/api/plex-event` | POST | Tautulli Watched notification receiver |

## Development

```bash
# Install dev dependencies
pip install -r requirements-dev.txt

# Run tests
make test

# Run locally
make run
```

## Architecture

```
Sonarr OnGrab webhook -----> prioritarr <----- Tautulli Watched webhook
                                |
                    +-----------+-----------+
                    |           |           |
                 Sonarr     Tautulli     Plex
                 (series,   (watch      (direct watch
                  episodes,  history)    status fallback)
                  queue)
                    |
          +---------+---------+
          |                   |
      qBittorrent          SABnzbd
      (pause/resume)       (priority levels)
              |
            SQLite
     (state + mapping_cache)
```

## Contract Testing

Prioritarr has a black-box HTTP contract test suite that locks the API surface. All tests run against the single `prioritarr-app` image via the mocks in `contract-tests/mocks/`.

### Running locally against the Kotlin backend

Restart the container with `PRIORITARR_TEST_MODE=true`:

```bash
docker run -d --name prioritarr-app \
  -e PRIORITARR_TEST_MODE=true \
  -e PRIORITARR_DRY_RUN=true \
  -e PRIORITARR_API_KEY=ci-test-key \
  # ... rest of env vars ...
  ghcr.io/cquemin/prioritarr-app:latest
```

Then install and run:

```bash
cd contract-tests
pip install -r requirements.txt
CONTRACT_TEST_BASE_URL=http://localhost:8000 \
CONTRACT_TEST_API_KEY=ci-test-key \
  pytest -v
```

### Running against mocked upstream services

`contract-tests/mocks/` contains WireMock stubs — use them when a live Sonarr/Tautulli/Plex stack isn't available:

```bash
cd contract-tests/mocks
docker compose up -d
# Point prioritarr at http://localhost:9001..9005 and launch as above.
```

### Regenerating `openapi.json`

The OpenAPI spec is committed at the repo root and verified in CI. The Kotlin backend serves the committed file verbatim — there is no runtime generator. Edit `openapi.json` by hand to add/modify endpoints, then let CI diff the committed spec against the live server.

### `PRIORITARR_TEST_MODE` safety

`PRIORITARR_TEST_MODE=true` mounts destructive endpoints at `/api/v1/_testing/*` (reset all state, force stale heartbeat, inject series mappings). **Never enable this in production.** The default is `false`.

### Web UI (Spec D)

`ghcr.io/cquemin/prioritarr-app:latest` bundles a React-based operator console served at `/` on the same port as the API. Point a browser at the container, enter the API key you set via `PRIORITARR_API_KEY`, and you get:

- **Series** — sortable list of every Sonarr series with its current priority, inline Recompute.
- **Downloads** — every tracked managed download across qBit + SAB with pause/resume/boost/demote/untrack buttons.
- **Audit** — filterable chronological log of every priority recompute, webhook, and cache invalidation.
- **Settings** — runtime config (secrets redacted) + mapping refresh + log-out.
- **Live updates** via SSE — priority recomputes, actions, and mapping refreshes show up in the list without a page reload. Small dot on the left rail flips red when the SSE stream drops.

Frontend source + dev instructions in [`prioritarr-app/frontend/README.md`](prioritarr-app/frontend/README.md).

### v2 API surface (Spec C)

The `prioritarr-app` (Kotlin) backend adds a `/api/v2/*` read + control API for the upcoming UI. Every v2 endpoint requires authentication via `X-Api-Key: <key>` (or `Authorization: Bearer <key>`); the key comes from `PRIORITARR_API_KEY`. If unset, auth is disabled and a startup WARN is logged — dev mode only.

Error bodies on `/api/v2/*` follow RFC 7807 Problem Details (`application/problem+json`). Webhooks (`/api/sonarr/on-grab`, `/api/plex-event`) keep the always-200 contract from Spec A and never emit an error body.

Pagination uses arr-style envelope: `?offset=0&limit=50`, response wraps `records` in `{records, totalRecords, offset, limit}`.

Real-time updates are available via Server-Sent Events at `GET /api/v2/events` — event types include `priority-recomputed`, `download-action`, `download-untracked`, `mapping-refreshed`, and a 30s `heartbeat`. Reconnect with `Last-Event-ID: <id>` to replay events you missed within the 1000-event ring buffer.

Running contract tests against a v2-enabled backend requires `CONTRACT_TEST_API_KEY`:

```bash
CONTRACT_TEST_BASE_URL=http://localhost:8001 \
CONTRACT_TEST_API_KEY=your-key \
  pytest contract-tests/
```

### Spec §4 response-example corrections

The original spec (`docs/specs/2026-04-14-prioritarr-api-contract-v1-design.md`) shows a handful of example response bodies that don't match the live backend. The code and the contract tests are the source of truth; a follow-up will correct the spec examples. The actual responses are:

| Endpoint | Case | Wire format |
|----------|------|-------------|
| `POST /api/sonarr/on-grab` | Non-Grab | `{"status":"ignored","eventType":"<type>"}` |
| `POST /api/sonarr/on-grab` | Processed | `{"status":"processed","priority":N,"label":"..."}` |
| `POST /api/sonarr/on-grab` | Duplicate | `{"status":"duplicate","priority":N,"label":"..."}` |
| `POST /api/plex-event` | Unmatched | `{"status":"unmatched","plex_key":"<key>"}` |
| `POST /api/plex-event` | Matched | `{"status":"ok","series_id":N}` |

## License

MIT
