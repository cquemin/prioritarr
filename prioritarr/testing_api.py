"""Test-mode-only endpoints for state control during contract tests.

These routes are mounted ONLY when PRIORITARR_TEST_MODE=true. They clear
state, backdate heartbeats and inject mappings — dangerous in production,
so gating is a hard requirement.
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

from fastapi import APIRouter
from pydantic import BaseModel, Field


router = APIRouter(prefix="/api/v1/_testing", tags=["testing"])


class OkResponse(BaseModel):
    status: str = Field(default="ok")


class InjectSeriesMappingRequest(BaseModel):
    plex_key: str
    series_id: int


@router.post(
    "/reset",
    summary="Clear all mutable state",
    response_model=OkResponse,
    description=(
        "Clears managed_downloads, webhook_dedupe, audit_log, "
        "series_priority_cache and heartbeat rows in state.db AND clears "
        "the in-memory Plex→Sonarr mapping. Used by the contract test "
        "suite between tests."
    ),
)
async def reset() -> OkResponse:
    from prioritarr import main as m
    m.db.execute("DELETE FROM managed_downloads")
    m.db.execute("DELETE FROM webhook_dedupe")
    m.db.execute("DELETE FROM audit_log")
    m.db.execute("DELETE FROM series_priority_cache")
    m.db.execute("DELETE FROM heartbeat")
    m._plex_key_to_series_id.clear()
    m._title_to_plex_key.clear()
    m._tvdb_to_series.clear()
    return OkResponse()


@router.post(
    "/stale-heartbeat",
    summary="Force heartbeat to be stale (make /health return 503)",
    response_model=OkResponse,
)
async def stale_heartbeat() -> OkResponse:
    from prioritarr import main as m
    stale_ts = (datetime.now(timezone.utc) - timedelta(days=365)).isoformat()
    m.db.execute(
        "INSERT INTO heartbeat (id, ts) VALUES (1, ?) "
        "ON CONFLICT(id) DO UPDATE SET ts=excluded.ts",
        (stale_ts,),
    )
    return OkResponse()


@router.post(
    "/inject-series-mapping",
    summary="Inject a plex_key → series_id entry into the in-memory mapping",
    response_model=OkResponse,
)
async def inject_series_mapping(payload: InjectSeriesMappingRequest) -> OkResponse:
    from prioritarr import main as m
    m._plex_key_to_series_id[payload.plex_key] = payload.series_id
    return OkResponse()
