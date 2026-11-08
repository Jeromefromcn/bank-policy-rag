#!/usr/bin/env bash
# Behaviour tests for scripts/verify-corpus.sh. Uses self-made fixture files only.
set -uo pipefail

SCRIPT="$(cd "$(dirname "$0")/.." && pwd)/verify-corpus.sh"
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

sha() { sha256sum "$1" | cut -d' ' -f1; }

setup() { # creates $TMP/case/{raw,manifest.yaml} with two modules
  rm -rf "$TMP/case"; mkdir -p "$TMP/case/raw"
  printf 'alpha fixture\n' > "$TMP/case/raw/AA-1.pdf"
  printf 'beta fixture\n' > "$TMP/case/raw/BB-2.pdf"
  cat > "$TMP/case/manifest.yaml" <<YAML
# test manifest
modules:
  - docId: test-aa-1
    code: AA-1
    title: Alpha Module
    version: "V.1"
    revisionDate: 2020-01-01
    file: AA-1.pdf
    sha256: "${1:-$(sha "$TMP/case/raw/AA-1.pdf")}"
  - docId: test-bb-2
    code: BB-2
    title: Beta Module
    version: "V.2"
    revisionDate: 2021-02-02
    file: BB-2.pdf
    sha256: "${2:-$(sha "$TMP/case/raw/BB-2.pdf")}"
YAML
}

run() { out="$("$SCRIPT" --manifest "$TMP/case/manifest.yaml" --raw-dir "$TMP/case/raw" "$@" 2>&1)"; code=$?; }

setup; run
check "all files match -> exit 0" 0 "$code" "$out" "2/2 modules verified"

setup "$(printf '0%.0s' {1..64})"; run
check "hash mismatch -> exit 1" 1 "$code" "$out" "MISMATCH AA-1.pdf"

setup; rm "$TMP/case/raw/BB-2.pdf"; run
check "missing file -> exit 1" 1 "$code" "$out" "MISSING  BB-2.pdf"

setup "" "<hex>"; sed -i 's/sha256: ".*"/sha256: ""/' "$TMP/case/manifest.yaml"; run
check "empty hash in manifest -> exit 1" 1 "$code" "$out" "NO-HASH  AA-1.pdf"

setup; run --print
check "--print lists computed hashes" 0 "$code" "$out" "AA-1.pdf $(sha "$TMP/case/raw/AA-1.pdf")"

setup; rm "$TMP/case/raw/AA-1.pdf"; run --print
check "--print skips missing files" 0 "$code" "$out" "MISSING  AA-1.pdf"

out="$("$SCRIPT" --manifest "$TMP/nope.yaml" --raw-dir "$TMP" 2>&1)"; code=$?
check "missing manifest -> exit 2" 2 "$code" "$out" "manifest not found"

out="$("$SCRIPT" --bogus 2>&1)"; code=$?
check "unknown option -> exit 2" 2 "$code" "$out" "usage"

echo "$pass passed, $fail failed"
[[ "$fail" -eq 0 ]]
