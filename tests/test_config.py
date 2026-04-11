from __future__ import annotations

import os
from pathlib import Path

import pytest
import yaml

from prioritarr.config import (
    PriorityThresholds,
    Settings,
    load_yaml_config,
)


def test_load_yaml_config_reads_thresholds(tmp_path: Path) -> None:
    """YAML file is read and returned as a plain dict."""
    config_file = tmp_path / "config.yaml"
    config_file.write_text(
        yaml.dump(
            {
                "priority_thresholds": {
                    "p1_watch_pct_min": 0.75,
                    "p1_days_since_watch_max": 7,
                }
            }
        )
    )

    data = load_yaml_config(str(config_file))

    assert data["priority_thresholds"]["p1_watch_pct_min"] == 0.75
    assert data["priority_thresholds"]["p1_days_since_watch_max"] == 7


def test_load_yaml_config_missing_file_returns_empty(tmp_path: Path) -> None:
    """Non-existent path returns an empty dict without raising."""
    data = load_yaml_config(str(tmp_path / "does_not_exist.yaml"))
    assert data == {}


def test_settings_defaults() -> None:
    """Settings created with only required fields should have correct defaults."""
    settings = Settings(
        sonarr_url="http://sonarr:8989",
        sonarr_api_key="abc123",
        tautulli_url="http://tautulli:8181",
        tautulli_api_key="def456",
        qbit_url="http://qbit:8080",
        sab_url="http://sab:8080",
        sab_api_key="ghi789",
    )

    assert settings.dry_run is True
    assert settings.log_level == "INFO"
    assert settings.qbit_username is None
    assert settings.qbit_password is None
    assert settings.config_path is None

    # Nested config objects should carry their own defaults
    assert settings.priority_thresholds.p1_watch_pct_min == 0.90
    assert settings.intervals.reconcile_minutes == 15
    assert settings.cache.priority_ttl_minutes == 60
    assert settings.audit.retention_days == 90


def test_priority_thresholds_override() -> None:
    """PriorityThresholds can be partially overridden at construction time."""
    thresholds = PriorityThresholds(
        p1_watch_pct_min=0.80,
        p1_days_since_watch_max=10,
    )

    # Overridden fields
    assert thresholds.p1_watch_pct_min == 0.80
    assert thresholds.p1_days_since_watch_max == 10

    # Non-overridden fields retain defaults
    assert thresholds.p1_days_since_release_max == 7
    assert thresholds.p1_hiatus_gap_days == 14
    assert thresholds.p2_days_since_watch_max == 60
    assert thresholds.p3_unwatched_max == 3
    assert thresholds.p4_min_watched == 1
