from __future__ import annotations
from typing import Literal
from pydantic import BaseModel, Field

class HealthOk(BaseModel):
    status: Literal["ok"] = Field(default="ok", description="Always 'ok' when healthy.")
    model_config = {"json_schema_extra": {"examples": [{"status": "ok"}]}}

class HealthUnhealthy(BaseModel):
    status: Literal["unhealthy"] = Field(default="unhealthy", description="Always 'unhealthy'.")
    reason: str = Field(description="Human-readable diagnostic. Free-form text.")
    model_config = {"json_schema_extra": {"examples": [{"status": "unhealthy", "reason": "heartbeat_stale: 342s > 300s"}]}}
