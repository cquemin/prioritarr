from __future__ import annotations

from dataclasses import dataclass

from prioritarr.clients.sabnzbd import PRIORITY_MAP


def compute_sab_priority(prioritarr_level: int) -> int:
    """Map P1-P5 to SAB priority values."""
    return PRIORITY_MAP.get(prioritarr_level, 0)


@dataclass
class QBitAction:
    hash: str
    action: str  # 'pause' | 'resume' | 'top_priority' | 'bottom_priority'


def compute_qbit_pause_actions(downloads: dict[str, dict]) -> list[QBitAction]:
    """Given {hash: {hash, priority, state, paused_by_us}}, compute pause/resume/reorder actions.

    Pause-band rules:
    - P1 active -> pause P4+P5
    - P2 active (no P1) -> pause P5
    - Only P3/P4/P5 -> nothing paused
    """
    actions = []

    # Determine highest active priority (not paused, not errored)
    active_priorities = set()
    for d in downloads.values():
        if d["state"] not in ("pausedDL", "pausedUP", "error", "missingFiles"):
            active_priorities.add(d["priority"])

    has_p1 = 1 in active_priorities
    has_p2 = 2 in active_priorities

    pause_levels: set[int] = set()
    if has_p1:
        pause_levels = {4, 5}
    elif has_p2:
        pause_levels = {5}

    for d in downloads.values():
        h, p, state, pbu = d["hash"], d["priority"], d["state"], d["paused_by_us"]
        is_paused = state in ("pausedDL", "pausedUP")

        if p in pause_levels and not is_paused:
            actions.append(QBitAction(hash=h, action="pause"))
        elif p not in pause_levels and is_paused and pbu:
            actions.append(QBitAction(hash=h, action="resume"))

        if p == 1 and not is_paused:
            actions.append(QBitAction(hash=h, action="top_priority"))

    return actions
