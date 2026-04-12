from __future__ import annotations

import httpx


class SonarrClient:
    """Thin synchronous wrapper around the Sonarr v3 REST API."""

    def __init__(self, base_url: str, api_key: str, timeout: float = 120.0) -> None:
        self._base_url = base_url.rstrip("/")
        self._client = httpx.Client(
            headers={"X-Api-Key": api_key},
            timeout=timeout,
        )

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _get(self, path: str, params: dict | None = None) -> object:
        response = self._client.get(f"{self._base_url}{path}", params=params)
        response.raise_for_status()
        return response.json()

    def _post(self, path: str, json_data: dict) -> object:
        response = self._client.post(f"{self._base_url}{path}", json=json_data)
        response.raise_for_status()
        return response.json()

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def get_all_series(self) -> list:
        return self._get("/api/v3/series")

    def get_series(self, series_id: int) -> dict:
        return self._get(f"/api/v3/series/{series_id}")

    def get_episodes(self, series_id: int) -> list:
        return self._get("/api/v3/episode", params={"seriesId": series_id})

    def get_wanted_missing(self, page_size: int = 1000) -> list:
        data = self._get("/api/v3/wanted/missing", params={"pageSize": page_size})
        return data["records"]

    def get_wanted_cutoff(self, page_size: int = 1000) -> list:
        data = self._get("/api/v3/wanted/cutoff", params={"pageSize": page_size})
        return data["records"]

    def get_queue(self, page_size: int = 1000) -> list:
        data = self._get("/api/v3/queue", params={"pageSize": page_size})
        return data["records"]

    def trigger_series_search(self, series_id: int) -> dict:
        return self._post(
            "/api/v3/command",
            {"name": "SeriesSearch", "seriesId": series_id},
        )

    def trigger_cutoff_search(self, series_id: int) -> dict:
        return self._post(
            "/api/v3/command",
            {"name": "CutoffUnmetEpisodeSearch", "seriesId": series_id},
        )
