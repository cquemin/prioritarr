from __future__ import annotations
from datetime import datetime
from enum import Enum
from typing import Annotated, Literal
from pydantic import BaseModel, Field
from pydantic.functional_serializers import PlainSerializer

# Serialize datetimes as ISO 8601 with explicit +00:00 offset (not the 'Z' shorthand).
_IsoDatetime = Annotated[
    datetime,
    PlainSerializer(lambda dt: dt.isoformat(), return_type=str, when_used="json"),
]

class DependencyStatus(str, Enum):
    ok = "ok"
    unreachable = "unreachable"

class ReadyResponse(BaseModel):
    status: Literal["ok", "degraded"] = Field(description="'ok' iff every dependency is 'ok', else 'degraded'.")
    dependencies: dict[Literal["sonarr", "tautulli", "qbit", "sab"], DependencyStatus] = Field(description="Status of each upstream dependency.")
    last_heartbeat: _IsoDatetime | None = Field(description="Most recent scheduler heartbeat. ISO 8601 with +00:00 offset. Null if never run.")
    model_config = {"json_schema_extra": {"examples": [{"status": "ok", "dependencies": {"sonarr": "ok", "tautulli": "ok", "qbit": "ok", "sab": "ok"}, "last_heartbeat": "2026-04-14T22:30:00.123456+00:00"}]}}
