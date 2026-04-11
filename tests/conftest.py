from __future__ import annotations

import pytest
from pathlib import Path

from prioritarr.database import Database
from prioritarr.config import Settings


@pytest.fixture
def db(tmp_path):
    return Database(str(tmp_path / "test.db"))


@pytest.fixture
def settings():
    return Settings(
        sonarr_url="http://sonarr:8989",
        sonarr_api_key="testkey",
        tautulli_url="http://tautulli:8181",
        tautulli_api_key="testkey",
        qbit_url="http://vpn:8080",
        sab_url="http://sabnzbd:8080",
        sab_api_key="testkey",
        dry_run=True,
    )


FIXTURES = Path(__file__).parent / "fixtures"
