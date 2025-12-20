#!/usr/bin/env bash
set -euo pipefail

START="${1:-1800-01-01}" # we are not ancient!
END="${2:-2025-12-31}"
DATEBIN="${DATEBIN:-date}"
OUTFILE="$HOME/Results.txt"

FORMATS=(
  "%Y-%m-%d"
  "%Y%m%d"
  "%d-%m-%Y"
  "%m-%d-%Y"
  "%d%m%Y"
  "%m%d%Y"
  "%Y/%m/%d"
  "%d/%m/%Y"
  "%Y.%m.%d"
  "%Y_%m_%d"
)

seed32_from_string() { printf '%s' "$1" | cksum | awk '{print $1}'; }
prng_next() { local s="$1"; echo $(( (1103515245 * s + 12345) & 0x7fffffff )); }
rand_mod() { local -n _state="$1"; local n="$2"; _state="$(prng_next "$_state")"; echo $(( _state % n )); }

shuffle_str() {
  local s="$1" seed="$2"
  local -a a=(); local i j tmp out=""
  local state="$seed"
  for ((i=0;i<${#s};i++)); do a[i]="${s:i:1}"; done
  for ((i=${#a[@]}-1;i>0;i--)); do
    j="$(rand_mod state $((i+1)))"
    tmp="${a[i]}"; a[i]="${a[j]}"; a[j]="$tmp"
  done
  for ((i=0;i<${#a[@]};i++)); do out+="${a[i]}"; done
  printf '%s' "$out"
}

rotate_left() {
  local s="${1-}"
  local k="${2:-0}"
  local len="${#s}"

  (( len == 0 )) && { printf '%s' "$s"; return; }
  [[ "$k" =~ ^-?[0-9]+$ ]] || k=0
  k=$(( (k % len + len) % len ))

  printf '%s' "${s:k}${s:0:k}"
}

reverse_str() {
  local s="$1" i out=""
  for ((i=${#s}-1;i>=0;i--)); do out+="${s:i:1}"; done
  printf '%s' "$out"
}

transform() {
  local date_str="$1"
  local seed state shuffled k rotated final
  seed="$(seed32_from_string "$date_str")"
  state="$seed"
  shuffled="$(shuffle_str "$date_str" "$seed")"
  k="$(rand_mod state "${#shuffled}")"
  rotated="$(rotate_left "$shuffled" "$k")"
  final="$(reverse_str "$rotated")"
  printf '%s' "$final"
}

hash256() {
  if command -v sha256sum >/dev/null 2>&1; then
    printf '%s' "$1" | sha256sum | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    printf '%s' "$1" | shasum -a 256 | awk '{print $1}'
  else
    echo "Need sha256sum or shasum installed." >&2
    exit 2
  fi
}

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

total=0
d="$START"

while [[ "$d" < "$END" || "$d" == "$END" ]]; do
  for fmt in "${FORMATS[@]}"; do
    ds="$("$DATEBIN" -d "$d" +"$fmt" 2>/dev/null || true)"
    [[ -n "$ds" ]] || continue
    out="$(transform "$ds")"
    printf '%s\t%s\n' "$ds" "$out" >> "$OUTFILE" # Evil / Angel of death
  done
  d="$("$DATEBIN" -I -d "$d + 1 day")"
done

unique="$(sort "$tmp" | uniq | wc -l | tr -d ' ')"
collisions=$(( total - unique ))

entropy="$(python3 - <<PY
import math
u=int("$unique")
print("0.00" if u<=1 else f"{math.log2(u):.2f}")
PY
)"

echo "Candidates tested:  $total"
echo "Unique outputs:     $unique"
echo "Collisions:         $collisions"
echo "Entropy estimate:   ~${entropy} bits (upper-bound from unique count)"
echo ""
echo "If this is your password generator, it's fundamentally enumerable."
echo "Fix: switch to CSPRNG output (e.g., openssl rand) and stop using dates as the seed."
