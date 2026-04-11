from __future__ import annotations

import pytest

from prioritarr.enforcement import compute_sab_priority, compute_qbit_pause_actions, QBitAction


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _dl(hash: str, priority: int, state: str = "downloading", paused_by_us: bool = False) -> dict:
    return {"hash": hash, "priority": priority, "state": state, "paused_by_us": paused_by_us}


def _actions_by_hash(actions: list[QBitAction]) -> dict[str, list[str]]:
    """Group action names by hash for easy lookup."""
    result: dict[str, list[str]] = {}
    for a in actions:
        result.setdefault(a.hash, []).append(a.action)
    return result


# ---------------------------------------------------------------------------
# TestSABPriorityMapping
# ---------------------------------------------------------------------------


class TestSABPriorityMapping:
    def test_p1_maps_to_2(self) -> None:
        assert compute_sab_priority(1) == 2

    def test_p2_maps_to_1(self) -> None:
        assert compute_sab_priority(2) == 1

    def test_p3_maps_to_0(self) -> None:
        assert compute_sab_priority(3) == 0

    def test_p4_maps_to_minus1(self) -> None:
        assert compute_sab_priority(4) == -1

    def test_p5_maps_to_minus1(self) -> None:
        assert compute_sab_priority(5) == -1


# ---------------------------------------------------------------------------
# TestQBitPauseBand
# ---------------------------------------------------------------------------


class TestQBitPauseBand:
    def test_p1_active_pauses_p4_and_p5(self) -> None:
        downloads = {
            "h1": _dl("h1", 1),
            "h4": _dl("h4", 4),
            "h5": _dl("h5", 5),
        }
        actions = compute_qbit_pause_actions(downloads)
        by_hash = _actions_by_hash(actions)

        # P4 and P5 should be paused
        assert "pause" in by_hash.get("h4", [])
        assert "pause" in by_hash.get("h5", [])
        # P1 should get top_priority (not paused)
        assert "top_priority" in by_hash.get("h1", [])

    def test_p2_active_pauses_p5_only(self) -> None:
        downloads = {
            "h2": _dl("h2", 2),
            "h4": _dl("h4", 4),
            "h5": _dl("h5", 5),
        }
        actions = compute_qbit_pause_actions(downloads)
        by_hash = _actions_by_hash(actions)

        # Only P5 should be paused; P4 should not
        assert "pause" in by_hash.get("h5", [])
        assert "pause" not in by_hash.get("h4", [])
        # P2 should not be paused or get top_priority
        assert "pause" not in by_hash.get("h2", [])
        assert "top_priority" not in by_hash.get("h2", [])

    def test_only_p3_p4_p5_nothing_paused(self) -> None:
        downloads = {
            "h3": _dl("h3", 3),
            "h4": _dl("h4", 4),
            "h5": _dl("h5", 5),
        }
        actions = compute_qbit_pause_actions(downloads)
        pause_actions = [a for a in actions if a.action == "pause"]
        assert pause_actions == []

    def test_already_paused_by_us_resumes_when_allowed(self) -> None:
        """A download paused by us that is no longer in a pause band should be resumed."""
        downloads = {
            # Only P3 active, so pause_levels is empty
            "h3": _dl("h3", 3),
            # P5 was paused by us, but no P1/P2 active → should resume
            "h5": _dl("h5", 5, state="pausedDL", paused_by_us=True),
        }
        actions = compute_qbit_pause_actions(downloads)
        by_hash = _actions_by_hash(actions)
        assert "resume" in by_hash.get("h5", [])

    def test_manually_paused_not_resumed(self) -> None:
        """A download paused manually (paused_by_us=False) should not be auto-resumed."""
        downloads = {
            "h3": _dl("h3", 3),
            "h5": _dl("h5", 5, state="pausedDL", paused_by_us=False),
        }
        actions = compute_qbit_pause_actions(downloads)
        by_hash = _actions_by_hash(actions)
        assert "resume" not in by_hash.get("h5", [])

    def test_p1_gets_top_priority_action(self) -> None:
        downloads = {
            "h1": _dl("h1", 1),
        }
        actions = compute_qbit_pause_actions(downloads)
        by_hash = _actions_by_hash(actions)
        assert "top_priority" in by_hash.get("h1", [])

    def test_empty_downloads_no_actions(self) -> None:
        actions = compute_qbit_pause_actions({})
        assert actions == []
