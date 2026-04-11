from __future__ import annotations

import json
from pathlib import Path

import httpx
import pytest
import respx

from prioritarr.clients.sonarr import SonarrClient

FIXTURES = Path(__file__).parent / "fixtures"
BASE_URL = "http://sonarr:8989"


@pytest.fixture
def client() -> SonarrClient:
    return SonarrClient(BASE_URL, api_key="testkey")


def _load(name: str) -> object:
    return json.loads((FIXTURES / name).read_text())


@respx.mock
def test_get_all_series(client: SonarrClient) -> None:
    data = _load("sonarr_series.json")
    respx.get(f"{BASE_URL}/api/v3/series").mock(
        return_value=httpx.Response(200, json=data)
    )

    result = client.get_all_series()

    assert len(result) == 2
    assert result[0]["title"] == "Attack on Titan"
    assert result[1]["title"] == "Demon Slayer"


@respx.mock
def test_get_episodes(client: SonarrClient) -> None:
    data = _load("sonarr_episodes.json")
    respx.get(f"{BASE_URL}/api/v3/episode").mock(
        return_value=httpx.Response(200, json=data)
    )

    result = client.get_episodes(series_id=1)

    assert len(result) == 3
    assert result[0]["seriesId"] == 1
    assert result[2]["hasFile"] is False


@respx.mock
def test_get_wanted_missing(client: SonarrClient) -> None:
    data = _load("sonarr_wanted_missing.json")
    respx.get(f"{BASE_URL}/api/v3/wanted/missing").mock(
        return_value=httpx.Response(200, json=data)
    )

    records = client.get_wanted_missing()

    assert len(records) == 2
    assert records[0]["series"]["title"] == "Attack on Titan"
    assert records[1]["series"]["tvdbId"] == 365946


@respx.mock
def test_get_queue(client: SonarrClient) -> None:
    data = _load("sonarr_queue.json")
    respx.get(f"{BASE_URL}/api/v3/queue").mock(
        return_value=httpx.Response(200, json=data)
    )

    records = client.get_queue()

    assert len(records) == 1
    assert records[0]["downloadId"] == "ABC123DEF456"
    assert records[0]["downloadClient"] == "qBittorrent"


@respx.mock
def test_trigger_series_search(client: SonarrClient) -> None:
    respx.post(f"{BASE_URL}/api/v3/command").mock(
        return_value=httpx.Response(201, json={"id": 1, "name": "SeriesSearch", "status": "queued"})
    )

    result = client.trigger_series_search(series_id=1)

    assert result["name"] == "SeriesSearch"
    sent_request = respx.calls.last.request
    body = json.loads(sent_request.content)
    assert body["name"] == "SeriesSearch"
    assert body["seriesId"] == 1


@respx.mock
def test_api_failure_raises(client: SonarrClient) -> None:
    respx.get(f"{BASE_URL}/api/v3/series").mock(
        return_value=httpx.Response(401, json={"message": "Unauthorized"})
    )

    with pytest.raises(httpx.HTTPStatusError):
        client.get_all_series()
