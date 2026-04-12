from __future__ import annotations

import logging
import os
import re
import sys
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from typing import Any

import redis
from apscheduler.schedulers.background import BackgroundScheduler
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse

from prioritarr.clients.qbittorrent import QBitClient
from prioritarr.clients.sabnzbd import SABClient, PRIORITY_MAP
from prioritarr.clients.sonarr import SonarrClient
from prioritarr.clients.tautulli import TautulliClient
from prioritarr.config import Settings, load_settings_from_env
from prioritarr.database import Database
from prioritarr.health import check_liveness, check_readiness
from prioritarr.models import PriorityResult, SeriesSnapshot
from prioritarr.priority import compute_priority
from prioritarr.reconcile import ReconcileContext, reconcile_client
from prioritarr.sweep import SweepContext, run_backfill_sweep, run_cutoff_sweep
from prioritarr.webhooks.plex_event import parse_tautulli_watched, handle_watched
from prioritarr.webhooks.sonarr_grab import parse_ongrab_payload, handle_ongrab

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Module-level state (initialised in lifespan)
# ---------------------------------------------------------------------------

settings: Settings
db: Database
sonarr: SonarrClient
tautulli: TautulliClient
qbit: QBitClient
sab: SABClient
scheduler: BackgroundScheduler
sweep_ctx: SweepContext | None = None
redis_client: redis.Redis | None = None

REDIS_MAPPING_KEY = "prioritarr:plex_to_series"   # hash: plex_rating_key -> series_id
REDIS_MAPPING_TTL = 7 * 24 * 3600                 # 7 days

_tvdb_to_series: dict[int, int] = {}         # tvdb_id -> sonarr series_id
_title_to_plex_key: dict[str, str] = {}      # normalized_title -> plex_rating_key
_plex_key_to_series_id: dict[str, int] = {}  # plex_rating_key -> sonarr series_id
_TAUTULLI_AVAILABLE: bool = True


# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------


def _setup_logging(level: str) -> None:
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format='{"ts":"%(asctime)s","level":"%(levelname)s","logger":"%(name)s","msg":"%(message)s"}',
        stream=sys.stdout,
    )
    # Silence noisy third-party loggers even in DEBUG mode
    logging.getLogger("httpcore").setLevel(logging.WARNING)
    logging.getLogger("httpx").setLevel(logging.INFO)
    logging.getLogger("apscheduler.scheduler").setLevel(logging.INFO)
    logging.getLogger("apscheduler.executors").setLevel(logging.INFO)


# ---------------------------------------------------------------------------
# Helper: normalise a title for matching
# ---------------------------------------------------------------------------


def _normalise_title(title: str) -> str:
    return title.strip().lower()


# ---------------------------------------------------------------------------
# Mapping helpers
# ---------------------------------------------------------------------------


def _find_plex_key_for_series(title: str) -> str | None:
    """Return the Plex rating key for *title*, or None if not mapped."""
    return _title_to_plex_key.get(_normalise_title(title))


def _extract_folder_name(path: str) -> str:
    """Extract the last meaningful folder component from a path."""
    # /storage/media/video/anime/Attack on Titan -> attack on titan
    return os.path.basename(path.rstrip("/")).strip().lower()


def _extract_tvdb_from_guids(guids: list) -> int | None:
    """Extract TVDB ID from Plex/Tautulli GUIDs list."""
    for guid in guids:
        g = str(guid)
        # Plex new agent: "tvdb://267440"
        if "tvdb://" in g:
            match = re.search(r"tvdb://(\d+)", g)
            if match:
                return int(match.group(1))
        # Plex old agent: "com.plexapp.agents.thetvdb://267440"
        if "thetvdb://" in g:
            match = re.search(r"thetvdb://(\d+)", g)
            if match:
                return int(match.group(1))
    return None


def _load_cached_mappings() -> dict[str, int]:
    """Load plex_key -> series_id mapping from Redis. Returns empty dict on failure."""
    if redis_client is None:
        return {}
    try:
        cached = redis_client.hgetall(REDIS_MAPPING_KEY)
        return {k.decode(): int(v) for k, v in cached.items()} if cached else {}
    except Exception:
        logger.warning("Redis: failed to load cached mappings")
        return {}


