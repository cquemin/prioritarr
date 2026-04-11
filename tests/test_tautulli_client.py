from __future__ import annotations

import json
from pathlib import Path

import httpx
import pytest
import respx

from prioritarr.clients.tautulli import TautulliClient

FIXTURES = Path(__file__).parent / "fixtures"
BASE_URL = "http://tautulli:8181"


@pytest.fixture
def client() -> TautulliClient:
    return TautulliClient(BASE_URL, api_key="testkey")


def _load(name: str) -> object:
    return json.loads((FIXTURES / name).read_text())


@respx.mock
def test_get_show_libraries(client: TautulliClient) -> None:
    data = _load("tautulli_libraries.json")
    respx.get(f"{BASE_URL}/api/v2").mock(
        return_value=httpx.Response(200, json=data)
    )

    result = client.get_show_libraries()

    # Should filter out the movie library; only 2 show libraries remain
    assert len(result) == 2
    assert all(lib["section_type"] == "show" for lib in result)
    assert result[0]["section_name"] == "TV Shows"
    assert result[1]["section_name"] == "Anime"


@respx.mock
def test_get_history_for_show(client: TautulliClient) -> None:
    data = _load("tautulli_history.json")
    respx.get(f"{BASE_URL}/api/v2").mock(
        return_value=httpx.Response(200, json=data)
    )

    entries = client.get_history(grandparent_rating_key="100")

    assert len(entries) == 2
    assert entries[0]["grandparent_title"] == "Attack on Titan"
    assert entries[0]["watched_status"] == 1


@respx.mock
def test_get_all_episode_history(client: TautulliClient) -> None:
    data = _load("tautulli_history.json")
    respx.get(f"{BASE_URL}/api/v2").mock(
        return_value=httpx.Response(200, json=data)
    )

    # Without a grandparent_rating_key — returns all history entries from fixture
    entries = client.get_history()

    assert len(entries) == 2
    # Verify media_index values are intact
    assert entries[0]["media_index"] == 5
    assert entries[1]["media_index"] == 6


@respx.mock
def test_api_failure_raises(client: TautulliClient) -> None:
    respx.get(f"{BASE_URL}/api/v2").mock(
        return_value=httpx.Response(500, json={"error": "internal server error"})
    )

    with pytest.raises(httpx.HTTPStatusError):
        client.get_show_libraries()
