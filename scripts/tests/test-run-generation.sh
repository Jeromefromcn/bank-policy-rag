#!/usr/bin/env bash
# Behaviour tests for scripts/run-generation.sh. A stub `git` on PATH controls the dirty-tree check;
# --dry-run never starts the app or the runner, so nothing here needs Elasticsearch, Ollama or DeepSeek.
set -uo pipefail

SCRIPT="$(cd "$(dirname "$0")/.." && pwd)/run-generation.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir "$TMP/bin"

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

run() { out="$(PATH="$TMP/bin:$PATH" RAG_ENV_PREFIX=rag_dev "$SCRIPT" "$@" 2>&1)"; code=$?; }

stub_git " M app/src/Foo.java"; run --dry-run sweep
check "dirty tree -> exit 1" 1 "$code" "$out" "working tree is dirty"

stub_git ""; run --dry-run bogus
check "unknown step -> exit 2" 2 "$code" "$out" "usage"

run --dry-run
check "no step -> exit 2" 2 "$code" "$out" "usage"

run --dry-run sweep
check "sweep dry run -> exit 0" 0 "$code" "$out"
check "sweep: runner flag" 0 "$code" "$out" "--sweep=true"
check "sweep: structural nomic index" 0 "$code" "$out" "RAG_CHUNKS_INDEX=rag_dev-chunks-nomic768-struct400-v1"
check "sweep: sections index" 0 "$code" "$out" "RAG_SECTIONS_INDEX=rag_dev-sections-struct400-v1"
check "sweep: all three callers are passed by name only" 0 "$code" "$out" "RAG_SECURITY_APIKEYS_0_KEY"
check "sweep: public caller" 0 "$code" "$out" "RAG_SECURITY_APIKEYS_2_CLEARANCE"

run --dry-run generate
check "generate dry run -> exit 0" 0 "$code" "$out"
check "generate: runner flag" 0 "$code" "$out" "--generation=true"
check "generate: dev split by default" 0 "$code" "$out" "--split=dev"

run --dry-run generate --split=test
check "generate: split passthrough" 0 "$code" "$out" "--split=test"

run --dry-run generate --split=bogus
check "bad split -> exit 2" 2 "$code" "$out" "usage"

if grep -q -- "--promote" "$SCRIPT"; then
  echo "FAIL script must never promote an alias"; fail=$((fail + 1))
else
  echo "ok   script never promotes an alias"; pass=$((pass + 1))
fi

echo "$pass passed, $fail failed"
[[ "$fail" -eq 0 ]]
