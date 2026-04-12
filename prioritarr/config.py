from __future__ import annotations

import dataclasses
import os
from typing import Any

import yaml


# ---------------------------------------------------------------------------
# YAML loader
# ---------------------------------------------------------------------------


def load_yaml_config(path: str) -> dict[str, Any]:
    """Load a YAML file and return its contents as a dict.

    Returns an empty dict if the file does not exist.
    """
    try:
        with open(path, "r", encoding="utf-8") as fh:
            data = yaml.safe_load(fh)
            return data if isinstance(data, dict) else {}
    except FileNotFoundError:
        return {}


# ---------------------------------------------------------------------------
# Helper
# ---------------------------------------------------------------------------


def _apply_yaml_section(obj: Any, data: dict[str, Any]) -> None:
    """Override dataclass fields on *obj* with matching keys from *data*.

    Only keys that correspond to existing dataclass fields are applied;
    unknown keys are silently ignored.
    """
    field_names = {f.name for f in dataclasses.fields(obj)}
    for key, value in data.items():
        if key in field_names:
            setattr(obj, key, value)


# ---------------------------------------------------------------------------
# Configuration dataclasses
# ---------------------------------------------------------------------------


@dataclasses.dataclass
class PriorityThresholds:
    p1_watch_pct_min: float = 0.90
    p1_days_since_watch_max: int = 14
    p1_days_since_release_max: int = 7
    p1_hiatus_gap_days: int = 14
    p1_hiatus_release_window_days: int = 28
    p2_days_since_watch_max: int = 60
    p3_unwatched_max: int = 3
    p3_days_since_watch_max: int = 60
    p4_min_watched: int = 1


@dataclasses.dataclass
class Intervals:
    reconcile_minutes: int = 15
    backfill_sweep_hours: int = 2
    cutoff_sweep_hours: int = 24
    backfill_max_searches_per_sweep: int = 10
    backfill_delay_between_searches_seconds: int = 30
    cutoff_max_searches_per_sweep: int = 5


@dataclasses.dataclass
class CacheConfig:
    priority_ttl_minutes: int = 60


@dataclasses.dataclass
class AuditConfig:
    retention_days: int = 90
    webhook_dedupe_hours: int = 24


# ---------------------------------------------------------------------------
# Top-level Settings
# ---------------------------------------------------------------------------


@dataclasses.dataclass
class Settings:
    # Required fields (no defaults)
    sonarr_url: str
    sonarr_api_key: str
    tautulli_url: str
    tautulli_api_key: str
    qbit_url: str
    sab_url: str
    sab_api_key: str

    # Optional connection fields
    qbit_username: str | None = None
    qbit_password: str | None = None
    redis_url: str | None = None

    # Behaviour flags
    dry_run: bool = True
    log_level: str = "INFO"

    # Path to user-supplied YAML config (overlay on top of defaults)
    config_path: str | None = None

    # Nested config sections
    priority_thresholds: PriorityThresholds = dataclasses.field(
        default_factory=PriorityThresholds
    )
    intervals: Intervals = dataclasses.field(default_factory=Intervals)
    cache: CacheConfig = dataclasses.field(default_factory=CacheConfig)
    audit: AuditConfig = dataclasses.field(default_factory=AuditConfig)


# ---------------------------------------------------------------------------
# Factory: build Settings from environment + optional YAML overlay
# ---------------------------------------------------------------------------


def _env(key: str, default: str | None = None) -> str | None:
    """Read a PRIORITARR_* env var, falling back to *default*."""
    return os.environ.get(f"PRIORITARR_{key}", default)


def _env_required(key: str) -> str:
    value = _env(key)
    if not value:
        raise ValueError(
            f"Required environment variable PRIORITARR_{key} is not set."
        )
    return value


def load_settings_from_env() -> Settings:
    """Build a :class:`Settings` instance from PRIORITARR_* env vars.

    If ``PRIORITARR_CONFIG_PATH`` is set (and the file exists), the YAML
    file is loaded and its sections overlay the dataclass defaults.
    """
    config_path = _env("CONFIG_PATH")

    # Start with nested config objects at their defaults
    thresholds = PriorityThresholds()
    intervals = Intervals()
    cache = CacheConfig()
    audit = AuditConfig()

    # Apply YAML overlay when available
    yaml_data: dict[str, Any] = {}
    if config_path:
        yaml_data = load_yaml_config(config_path)

    if yaml_data.get("priority_thresholds"):
        _apply_yaml_section(thresholds, yaml_data["priority_thresholds"])
    if yaml_data.get("intervals"):
        _apply_yaml_section(intervals, yaml_data["intervals"])
    if yaml_data.get("cache"):
        _apply_yaml_section(cache, yaml_data["cache"])
    if yaml_data.get("audit"):
        _apply_yaml_section(audit, yaml_data["audit"])

    dry_run_raw = _env("DRY_RUN", "true").lower()  # type: ignore[union-attr]
    dry_run = dry_run_raw not in ("false", "0", "no")

    return Settings(
        sonarr_url=_env_required("SONARR_URL"),
        sonarr_api_key=_env_required("SONARR_API_KEY"),
        tautulli_url=_env_required("TAUTULLI_URL"),
        tautulli_api_key=_env_required("TAUTULLI_API_KEY"),
        qbit_url=_env_required("QBIT_URL"),
        sab_url=_env_required("SAB_URL"),
        sab_api_key=_env_required("SAB_API_KEY"),
        qbit_username=_env("QBIT_USERNAME"),
        qbit_password=_env("QBIT_PASSWORD"),
        redis_url=_env("REDIS_URL"),
        dry_run=dry_run,
        log_level=_env("LOG_LEVEL", "INFO"),  # type: ignore[arg-type]
        config_path=config_path,
        priority_thresholds=thresholds,
        intervals=intervals,
        cache=cache,
        audit=audit,
    )
