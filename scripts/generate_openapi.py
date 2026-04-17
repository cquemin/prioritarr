"""Generate openapi.json from the FastAPI app without requiring a running server.

Sets throwaway env vars so the module imports cleanly, then dumps the schema
with sorted keys for deterministic diffs.
"""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path

# Ensure the repo root is on sys.path so `import prioritarr` works when
# this script is invoked from any cwd.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))


# Inject required env vars before importing prioritarr.main — load_settings_from_env
# is called at module-import time (inside lifespan setup), but the module imports
# happen eagerly and the app construction reads config later. We set these anyway
# so any import-time settings read succeeds.
_REQUIRED_ENV = {
    "PRIORITARR_SONARR_URL": "http://x",
    "PRIORITARR_SONARR_API_KEY": "x",
    "PRIORITARR_TAUTULLI_URL": "http://x",
    "PRIORITARR_TAUTULLI_API_KEY": "x",
    "PRIORITARR_QBIT_URL": "http://x",
    "PRIORITARR_SAB_URL": "http://x",
    "PRIORITARR_SAB_API_KEY": "x",
}
for k, v in _REQUIRED_ENV.items():
    os.environ.setdefault(k, v)

# Import only AFTER env vars are in place.
from prioritarr.main import app  # noqa: E402


def main() -> None:
    schema = app.openapi()
    json.dump(schema, sys.stdout, sort_keys=True, indent=2)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
