"""Verify the test-mode router is mounted iff PRIORITARR_TEST_MODE=true."""
from __future__ import annotations

import os
from importlib import reload


REQUIRED_ENV = {
    "PRIORITARR_SONARR_URL": "http://sonarr:8989",
    "PRIORITARR_SONARR_API_KEY": "testkey",
    "PRIORITARR_TAUTULLI_URL": "http://tautulli:8181",
    "PRIORITARR_TAUTULLI_API_KEY": "testkey",
    "PRIORITARR_QBIT_URL": "http://vpn:8080",
    "PRIORITARR_SAB_URL": "http://sabnzbd:8080",
    "PRIORITARR_SAB_API_KEY": "testkey",
    "PRIORITARR_DRY_RUN": "true",
}


def _reload_main_with_env(monkeypatch, test_mode: str) -> object:
    """Reload prioritarr.main after injecting env vars. Returns the reloaded module."""
    for k, v in REQUIRED_ENV.items():
        monkeypatch.setenv(k, v)
    monkeypatch.setenv("PRIORITARR_TEST_MODE", test_mode)
    import prioritarr.main as m
    reload(m)
    return m


def test_testing_endpoints_absent_in_production_mode(monkeypatch):
    m = _reload_main_with_env(monkeypatch, "false")
    paths = {getattr(r, "path", None) for r in m.app.routes}
    assert "/api/v1/_testing/reset" not in paths
    assert "/api/v1/_testing/stale-heartbeat" not in paths
    assert "/api/v1/_testing/inject-series-mapping" not in paths


def test_testing_endpoints_present_when_enabled(monkeypatch):
    m = _reload_main_with_env(monkeypatch, "true")
    paths = {getattr(r, "path", None) for r in m.app.routes}
    assert "/api/v1/_testing/reset" in paths
    assert "/api/v1/_testing/stale-heartbeat" in paths
    assert "/api/v1/_testing/inject-series-mapping" in paths


def test_testing_endpoints_absent_when_env_var_unset(monkeypatch):
    for k, v in REQUIRED_ENV.items():
        monkeypatch.setenv(k, v)
    monkeypatch.delenv("PRIORITARR_TEST_MODE", raising=False)
    import prioritarr.main as m
    reload(m)
    paths = {getattr(r, "path", None) for r in m.app.routes}
    assert "/api/v1/_testing/reset" not in paths
