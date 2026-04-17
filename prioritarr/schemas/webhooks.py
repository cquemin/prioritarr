from __future__ import annotations
from typing import Literal
from pydantic import BaseModel, Field

class OnGrabIgnored(BaseModel):
    status: Literal["ignored"] = Field(default="ignored")
    eventType: str = Field(description="The eventType value that was ignored, echoed verbatim.")

class OnGrabProcessed(BaseModel):
    status: Literal["processed"] = Field(default="processed")
    priority: int = Field(ge=1, le=5, description="Computed priority 1 (highest) through 5 (lowest).")
    label: str = Field(description="Human-readable label like 'P1 Live-following'.")

class OnGrabDuplicate(BaseModel):
    status: Literal["duplicate"] = Field(default="duplicate")
    priority: int = Field(ge=1, le=5)
    label: str

class PlexEventUnmatched(BaseModel):
    status: Literal["unmatched"] = Field(default="unmatched")
    plex_key: str = Field(description="The grandparent_rating_key we couldn't map.")

class PlexEventOk(BaseModel):
    status: Literal["ok"] = Field(default="ok")
    series_id: int = Field(description="The Sonarr seriesId the event was applied to.")
