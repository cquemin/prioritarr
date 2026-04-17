from __future__ import annotations
from datetime import datetime, timezone
from prioritarr.schemas.ready import DependencyStatus, ReadyResponse

def test_ready_ok_all_deps_healthy():
    ts = datetime(2026, 4, 14, 22, 30, 0, 123456, tzinfo=timezone.utc)
    model = ReadyResponse(
        status="ok",
        dependencies={"sonarr": DependencyStatus.ok, "tautulli": DependencyStatus.ok, "qbit": DependencyStatus.ok, "sab": DependencyStatus.ok},
        last_heartbeat=ts,
    )
    dumped = model.model_dump(mode="json")
    assert dumped["status"] == "ok"
    assert dumped["dependencies"] == {"sonarr": "ok", "tautulli": "ok", "qbit": "ok", "sab": "ok"}
    assert dumped["last_heartbeat"] == "2026-04-14T22:30:00.123456+00:00"

def test_ready_degraded_with_null_heartbeat():
    model = ReadyResponse(
        status="degraded",
        dependencies={"sonarr": DependencyStatus.unreachable, "tautulli": DependencyStatus.ok, "qbit": DependencyStatus.ok, "sab": DependencyStatus.ok},
        last_heartbeat=None,
    )
    dumped = model.model_dump(mode="json")
    assert dumped["status"] == "degraded"
    assert dumped["dependencies"]["sonarr"] == "unreachable"
    assert dumped["last_heartbeat"] is None

def test_ready_rejects_unknown_dependency_status():
    import pytest
    from pydantic import ValidationError
    with pytest.raises(ValidationError):
        ReadyResponse(status="ok", dependencies={"sonarr": "weird"}, last_heartbeat=None)
