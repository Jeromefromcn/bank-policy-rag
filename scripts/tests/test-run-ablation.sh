#!/usr/bin/env bash
# Behaviour tests for scripts/run-ablation.sh. A stub `git` on PATH controls the dirty-tree check;
# --dry-run never starts the app or the runner, so nothing here needs Elasticsearch or Ollama.
set -uo pipefail

SCRIPT="$(cd "$(dirname "$0")/.." && pwd)/run-ablation.sh"
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

count() { # pattern, text -> number of matching lines
  grep -c -- "$1" <<<"$2" || true
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

stub_git " M app/src/Foo.java"; run --dry-run retrieval
check "dirty tree -> exit 1" 1 "$code" "$out" "working tree is dirty"

stub_git ""; run --dry-run bogus
check "unknown table -> exit 2" 2 "$code" "$out" "usage"

run --dry-run
check "no table -> exit 2" 2 "$code" "$out" "usage"

run --dry-run retrieval
check "retrieval dry run -> exit 0" 0 "$code" "$out"
check "retrieval: two app starts" 2 "$(count '^+ START' "$out")" "$out"
check "retrieval: six runner calls" 6 "$(count '^+ RUN' "$out")" "$out"
check "retrieval: fixed index named" 0 "$code" "$out" "RAG_CHUNKS_INDEX=rag_dev-chunks-nomic768-fixed512-v1"
check "retrieval: structural index named" 0 "$code" "$out" "RAG_CHUNKS_INDEX=rag_dev-chunks-nomic768-struct400-v1"
check "retrieval: structural sections index named" 0 "$code" "$out" "RAG_SECTIONS_INDEX=rag_dev-sections-struct400-v1"
for m in VECTOR BM25 HYBRID; do
  check "retrieval: mode $m run twice" 2 "$(count "mode=$m" "$out")" "$out"
done

run --dry-run embedding
check "embedding dry run -> exit 0" 0 "$code" "$out"
check "embedding: three app starts" 3 "$(count '^+ START' "$out")" "$out"
check "embedding: three runner calls, all HYBRID" 3 "$(count '^+ RUN.*--mode=HYBRID' "$out")" "$out"
check "embedding: bge-small index" 0 "$code" "$out" "RAG_CHUNKS_INDEX=rag_dev-chunks-bgesmall384-struct400-v1"
check "embedding: bge-m3 index" 0 "$code" "$out" "RAG_CHUNKS_INDEX=rag_dev-chunks-bgem3-1024-struct400-v1"
check "embedding: model selected by env" 0 "$code" "$out" "RAG_EMBEDDING_MODEL=bge-m3"

if grep -q -- "--promote" "$SCRIPT"; then
  echo "FAIL script must never promote an alias"; fail=$((fail + 1))
else
  echo "ok   script never promotes an alias"; pass=$((pass + 1))
fi

echo "$pass passed, $fail failed"
[[ "$fail" -eq 0 ]]