def _save_cached_mappings(mapping: dict[str, int]) -> None:
    """Save plex_key -> series_id mapping to Redis."""
    if redis_client is None or not mapping:
        return
    try:
        pipe = redis_client.pipeline()
        pipe.delete(REDIS_MAPPING_KEY)
        pipe.hset(REDIS_MAPPING_KEY, mapping={k: str(v) for k, v in mapping.items()})
        pipe.expire(REDIS_MAPPING_KEY, REDIS_MAPPING_TTL)
        pipe.execute()
        logger.debug("Redis: saved %d mappings", len(mapping))
    except Exception:
        logger.warning("Redis: failed to save cached mappings")


def _refresh_mappings() -> None:
    """Rebuild Plex↔Sonarr mappings using 3-step matching + Redis cache.

    Steps per Plex show (in order, first match wins):
    1. TVDB ID — via Tautulli get_metadata GUIDs → Sonarr tvdbId (unambiguous)
    2. Path    — Sonarr series folder name → Plex file location folder name
    3. Title   — normalised title comparison (current fallback)

    Redis caches the final plex_key→series_id map so only NEW shows need
    the expensive get_metadata calls on subsequent refreshes.
    """
    global _tvdb_to_series, _title_to_plex_key, _plex_key_to_series_id, _TAUTULLI_AVAILABLE

    # --- Sonarr side ---
    try:
        all_series = sonarr.get_all_series()
    except Exception:
        logger.exception("_refresh_mappings: failed to fetch Sonarr series")
        return

    new_tvdb: dict[int, int] = {}
    sonarr_titles: dict[str, int] = {}
    sonarr_folders: dict[str, int] = {}  # normalized folder name -> series_id

    for s in all_series:
        sid = s.get("id")
        tvdb_id = s.get("tvdbId")
        title = s.get("title", "")
        path = s.get("path", "")
        if sid is None:
            continue
        if tvdb_id:
            new_tvdb[tvdb_id] = sid
        sonarr_titles[_normalise_title(title)] = sid
        if path:
            sonarr_folders[_extract_folder_name(path)] = sid

    _tvdb_to_series = new_tvdb

    # --- Tautulli side ---
    try:
        libraries = tautulli.get_show_libraries()
    except Exception:
        logger.exception("_refresh_mappings: failed to fetch Tautulli libraries")
        _TAUTULLI_AVAILABLE = False
        return

    # Load existing Redis cache — shows already matched skip the 3-step process
    cached = _load_cached_mappings()

    new_title_to_key: dict[str, str] = {}
    new_key_to_sid: dict[str, int] = {}
    stats = {"cached": 0, "tvdb": 0, "path": 0, "title": 0, "unmatched": 0}

    for lib in libraries:
        section_id = lib.get("section_id")
        if section_id is None:
            continue
        try:
            media_items = tautulli.get_library_media_info(int(section_id))
        except Exception:
            logger.exception("_refresh_mappings: failed to get media for section %s", section_id)
            continue

        for item in media_items:
            plex_key = str(item.get("rating_key", ""))
            plex_title = item.get("title", "")
            if not plex_key:
                continue

            norm = _normalise_title(plex_title)
            new_title_to_key[norm] = plex_key

            # Check Redis cache first
            if plex_key in cached:
                sid = cached[plex_key]
                # Verify the series still exists in Sonarr
                if sid in new_tvdb.values() or sid in sonarr_titles.values():
                    new_key_to_sid[plex_key] = sid
                    stats["cached"] += 1
                    continue

            # Step 1: TVDB ID match via get_metadata
            matched = False
            try:
                metadata = tautulli.get_metadata(plex_key)
                guids = metadata.get("guids", []) if isinstance(metadata, dict) else []
                tvdb_id = _extract_tvdb_from_guids(guids)
                if tvdb_id and tvdb_id in new_tvdb:
                    new_key_to_sid[plex_key] = new_tvdb[tvdb_id]
                    stats["tvdb"] += 1
                    matched = True

                # Step 2: Path match (from metadata file info)
                if not matched and isinstance(metadata, dict):
                    media_info = metadata.get("media_info", [])
                    for mi in media_info if isinstance(media_info, list) else []:
                        parts = mi.get("parts", [])
                        for part in parts if isinstance(parts, list) else []:
                            file_path = part.get("file", "")
                            if file_path:
                                # Extract series folder: /anime/Attack on Titan/Season 01/ep.mkv
                                # Split path, find component before "Season XX"
                                parts_list = file_path.replace("\\", "/").split("/")
                                for i, p in enumerate(parts_list):
                                    if p.lower().startswith("season") and i > 0:
                                        folder = parts_list[i - 1].strip().lower()
                                        sid = sonarr_folders.get(folder)
                                        if sid is not None:
                                            new_key_to_sid[plex_key] = sid
                                            stats["path"] += 1
                                            matched = True
                                        break
                            if matched:
                                break
                        if matched:
                            break
            except Exception:
                logger.debug("_refresh_mappings: get_metadata failed for %s (%s)", plex_key, plex_title)

            # Step 3: Title match (fallback)
            if not matched:
                sid = sonarr_titles.get(norm)
                if sid is not None:
                    new_key_to_sid[plex_key] = sid
                    stats["title"] += 1
                else:
                    stats["unmatched"] += 1

    _title_to_plex_key = new_title_to_key
    _plex_key_to_series_id = new_key_to_sid
    _TAUTULLI_AVAILABLE = True

    # Save to Redis
    _save_cached_mappings(new_key_to_sid)

    logger.info(
        "_refresh_mappings: %d sonarr series, %d plex shows, %d matched "
        "(cached=%d, tvdb=%d, path=%d, title=%d, unmatched=%d)",
        len(all_series), len(new_title_to_key), len(new_key_to_sid),
        stats["cached"], stats["tvdb"], stats["path"], stats["title"], stats["unmatched"],
    )


