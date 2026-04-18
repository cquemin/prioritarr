#!/usr/bin/env bash
# Compare /api/v2/* responses across two backends — used during the
# Spec C shadow window to validate that new endpoint behaviour stays
# stable across restarts / image rebuilds.
#
# Usage:
#   OLD_URL=http://localhost:8000 NEW_URL=http://localhost:8001 \
#     API_KEY=ci-test-key ./scripts/shadow_diff_v2.sh

set -euo pipefail

OLD_URL="${OLD_URL:-http://localhost:8000}"
NEW_URL="${NEW_URL:-http://localhost:8001}"
API_KEY="${API_KEY:?API_KEY env required}"
WORKDIR=$(mktemp -d)
trap 'rm -rf "$WORKDIR"' EXIT

diff_count=0

run_case() {
    local endpoint=$1 label=$2
    curl -fsS -H "X-Api-Key: $API_KEY" "$OLD_URL$endpoint" \
        | python -m json.tool --sort-keys > "$WORKDIR/old.$label.json"
    curl -fsS -H "X-Api-Key: $API_KEY" "$NEW_URL$endpoint" \
        | python -m json.tool --sort-keys > "$WORKDIR/new.$label.json"
    if ! diff -u "$WORKDIR/old.$label.json" "$WORKDIR/new.$label.json"; then
        echo "DIFF on $label ($endpoint)" >&2
        diff_count=$((diff_count + 1))
    fi
}

run_case "/api/v2/settings"     "settings"
run_case "/api/v2/mappings"     "mappings"
run_case "/api/v2/series"       "series-list"
run_case "/api/v2/downloads"    "downloads-list"
run_case "/api/v2/audit?limit=5" "audit-list"

if (( diff_count == 0 )); then
    echo "shadow_diff_v2: 0 divergences"
    exit 0
else
    echo "shadow_diff_v2: $diff_count divergence(s)" >&2
    exit 1
fi
