from __future__ import annotations
from prioritarr.schemas.webhooks import OnGrabIgnored, OnGrabProcessed, OnGrabDuplicate, PlexEventUnmatched, PlexEventOk

def test_ongrab_ignored_wire_format():
    model = OnGrabIgnored(eventType="Test")
    assert model.model_dump() == {"status": "ignored", "eventType": "Test"}

def test_ongrab_processed_includes_priority_and_label():
    model = OnGrabProcessed(priority=1, label="P1 Live-following")
    assert model.model_dump() == {"status": "processed", "priority": 1, "label": "P1 Live-following"}

def test_ongrab_duplicate_still_includes_priority_and_label():
    model = OnGrabDuplicate(priority=3, label="P3 A few unwatched")
    assert model.model_dump() == {"status": "duplicate", "priority": 3, "label": "P3 A few unwatched"}

def test_ongrab_priority_rejects_out_of_range():
    import pytest
    from pydantic import ValidationError
    with pytest.raises(ValidationError):
        OnGrabProcessed(priority=0, label="bogus")
    with pytest.raises(ValidationError):
        OnGrabProcessed(priority=6, label="bogus")

def test_plex_event_unmatched_wire_format():
    model = PlexEventUnmatched(plex_key="5000")
    assert model.model_dump() == {"status": "unmatched", "plex_key": "5000"}

def test_plex_event_ok_wire_format():
    model = PlexEventOk(series_id=42)
    assert model.model_dump() == {"status": "ok", "series_id": 42}