# ---------------------------------------------------------------------------
# Snapshot + priority computation
# ---------------------------------------------------------------------------


def _build_series_snapshot(series_id: int) -> SeriesSnapshot | None:
    """Build a SeriesSnapshot for *series_id*, or None if Tautulli is unavailable."""
    try:
        series_data = sonarr.get_series(series_id)
    except Exception:
        logger.exception("_build_series_snapshot: failed to fetch series %d", series_id)
        return None

    try:
        episodes = sonarr.get_episodes(series_id)
    except Exception:
        logger.exception(
            "_build_series_snapshot: failed to fetch episodes for series %d", series_id
        )
        return None

    title: str = series_data.get("title", "")
    tvdb_id: int = series_data.get("tvdbId", 0)

    now = datetime.now(timezone.utc)

    # Count monitored aired episodes
    monitored_aired = 0
    missing_episodes: list[datetime] = []

    for ep in episodes:
        if not ep.get("monitored"):
            continue
        air_str = ep.get("airDateUtc")
        if not air_str:
            continue
        try:
            air_dt = datetime.fromisoformat(air_str.replace("Z", "+00:00"))
        except ValueError:
            continue
        if air_dt > now:
            continue  # Not yet aired
        monitored_aired += 1
        if not ep.get("hasFile"):
            missing_episodes.append(air_dt)

    # Find the most recent missing episode (for release_date / hiatus detection)
    episode_release_date: datetime | None = None
    previous_episode_release_date: datetime | None = None

    if missing_episodes:
        missing_episodes.sort()
        episode_release_date = missing_episodes[-1]
        if len(missing_episodes) >= 2:
            previous_episode_release_date = missing_episodes[-2]

    # Plex / Tautulli side
    plex_key = _find_plex_key_for_series(title)
    watched_count = 0
    last_watched_at: datetime | None = None

    if plex_key:
        try:
            history = tautulli.get_history(grandparent_rating_key=plex_key, media_type="episode")
        except Exception:
            logger.exception(
                "_build_series_snapshot: Tautulli history failed for series %d", series_id
            )
            return None  # Tautulli unreachable → caller handles P3 fallback

        watched_keys: set[str] = set()
        latest_ts: datetime | None = None

        for entry in history:
            rk = str(entry.get("rating_key", ""))
            if rk:
                watched_keys.add(rk)
            ts_raw = entry.get("date")
            if ts_raw:
                try:
                    ts_val = datetime.fromtimestamp(int(ts_raw), tz=timezone.utc)
                    if latest_ts is None or ts_val > latest_ts:
                        latest_ts = ts_val
                except (ValueError, TypeError):
                    pass

        watched_count = len(watched_keys)
        last_watched_at = latest_ts

    return SeriesSnapshot(
        series_id=series_id,
        title=title,
        tvdb_id=tvdb_id,
        monitored_episodes_aired=monitored_aired,
        monitored_episodes_watched=watched_count,
        last_watched_at=last_watched_at,
        episode_release_date=episode_release_date,
        previous_episode_release_date=previous_episode_release_date,
    )


