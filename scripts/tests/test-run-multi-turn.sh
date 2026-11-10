#!/usr/bin/env bash
# Behaviour tests for scripts/run-multi-turn.sh. A stub `git` on PATH controls the dirty-tree check;
# --dry-run never starts the app or the runner, so nothing here needs Elasticsearch, Ollama or DeepSeek.
set -uo pipefail

SCRIPT="$(cd "$(dirname "$0")/.." && pwd)/run-multi-turn.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir "$TMP/bin"
SET="$TMP/multi-turn.yaml"
echo "- {id: m1}" > "$SET"

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

stub_git() { # status output
  cat > "$TMP/bin/git" <<STUB
#!/usr/bin/env bash
case "\$1" in
  status) printf '%s' '$1' ;;
  rev-parse) echo abc1234 ;;
esac
STUB
  chmod +x "$TMP/bin/git"
}

run() { out="$(PATH="$TMP/bin:$PATH" RAG_ENV_PREFIX=rag_dev MULTI_TURN_FILE="${set_file:-$SET}" "$SCRIPT" "$@" 2>&1)"; code=$?; }

stub_git " M app/src/Foo.java"; run --dry-run
check "dirty tree -> exit 1" 1 "$code" "$out" "working tree is dirty"

stub_git ""; run --dry-run bogus
check "unknown argument -> exit 2" 2 "$code" "$out" "usage"

run --dry-run --split=all
check "split all is not allowed -> exit 2" 2 "$code" "$out" "usage"

set_file="$TMP/missing.yaml"; run --dry-run
check "missing set -> exit 1" 1 "$code" "$out" "missing.yaml"
set_file=""

run --dry-run
check "dry run -> exit 0" 0 "$code" "$out"
check "runner flag" 0 "$code" "$out" "--multi-turn=true"
check "set file passed" 0 "$code" "$out" "--multi-turn-file=$SET"
check "dev split by default" 0 "$code" "$out" "--split=dev"
check "structural nomic index" 0 "$code" "$out" "RAG_CHUNKS_INDEX=rag_dev-chunks-nomic768-struct400-v1"
check "callers passed by name only" 0 "$code" "$out" "RAG_SECURITY_APIKEYS_{0,1,2}_{KEY,PRINCIPAL,CLEARANCE}"

run --dry-run --split=test
check "split passthrough" 0 "$code" "$out" "--split=test"

if grep -q -- "--promote" "$SCRIPT"; then
  echo "FAIL script must never promote an alias"; fail=$((fail + 1))
else
  echo "ok   script never promotes an alias"; pass=$((pass + 1))
fi

echo "$pass passed, $fail failed"
[[ "$fail" -eq 0 ]]
