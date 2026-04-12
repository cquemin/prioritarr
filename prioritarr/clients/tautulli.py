from __future__ import annotations

import httpx


class TautulliClient:
    """Thin synchronous wrapper around the Tautulli v2 REST API."""

    def __init__(self, base_url: str, api_key: str, timeout: float = 120.0) -> None:
        self._base_url = base_url.rstrip("/")
        self._api_key = api_key
        self._client = httpx.Client(timeout=timeout)

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _call(self, cmd: str, **params: object) -> object:
        query = {"apikey": self._api_key, "cmd": cmd, **params}
        response = self._client.get(f"{self._base_url}/api/v2", params=query)
        response.raise_for_status()
        return response.json()["response"]["data"]

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def get_show_libraries(self) -> list:
        data = self._call("get_libraries")
        return [lib for lib in data if lib.get("section_type") == "show"]

    def get_history(
        self,
        grandparent_rating_key: str | None = None,
        media_type: str | None = None,
        length: int = 1000,
    ) -> list:
        kwargs: dict[str, object] = {"length": length}
        if grandparent_rating_key is not None:
            kwargs["grandparent_rating_key"] = grandparent_rating_key
        if media_type is not None:
            kwargs["media_type"] = media_type
        data = self._call("get_history", **kwargs)
        return data["data"]

    def get_metadata(self, rating_key: str) -> object:
        return self._call("get_metadata", rating_key=rating_key)

    def get_library_media_info(self, section_id: int, length: int = 5000) -> list:
        data = self._call("get_library_media_info", section_id=section_id, length=length)
        return data["data"]