def _compute_priority_for_series(series_id: int) -> PriorityResult:
    """Return a PriorityResult for *series_id*, using the cache when valid."""
    # 1. Check cache
    row = db.get_priority_cache(series_id)
    if row is not None:
        try:
            expires_at = datetime.fromisoformat(row["expires_at"])
            if expires_at.tzinfo is None:
                expires_at = expires_at.replace(tzinfo=timezone.utc)
            if datetime.now(timezone.utc) < expires_at:
                return PriorityResult(
                    priority=row["priority"],
                    label=f"P{row['priority']} (cached)",
                    reason=row["reason"] or "",
                )
        except (ValueError, TypeError):
            pass  # Fall through to recompute

    # 2. Build snapshot
    snap = _build_series_snapshot(series_id)

    # 3. Snapshot None → P3 fallback (dependency unreachable)
    if snap is None:
        return PriorityResult(
            priority=3,
            label="P3 A few unwatched",
            reason="dependency_unreachable",
        )

    # 4. Compute priority
    result = compute_priority(snap, settings.priority_thresholds)

    # 5. Cache with TTL
    now = datetime.now(timezone.utc)
    ttl_minutes = settings.cache.priority_ttl_minutes
    expires_at = now + timedelta(minutes=ttl_minutes)

    aired = snap.monitored_episodes_aired
    watched = snap.monitored_episodes_watched
    watch_pct = watched / aired if aired > 0 else 0.0
    days_since_watch: int | None = None
    if snap.last_watched_at is not None:
        days_since_watch = (now - snap.last_watched_at).days
    unwatched = aired - watched

    db.upsert_priority_cache(
        series_id=series_id,
        priority=result.priority,
        watch_pct=watch_pct,
        days_since_watch=days_since_watch,
        unwatched_pending=unwatched,
        computed_at=now.isoformat(),
        expires_at=expires_at.isoformat(),
        reason=result.reason,
    )

    return result


# ---------------------------------------------------------------------------
# Scheduled jobs
# ---------------------------------------------------------------------------


def _job_heartbeat() -> None:
    try:
        db.update_heartbeat()
    except Exception:
        logger.exception("_job_heartbeat: failed")


def _job_reconcile() -> None:
    try:
        queue_items = sonarr.get_queue()
    except Exception:
        logger.exception("_job_reconcile: failed to fetch Sonarr queue")
        return

    # Build download_id → {seriesId, episodeId} lookup
    sonarr_queue_lookup: dict[str, dict] = {}
    for item in queue_items:
        dl_id = item.get("downloadId", "")
        if dl_id:
            sonarr_queue_lookup[dl_id] = {
                "seriesId": item.get("seriesId", 0),
                "episodeId": item.get("episodeId", 0),
            }

    for client_name, client in (("qbit", qbit), ("sab", sab)):
        try:
            ctx = ReconcileContext(
                client_name=client_name,
                client=client,
                db=db,
                sonarr_queue_lookup=sonarr_queue_lookup,
                priority_fn=_compute_priority_for_series,
                dry_run=settings.dry_run,
            )
            reconcile_client(ctx)
        except Exception:
            logger.exception("_job_reconcile: failed for client %s", client_name)

    try:
        db.prune(
            dedupe_hours=settings.audit.webhook_dedupe_hours,
            retention_days=settings.audit.retention_days,
        )
    except Exception:
        logger.exception("_job_reconcile: prune failed")


def _job_backfill_sweep() -> None:
    global sweep_ctx
    ctx = SweepContext(
        sonarr=sonarr,
        priority_fn=_compute_priority_for_series,
        max_searches=settings.intervals.backfill_max_searches_per_sweep,
        delay_seconds=settings.intervals.backfill_delay_between_searches_seconds,
        dry_run=settings.dry_run,
    )
    sweep_ctx = ctx
    try:
        count = run_backfill_sweep(ctx)
        logger.info("_job_backfill_sweep: searched %d series", count)
    except Exception:
        logger.exception("_job_backfill_sweep: failed")
    finally:
        sweep_ctx = None


