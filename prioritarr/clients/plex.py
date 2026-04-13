from __future__ import annotations

import logging
from xml.etree import ElementTree

import httpx

logger = logging.getLogger(__name__)


class PlexClient:
    """Direct Plex Media Server client for watch-status queries.

    Used as a fallback when Tautulli history is stale — Plex always knows
    the current viewCount / lastViewedAt per episode.
    """

    def __init__(self, base_url: str, token: str, timeout: float = 60.0) -> None:
        self._base_url = base_url.rstrip("/")
        self._token = token
        self._client = httpx.Client(timeout=timeout)

    def _get_xml(self, path: str, params: dict | None = None) -> ElementTree.Element | None:
        params = params or {}
        params["X-Plex-Token"] = self._token
        resp = self._client.get(f"{self._base_url}{path}", params=params)
        resp.raise_for_status()
        return ElementTree.fromstring(resp.content)

    def get_library_sections(self) -> list[dict]:
        """Return all library sections with id, title, type."""
        root = self._get_xml("/library/sections")
        if root is None:
            return []
        return [
            {
                "id": d.get("key"),
                "title": d.get("title"),
                "type": d.get("type"),
            }
            for d in root.findall("Directory")
        ]

    def get_show_episodes_watch_status(self, rating_key: str) -> list[dict]:
        """Return all episodes for a show with watch status.

        Each entry: {season, episode, watched (bool), last_viewed_at (int|None)}
        """
        # /library/metadata/{ratingKey}/allLeaves returns all episodes
        root = self._get_xml(f"/library/metadata/{rating_key}/allLeaves")
        if root is None:
            return []
        episodes = []
        for video in root.findall("Video"):
            season = video.get("parentIndex")
            episode = video.get("index")
            view_count = int(video.get("viewCount", "0"))
            last_viewed = video.get("lastViewedAt")
            if season is not None and episode is not None:
                episodes.append({
                    "season": int(season),
                    "episode": int(episode),
                    "watched": view_count > 0,
                    "last_viewed_at": int(last_viewed) if last_viewed else None,
                })
        return episodes
