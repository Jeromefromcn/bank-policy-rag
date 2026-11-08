#!/usr/bin/env bash
# Behaviour tests for scripts/check-compose-limits.sh. Uses throwaway compose files only.
set -uo pipefail

SCRIPT="$(cd "$(dirname "$0")/.." && pwd)/check-compose-limits.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass=0
fail=0

check() { # name, expected exit code, actual exit code, output, [expected substring]
  local name="$1" want="$2" got="$3" out="$4" needle="${5:-}"
  if [[ "$got" -ne "$want" ]]; then
    echo "FAIL $name: exit $got, want $want"; echo "$out" | sed 's/^/    /'; fail=$((fail + 1)); return
  fi
  if [[ -n "$needle" && "$out" != *"$needle"* ]]; then
    echo "FAIL $name: output lacks '$needle'"; echo "$out" | sed 's/^/    /'; fail=$((fail + 1)); return
  fi
  echo "ok   $name"; pass=$((pass + 1))
}

limited() { # service name, [extra yaml lines]
  cat <<YAML
  $1:
    image: busybox:1.37.0
${2:-}
    deploy:
      resources:
        limits:
          cpus: "0.5"
          memory: 64M
YAML
}

run() { out="$("$SCRIPT" "$TMP/compose.yaml" 2>&1)"; code=$?; }

{ echo "services:"; limited alpha; limited beta; } > "$TMP/compose.yaml"; run
check "all limited -> exit 0" 0 "$code" "$out" "2 services limited"

cat > "$TMP/compose.yaml" <<'YAML'
services:
  alpha:
    image: busybox:1.37.0
    deploy:
      resources:
        limits:
          cpus: "0.5"
YAML
run
check "missing memory -> exit 1" 1 "$code" "$out" "alpha: missing memory"

cat > "$TMP/compose.yaml" <<'YAML'
services:
  alpha:
    image: busybox:1.37.0
    deploy:
      resources:
        limits:
          memory: 64M
YAML
run
check "missing cpus -> exit 1" 1 "$code" "$out" "alpha: missing cpus"

{ echo "services:"; limited alpha; printf '  gamma:\n    image: busybox:1.37.0\n    profiles: [extra]\n'; } > "$TMP/compose.yaml"; run
check "profile-only service is checked -> exit 1" 1 "$code" "$out" "gamma: missing cpus"

out="$("$SCRIPT" "$TMP/nope.yaml" 2>&1)"; code=$?
check "missing file -> exit 2" 2 "$code" "$out" "not found"

echo "$pass passed, $fail failed"
[[ "$fail" -eq 0 ]]