def _job_cutoff_sweep() -> None:
    global sweep_ctx
    ctx = SweepContext(
        sonarr=sonarr,
        priority_fn=_compute_priority_for_series,
        max_searches=settings.intervals.cutoff_max_searches_per_sweep,
        delay_seconds=settings.intervals.backfill_delay_between_searches_seconds,
        dry_run=settings.dry_run,
    )
    sweep_ctx = ctx
    try:
        count = run_cutoff_sweep(ctx)
        logger.info("_job_cutoff_sweep: searched %d series", count)
    except Exception:
        logger.exception("_job_cutoff_sweep: failed")
    finally:
        sweep_ctx = None


def _job_refresh_mappings() -> None:
    try:
        _refresh_mappings()
    except Exception:
        logger.exception("_job_refresh_mappings: failed")


# ---------------------------------------------------------------------------
# Lifespan
# ---------------------------------------------------------------------------


@asynccontextmanager
async def lifespan(application: FastAPI):  # type: ignore[type-arg]
    global settings, db, sonarr, tautulli, qbit, sab, scheduler, redis_client

    # 1. Load settings
    settings = load_settings_from_env()
    _setup_logging(settings.log_level)
    logger.info("prioritarr starting (dry_run=%s)", settings.dry_run)

    # 2. Initialise DB
    db = Database("/config/state.db")

    # 3. Create API clients
    sonarr = SonarrClient(settings.sonarr_url, settings.sonarr_api_key)
    tautulli = TautulliClient(settings.tautulli_url, settings.tautulli_api_key)
    qbit = QBitClient(
        settings.qbit_url,
        username=settings.qbit_username or "",
        password=settings.qbit_password or "",
    )
    sab = SABClient(settings.sab_url, settings.sab_api_key)

    # 3b. Redis (optional — graceful if unavailable)
    if settings.redis_url:
        try:
            redis_client = redis.from_url(settings.redis_url, decode_responses=False)
            redis_client.ping()
            logger.info("Redis connected at %s", settings.redis_url.split("@")[-1])
        except Exception:
            logger.warning("Redis unavailable — mapping cache disabled")
            redis_client = None
    else:
        redis_client = None
        logger.info("No REDIS_URL configured — mapping cache disabled")

    # 4. Skip blocking mapping refresh at startup — let the scheduler handle it
    # immediately after start. This avoids a 60s timeout blocking the lifespan.
    logger.info("Deferring initial mapping refresh to scheduler (runs within 30s)")

    # 5. Start scheduler
    scheduler = BackgroundScheduler()

    ivl = settings.intervals
    scheduler.add_job(_job_heartbeat, "interval", seconds=60, id="heartbeat")
    scheduler.add_job(
        _job_reconcile,
        "interval",
        minutes=ivl.reconcile_minutes,
        id="reconcile",
    )
    scheduler.add_job(
        _job_backfill_sweep,
        "interval",
        hours=ivl.backfill_sweep_hours,
        id="backfill_sweep",
    )
    scheduler.add_job(
        _job_cutoff_sweep,
        "interval",
        hours=ivl.cutoff_sweep_hours,
        id="cutoff_sweep",
    )
    scheduler.add_job(
        _job_refresh_mappings,
        "interval",
        hours=1,
        id="refresh_mappings",
    )
    # Run initial mapping refresh 10s after start (non-blocking)
    scheduler.add_job(
        _job_refresh_mappings,
        "date",
        run_date=datetime.now(timezone.utc) + timedelta(seconds=10),
        id="initial_refresh",
        misfire_grace_time=120,  # still run even if "missed" by up to 2 min
    )

    scheduler.start()
    logger.info("prioritarr scheduler started")

    yield

    scheduler.shutdown(wait=False)
    logger.info("prioritarr shutdown complete")


# ---------------------------------------------------------------------------
# Application
# ---------------------------------------------------------------------------

app = FastAPI(title="prioritarr", lifespan=lifespan)


# ---------------------------------------------------------------------------
# Health endpoints
# ---------------------------------------------------------------------------


@app.get("/health")
async def health() -> JSONResponse:
    """Liveness check — returns 200 OK or 503 Unhealthy."""
    ivl = settings.intervals
    max_age = 2 * max(ivl.reconcile_minutes * 60, ivl.backfill_sweep_hours * 3600)
    result = check_liveness(db, max_heartbeat_age_seconds=max_age)
    status_code = 200 if result["status"] == "ok" else 503
    return JSONResponse(content=result, status_code=status_code)


