from __future__ import annotations

import httpx


class QBitClient:
    """Thin synchronous wrapper around the qBittorrent Web API v2."""

    def __init__(
        self,
        base_url: str,
        username: str = "",
        password: str = "",
        timeout: float = 30.0,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._username = username
        self._password = password
        self._authenticated = False
        self._client = httpx.Client(timeout=timeout)

    # ------------------------------------------------------------------
    # Auth
    # ------------------------------------------------------------------

    def _login(self) -> None:
        response = self._client.post(
            f"{self._base_url}/api/v2/auth/login",
            data={"username": self._username, "password": self._password},
        )
        response.raise_for_status()
        self._authenticated = True

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _request(self, method: str, path: str, **kwargs: object) -> httpx.Response:
        response = self._client.request(method, f"{self._base_url}{path}", **kwargs)
        if response.status_code in (401, 403) and not self._authenticated:
            self._login()
            response = self._client.request(method, f"{self._base_url}{path}", **kwargs)
        response.raise_for_status()
        return response

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def get_torrents(self, category: str | None = None) -> list:
        params: dict[str, object] = {}
        if category is not None:
            params["category"] = category
        response = self._request("GET", "/api/v2/torrents/info", params=params)
        return response.json()

    def pause(self, hashes: list[str]) -> None:
        self._request(
            "POST",
            "/api/v2/torrents/pause",
            data={"hashes": "|".join(hashes)},
        )

    def resume(self, hashes: list[str]) -> None:
        self._request(
            "POST",
            "/api/v2/torrents/resume",
            data={"hashes": "|".join(hashes)},
        )

    def top_priority(self, hashes: list[str]) -> None:
        self._request(
            "POST",
            "/api/v2/torrents/topPrio",
            data={"hashes": "|".join(hashes)},
        )

    def bottom_priority(self, hashes: list[str]) -> None:
        self._request(
            "POST",
            "/api/v2/torrents/bottomPrio",
            data={"hashes": "|".join(hashes)},
        )
