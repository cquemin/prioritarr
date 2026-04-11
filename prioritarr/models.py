from __future__ import annotations

import dataclasses
from datetime import datetime


@dataclasses.dataclass
class SeriesSnapshot:
    """Snapshot of a Sonarr series combined with Tautulli watch history."""

    series_id: int
    title: str
    tvdb_id: int
    monitored_episodes_aired: int
    monitored_episodes_watched: int
    last_watched_at: datetime | None
    episode_release_date: datetime | None
    previous_episode_release_date: datetime | None


@dataclasses.dataclass
class PriorityResult:
    """Computed priority for a single series."""

    priority: int  # 1..5
    label: str
    reason: str


@dataclasses.dataclass
class ManagedDownload:
    """A download tracked by the orchestrator across reconciliation cycles."""

    client: str
    client_id: str
    series_id: int
    episode_ids: list[int]
    initial_priority: int
    current_priority: int
    paused_by_us: bool
    first_seen_at: datetime
    last_reconciled_at: datetime
