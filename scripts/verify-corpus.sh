#!/usr/bin/env bash
# Verify the SPM PDFs in corpus/raw/ against the sha256 values in corpus/manifest.yaml.
#
# Usage: scripts/verify-corpus.sh [--manifest PATH] [--raw-dir DIR] [--print]
#   --print  list the computed sha256 of each present file (to fill in the manifest)
#
# Exit codes: 0 all verified (or --print done), 1 verification failed, 2 usage error.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MANIFEST="$ROOT/corpus/manifest.yaml"
RAW_DIR="$ROOT/corpus/raw"
PRINT=0

usage() { echo "usage: $0 [--manifest PATH] [--raw-dir DIR] [--print]" >&2; exit 2; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --manifest) [[ $# -ge 2 ]] || usage; MANIFEST="$2"; shift 2 ;;
    --raw-dir)  [[ $# -ge 2 ]] || usage; RAW_DIR="$2"; shift 2 ;;
    --print)    PRINT=1; shift ;;
    *) usage ;;
  esac
done

[[ -f "$MANIFEST" ]] || { echo "error: manifest not found: $MANIFEST" >&2; exit 2; }

# Emit "file<TAB>sha256" per module. The manifest is a flat list of maps under `modules:`;
# within each `- docId:` entry, `file` and `sha256` are read as scalar values.
entries="$(awk '
  function val(line) { sub(/^[^:]*:[ \t]*/, "", line); sub(/[ \t]+#.*$/, "", line);
                       gsub(/^"|"$/, "", line); return line }
  function flush() { if (inmod) printf "%s\t%s\n", file, hash; file = ""; hash = "" }
  /^[ \t]*-[ \t]+docId:/   { flush(); inmod = 1; next }
  inmod && /^[ \t]+file:/   { file = val($0) }
  inmod && /^[ \t]+sha256:/ { hash = val($0) }
  END { flush() }
' "$MANIFEST")"

total=0
ok=0
while IFS=$'\t' read -r file want; do
  [[ -n "$file" ]] || continue
  total=$((total + 1))
  path="$RAW_DIR/$file"
  if [[ ! -f "$path" ]]; then
    echo "MISSING  $file"
    continue
  fi
  got="$(sha256sum "$path" | cut -d' ' -f1)"
  if [[ "$PRINT" -eq 1 ]]; then
    echo "$file $got"
  elif [[ ! "$want" =~ ^[0-9a-f]{64}$ ]]; then
    echo "NO-HASH  $file (manifest has no valid sha256; run with --print)"
  elif [[ "$got" != "$want" ]]; then
    echo "MISMATCH $file (expected $want, got $got)"
  else
    echo "OK       $file"
    ok=$((ok + 1))
  fi
done <<< "$entries"

[[ "$PRINT" -eq 1 ]] && exit 0
echo "$ok/$total modules verified"
[[ "$total" -gt 0 && "$ok" -eq "$total" ]]
