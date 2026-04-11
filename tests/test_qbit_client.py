from __future__ import annotations

import json
from pathlib import Path

import httpx
import pytest
import respx

from prioritarr.clients.qbittorrent import QBitClient

FIXTURES = Path(__file__).parent / "fixtures"
BASE_URL = "http://qbit:8080"

HASH_A = "aabbccddeeff00112233445566778899aabbccdd"
HASH_B = "1122334455667788990011223344556677889900"


@pytest.fixture
def client() -> QBitClient:
    return QBitClient(BASE_URL, username="admin", password="secret")


def _load(name: str) -> object:
    return json.loads((FIXTURES / name).read_text())


@respx.mock
def test_get_torrents(client: QBitClient) -> None:
    data = _load("qbit_torrents.json")
    respx.get(f"{BASE_URL}/api/v2/torrents/info").mock(
        return_value=httpx.Response(200, json=data)
    )

    torrents = client.get_torrents()

    assert len(torrents) == 2
    assert torrents[0]["hash"] == HASH_A
    assert torrents[1]["category"] == "anime"


@respx.mock
def test_pause_torrent(client: QBitClient) -> None:
    respx.post(f"{BASE_URL}/api/v2/torrents/pause").mock(
        return_value=httpx.Response(200)
    )

    client.pause([HASH_A])

    sent = respx.calls.last.request
    assert b"hashes=" in sent.content
    assert HASH_A.encode() in sent.content


@respx.mock
def test_resume_torrent(client: QBitClient) -> None:
    respx.post(f"{BASE_URL}/api/v2/torrents/resume").mock(
        return_value=httpx.Response(200)
    )

    client.resume([HASH_B])

    sent = respx.calls.last.request
    assert HASH_B.encode() in sent.content


@respx.mock
def test_set_top_priority(client: QBitClient) -> None:
    respx.post(f"{BASE_URL}/api/v2/torrents/topPrio").mock(
        return_value=httpx.Response(200)
    )

    client.top_priority([HASH_A])

    sent = respx.calls.last.request
    assert HASH_A.encode() in sent.content


@respx.mock
def test_set_bottom_priority(client: QBitClient) -> None:
    respx.post(f"{BASE_URL}/api/v2/torrents/bottomPrio").mock(
        return_value=httpx.Response(200)
    )

    client.bottom_priority([HASH_B])

    sent = respx.calls.last.request
    assert HASH_B.encode() in sent.content


@respx.mock
def test_login_called_on_403_retry(client: QBitClient) -> None:
    data = _load("qbit_torrents.json")

    # First call returns 403, login succeeds, retry returns 200
    torrents_route = respx.get(f"{BASE_URL}/api/v2/torrents/info")
    torrents_route.side_effect = [
        httpx.Response(403),
        httpx.Response(200, json=data),
    ]
    respx.post(f"{BASE_URL}/api/v2/auth/login").mock(
        return_value=httpx.Response(200, text="Ok.")
    )

    torrents = client.get_torrents()

    # Login should have been triggered, then retry succeeded
    assert client._authenticated is True
    assert len(torrents) == 2
    assert torrents[0]["name"] == "Attack on Titan S01 [1080p BluRay]"
