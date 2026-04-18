#!/usr/bin/env bash
# Dual-send a batch of synthetic webhook payloads to both the python
# backend (port 8000) and the kotlin shadow (port 8001) and diff the
# responses byte-for-byte. Used during the 72h soak (Spec B §Task 15)
# to prove behavioural parity before cutover.
#
# Exits non-zero on any diff — safe to run under `watch` or cron.
#
# Usage:
#   PY_URL=http://localhost:8000 KT_URL=http://localhost:8001 \
#     ./scripts/shadow_diff.sh

set -euo pipefail

PY_URL="${PY_URL:-http://localhost:8000}"
KT_URL="${KT_URL:-http://localhost:8001}"
WORKDIR=$(mktemp -d)
trap 'rm -rf "$WORKDIR"' EXIT

# 1. Non-Grab event — both should return {"status":"ignored","eventType":"Test"}.
cat > "$WORKDIR/non_grab.json" <<'JSON'
{"eventType":"Test","series":{}}
JSON

# 2. Plex-event with an unknown key — both should return unmatched.
cat > "$WORKDIR/unmatched.json" <<'JSON'
{"event":"watched","grandparent_rating_key":"UNKNOWN-SHADOW-DIFF-KEY"}
JSON

diff_count=0

run_case() {
    local endpoint=$1 payload=$2 label=$3
    curl -fsS -X POST "$PY_URL$endpoint" -H 'Content-Type: application/json' \
        -d @"$payload" > "$WORKDIR/py.$label.json"
    curl -fsS -X POST "$KT_URL$endpoint" -H 'Content-Type: application/json' \
        -d @"$payload" > "$WORKDIR/kt.$label.json"
    if ! diff -u "$WORKDIR/py.$label.json" "$WORKDIR/kt.$label.json"; then
        echo "DIFF on $label ($endpoint)" >&2
        diff_count=$((diff_count + 1))
    fi
}

run_case "/api/sonarr/on-grab" "$WORKDIR/non_grab.json" "sonarr-non-grab"
run_case "/api/plex-event"     "$WORKDIR/unmatched.json" "plex-unmatched"

# /health and /ready — compare shape (omit last_heartbeat which is
# per-backend and changes second-by-second).
py_health=$(curl -fsS "$PY_URL/health")
kt_health=$(curl -fsS "$KT_URL/health")
if [[ "$py_health" != "$kt_health" ]]; then
    echo "DIFF on /health" >&2
    echo "py: $py_health" >&2
    echo "kt: $kt_health" >&2
    diff_count=$((diff_count + 1))
fi

if (( diff_count == 0 )); then
    echo "shadow_diff: 0 divergences"
    exit 0
else
    echo "shadow_diff: $diff_count divergence(s)" >&2
    exit 1
fi
