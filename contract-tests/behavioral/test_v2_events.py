"""SSE /api/v2/events contract tests (Spec C §8)."""
from __future__ import annotations

import json
import os
import threading
import time

import httpx
import pytest


pytestmark = pytest.mark.skipif(
    os.environ.get("CONTRACT_TEST_API_KEY") is None,
    reason="CONTRACT_TEST_API_KEY not set",
)


def _parse_sse_chunk(text: str):
    """Yield (id, type, data) tuples from a block of SSE-formatted text."""
    event_type = ev_id = data = None
    for line in text.splitlines():
        if not line.strip():
            if event_type and data is not None:
                yield (ev_id, event_type, json.loads(data))
            event_type = ev_id = data = None
            continue
        if line.startswith("event: "):
            event_type = line[len("event: "):]
        elif line.startswith("id: "):
            try:
                ev_id = int(line[len("id: "):])
            except ValueError:
                ev_id = None
        elif line.startswith("data: "):
            data = line[len("data: "):]


def test_recompute_fires_event(auth_client: httpx.Client, base_url: str, api_key: str) -> None:
    """Subscribe to SSE, trigger a recompute in a thread, confirm the event arrives."""
    received = []
    stop = threading.Event()

    def listener():
        with httpx.Client(base_url=base_url, timeout=10.0,
                          headers={"X-Api-Key": api_key}) as c:
            with c.stream("GET", "/api/v2/events") as resp:
                buffer = ""
                for chunk in resp.iter_text():
                    buffer += chunk
                    while "\n\n" in buffer:
                        block, buffer = buffer.split("\n\n", 1)
                        received.extend(_parse_sse_chunk(block + "\n\n"))
                    if stop.is_set() or len(received) >= 2:
                        return

    t = threading.Thread(target=listener, daemon=True)
    t.start()
    time.sleep(0.5)  # give the listener time to connect

    auth_client.post("/api/v2/series/1/recompute")
    t.join(timeout=10.0)
    stop.set()

    types = [type_ for _id, type_, _data in received]
    assert "priority-recomputed" in types, f"expected priority-recomputed; got {types}"


def test_heartbeat_emitted(auth_client: httpx.Client, base_url: str, api_key: str) -> None:
    """With no other activity, a heartbeat should arrive within 35s."""
    with httpx.Client(base_url=base_url, timeout=40.0,
                      headers={"X-Api-Key": api_key}) as c:
        with c.stream("GET", "/api/v2/events") as resp:
            start = time.time()
            buffer = ""
            saw_heartbeat = False
            for chunk in resp.iter_text():
                buffer += chunk
                while "\n\n" in buffer:
                    block, buffer = buffer.split("\n\n", 1)
                    for _id, type_, _data in _parse_sse_chunk(block + "\n\n"):
                        if type_ == "heartbeat":
                            saw_heartbeat = True
                            break
                if saw_heartbeat or time.time() - start > 35:
                    break
            assert saw_heartbeat, "no heartbeat event within 35s"
