from __future__ import annotations

from datetime import datetime, timezone

from prioritarr.database import Database


def check_liveness(
    db: Database,
    max_heartbeat_age_seconds: int = 300,
) -> dict:
    """Check that the DB is accessible and the heartbeat is fresh.

    Returns ``{"status": "ok"}`` when healthy, or
    ``{"status": "unhealthy", "reason": "..."}`` on failure.
    """
    try:
        ts = db.get_heartbeat()
    except Exception as exc:
        return {"status": "unhealthy", "reason": f"db_error: {exc}"}

    if ts is None:
        return {"status": "unhealthy", "reason": "no_heartbeat"}

    try:
        last = datetime.fromisoformat(ts)
    except ValueError:
        return {"status": "unhealthy", "reason": "invalid_heartbeat_ts"}

    # Ensure tz-aware comparison
    if last.tzinfo is None:
        last = last.replace(tzinfo=timezone.utc)

    now = datetime.now(timezone.utc)
    age_seconds = (now - last).total_seconds()

    if age_seconds > max_heartbeat_age_seconds:
        return {
            "status": "unhealthy",
            "reason": f"heartbeat_stale: {age_seconds:.0f}s > {max_heartbeat_age_seconds}s",
        }

    return {"status": "ok"}


def check_readiness(
    db: Database,
    dependency_status: dict[str, str],
) -> dict:
    """Return a readiness payload including dependency statuses and last heartbeat.

    ``dependency_status`` is a mapping of dependency name → status string,
    e.g. ``{"sonarr": "ok", "qbit": "unreachable"}``.
    """
    last_heartbeat = db.get_heartbeat()

    return {
        "status": "ok" if all(v == "ok" for v in dependency_status.values()) else "degraded",
        "dependencies": dependency_status,
        "last_heartbeat": last_heartbeat,
    }
