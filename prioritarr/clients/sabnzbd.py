from __future__ import annotations

import httpx

# Maps internal priority levels (P1–P5) to SABnzbd priority values:
# P1 → Force (2), P2 → High (1), P3 → Normal (0), P4/P5 → Low (-1)
PRIORITY_MAP: dict[int, int] = {1: 2, 2: 1, 3: 0, 4: -1, 5: -1}


class SABClient:
    """Thin synchronous wrapper around the SABnzbd JSON API."""

    def __init__(self, base_url: str, api_key: str, timeout: float = 30.0) -> None:
        self._base_url = base_url.rstrip("/")
        self._api_key = api_key
        self._client = httpx.Client(timeout=timeout)

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _call(self, mode: str, **params: object) -> object:
        query = {"apikey": self._api_key, "mode": mode, "output": "json", **params}
        response = self._client.get(f"{self._base_url}/sabnzbd/api", params=query)
        response.raise_for_status()
        return response.json()

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def get_queue(self) -> list:
        data = self._call("queue")
        return data["queue"]["slots"]

    def set_priority(self, nzo_id: str, priority: int) -> object:
        return self._call("queue", name="priority", value=nzo_id, value2=str(priority))

    def move_to_position(self, nzo_id: str, position: int) -> object:
        return self._call("switch", value=nzo_id, value2=str(position))
