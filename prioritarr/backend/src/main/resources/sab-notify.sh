#!/bin/sh
# prioritarr-notify.sh — SABnzbd post-processing script.
#
# Place this file in SAB's scripts folder (Config → Folders → "Post-
# Processing Scripts Folder"), make it executable (`chmod +x`), then:
#
#   1. Config → Switches → "Default Post-Processing Script" = this file
#      (or assign per-category in Config → Categories).
#
#   2. The script will be invoked once per completed download. It pings
#      prioritarr's /api/sab/webhook so the UI flips download state
#      immediately rather than waiting for the next reconcile tick.
#
# Required environment (set in SAB's "extra_param" or as system env):
#
#   PRIORITARR_URL       default http://prioritarr:8000
#   PRIORITARR_API_KEY   X-Api-Key header (required if prioritarr's
#                         API_KEY is set; safe to leave blank otherwise)
#
# SAB sets these env vars + positional args when it invokes the script:
#
#   $1 = final job directory
#   $2 = original NZB filename
#   $3 = clean job name
#   $4 = indexer report id
#   $5 = user-defined category
#   $6 = group
#   $7 = post-processing status (0 = OK, non-zero = failed)
#
#   SAB_NZO_ID, SAB_PP_STATUS, SAB_FAIL_MESSAGE — env equivalents.

set -e

PRIORITARR_URL="${PRIORITARR_URL:-http://prioritarr:8000}"
NZO="${SAB_NZO_ID:-}"
STATUS_CODE="${SAB_PP_STATUS:-${7:-0}}"
FAIL_MSG="${SAB_FAIL_MESSAGE:-}"

# Map SAB's numeric pp-status into the strings prioritarr understands.
case "$STATUS_CODE" in
  0) STATUS=Completed ;;
  *) STATUS=Failed ;;
esac

if [ -z "$NZO" ]; then
  echo "prioritarr-notify: no SAB_NZO_ID; nothing to report"
  exit 0
fi

curl -sS -X POST \
  -H "X-Api-Key: ${PRIORITARR_API_KEY:-}" \
  --data-urlencode "nzo_id=$NZO" \
  --data-urlencode "status=$STATUS" \
  --data-urlencode "fail_message=$FAIL_MSG" \
  "$PRIORITARR_URL/api/sab/webhook" \
  -o /dev/null -w "prioritarr-notify: status=%{http_code} for $NZO\n"

exit 0
