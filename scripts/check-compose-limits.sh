#!/usr/bin/env bash
# Fails if any compose service (in any profile) lacks deploy.resources.limits.cpus or .memory.
# Usage: scripts/check-compose-limits.sh [compose-file]   (default: compose.yaml)
set -euo pipefail

file="${1:-compose.yaml}"
[[ -f "$file" ]] || { echo "error: compose file not found: $file" >&2; exit 2; }

# Interpolation is disabled so the check does not need .env values.
json="$(docker compose -f "$file" --profile '*' config --no-interpolate --format json)"

problems="$(jq -r '
  .services | to_entries[] | .key as $s | .value.deploy.resources.limits as $l
  | (if ($l.cpus // null) == null then "\($s): missing cpus" else empty end),
    (if ($l.memory // null) == null then "\($s): missing memory" else empty end)
' <<<"$json")"

if [[ -n "$problems" ]]; then
  echo "$problems" >&2
  exit 1
fi
echo "ok: $(jq '.services | length' <<<"$json") services limited"
