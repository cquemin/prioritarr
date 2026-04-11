from __future__ import annotations

import json
from pathlib import Path

import httpx
import pytest
import respx

from prioritarr.clients.sabnzbd import PRIORITY_MAP, SABClient

FIXTURES = Path(__file__).parent / "fixtures"
BASE_URL = "http://sab:8080"
NZO_ID = "SABnzbd_nzo_abc123"


@pytest.fixture
def client() -> SABClient:
    return SABClient(BASE_URL, api_key="testkey")


def _load(name: str) -> object:
    return json.loads((FIXTURES / name).read_text())


@respx.mock
def test_get_queue(client: SABClient) -> None:
    data = _load("sab_queue.json")
    respx.get(f"{BASE_URL}/sabnzbd/api").mock(
        return_value=httpx.Response(200, json=data)
    )

    slots = client.get_queue()

    assert len(slots) == 2
    assert slots[0]["nzo_id"] == NZO_ID
    assert slots[0]["priority"] == "Force"
    assert slots[1]["filename"] == "Demon.Slayer.S02E05.1080p.WEB-DL"


@respx.mock
def test_set_priority(client: SABClient) -> None:
    respx.get(f"{BASE_URL}/sabnzbd/api").mock(
        return_value=httpx.Response(200, json={"status": True})
    )

    result = client.set_priority(NZO_ID, priority=2)

    assert result["status"] is True
    sent = respx.calls.last.request
    assert b"name=priority" in sent.url.query or "name=priority" in str(sent.url)
    assert NZO_ID in str(sent.url)


@respx.mock
def test_move_to_position(client: SABClient) -> None:
    respx.get(f"{BASE_URL}/sabnzbd/api").mock(
        return_value=httpx.Response(200, json={"result": "ok"})
    )

    result = client.move_to_position(NZO_ID, position=0)

    assert result["result"] == "ok"
    sent = respx.calls.last.request
    assert "switch" in str(sent.url)
    assert NZO_ID in str(sent.url)


def test_priority_mapping() -> None:
    # P1 → Force (highest in SABnzbd)
    assert PRIORITY_MAP[1] == 2
    # P2 → High
    assert PRIORITY_MAP[2] == 1
    # P3 → Normal
    assert PRIORITY_MAP[3] == 0
    # P4 → Low
    assert PRIORITY_MAP[4] == -1
    # P5 → Low
    assert PRIORITY_MAP[5] == -1
    # All 5 levels are covered
    assert set(PRIORITY_MAP.keys()) == {1, 2, 3, 4, 5}
