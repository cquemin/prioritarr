from __future__ import annotations
from prioritarr.schemas.health import HealthOk, HealthUnhealthy

def test_health_ok_serializes_correctly():
    model = HealthOk()
    assert model.model_dump() == {"status": "ok"}

def test_health_unhealthy_serializes_correctly():
    model = HealthUnhealthy(reason="heartbeat_stale: 342s > 300s")
    assert model.model_dump() == {"status": "unhealthy", "reason": "heartbeat_stale: 342s > 300s"}

def test_health_unhealthy_rejects_wrong_status():
    import pytest
    from pydantic import ValidationError
    with pytest.raises(ValidationError):
        HealthUnhealthy(status="ok", reason="x")