@app.get("/ready")
async def ready() -> JSONResponse:
    """Readiness check — probes all dependencies."""
    dep_status: dict[str, str] = {}

    for name, client, method in (
        ("sonarr", sonarr, lambda: sonarr.get_all_series()),
        ("tautulli", tautulli, lambda: tautulli.get_show_libraries()),
        ("qbit", qbit, lambda: qbit.get_torrents()),
        ("sab", sab, lambda: sab.get_queue()),
    ):
        try:
            method()
            dep_status[name] = "ok"
        except Exception as exc:
            dep_status[name] = f"unreachable: {exc}"

    result = check_readiness(db, dep_status)
    status_code = 200 if result["status"] == "ok" else 503
    return JSONResponse(content=result, status_code=status_code)


# ---------------------------------------------------------------------------
# Webhook endpoints
# ---------------------------------------------------------------------------


@app.post("/api/sonarr/on-grab")
async def sonarr_on_grab(request: Request) -> JSONResponse:
    """Handle Sonarr OnGrab webhook."""
    payload: dict[str, Any] = await request.json()

    # Only process Grab events
    event_type = payload.get("eventType", "")
    if event_type != "Grab":
        return JSONResponse(content={"status": "ignored", "eventType": event_type})

    event = parse_ongrab_payload(payload)
    result = _compute_priority_for_series(event.series_id)

    processed = handle_ongrab(event, db, priority=result.priority, dry_run=settings.dry_run)

    if processed and not settings.dry_run:
        # Interrupt any running sweep
        if sweep_ctx is not None:
            sweep_ctx.interrupted = True

        # Trigger a reconcile pass for the relevant client
        if event.download_client == "qbit":
            try:
                _trigger_qbit_reconcile(event.download_id)
            except Exception:
                logger.exception("sonarr_on_grab: qbit reconcile failed")
        elif event.download_client in ("sab", "nzbget"):
            try:
                sab_priority = PRIORITY_MAP.get(result.priority, 0)
                sab.set_priority(event.download_id, sab_priority)
            except Exception:
                logger.exception("sonarr_on_grab: SAB set_priority failed")

    return JSONResponse(
        content={
            "status": "processed" if processed else "duplicate",
            "priority": result.priority,
            "label": result.label,
        }
    )


def _trigger_qbit_reconcile(download_id: str) -> None:
    """Run a single reconcile pass for qBittorrent."""
    try:
        queue_items = sonarr.get_queue()
    except Exception:
        logger.exception("_trigger_qbit_reconcile: failed to fetch Sonarr queue")
        return

    sonarr_queue_lookup: dict[str, dict] = {}
    for item in queue_items:
        dl_id = item.get("downloadId", "")
        if dl_id:
            sonarr_queue_lookup[dl_id] = {
                "seriesId": item.get("seriesId", 0),
                "episodeId": item.get("episodeId", 0),
            }

    ctx = ReconcileContext(
        client_name="qbit",
        client=qbit,
        db=db,
        sonarr_queue_lookup=sonarr_queue_lookup,
        priority_fn=_compute_priority_for_series,
        dry_run=settings.dry_run,
    )
    reconcile_client(ctx)


@app.post("/api/plex-event")
async def plex_event(request: Request) -> JSONResponse:
    """Handle Tautulli watched webhook."""
    payload: dict[str, Any] = await request.json()

    event = parse_tautulli_watched(payload)
    plex_key = event.plex_show_key

    series_id = _plex_key_to_series_id.get(plex_key)

    # Refresh on miss
    if series_id is None and plex_key:
        try:
            _refresh_mappings()
        except Exception:
            logger.exception("plex_event: mapping refresh failed")
        series_id = _plex_key_to_series_id.get(plex_key)

    if series_id is None:
        return JSONResponse(
            content={"status": "unmatched", "plex_key": plex_key},
            status_code=200,
        )

    handle_watched(series_id, db)

    # Check if there are affected downloads and trigger reconcile
    if not settings.dry_run:
        affected = db.list_managed_downloads()
        if any(row["series_id"] == series_id for row in affected):
            try:
                _job_reconcile()
            except Exception:
                logger.exception("plex_event: reconcile failed")

    return JSONResponse(content={"status": "ok", "series_id": series_id})
