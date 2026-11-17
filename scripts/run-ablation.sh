#!/usr/bin/env bash
# Runs the Phase 2 ablations (docs/specs/02-retrieval.md §7): one app process per physical index,
# one eval-runner call per mode. It only reads indices; it never moves an alias.
#
#   scripts/run-ablation.sh [--dry-run] retrieval   # {fixed, structural} x {VECTOR, BM25, HYBRID}, nomic
#   scripts/run-ablation.sh [--dry-run] embedding   # structural + HYBRID, {nomic, bge-small, bge-m3}
#
# The indices must already exist (ingest builds them). Needs ES_PASSWORD and RAG_KEY_DEV (from .env).
# Env: RAG_ENV_PREFIX (default rag_dev), ABLATION_PORT (default 18080).
set -euo pipefail

usage() { echo "usage: $0 [--dry-run] retrieval|embedding" >&2; exit 2; }

dry=0
table=""
for arg in "$@"; do
  case "$arg" in
    --dry-run) dry=1 ;;
    retrieval|embedding) table="$arg" ;;
    *) usage ;;
  esac
done
[[ -n "$table" ]] || usage

cd "$(cd "$(dirname "$0")/.." && pwd)"

if [[ -n "$(git status --porcelain)" ]]; then
  echo "error: working tree is dirty; commit first so every report records a real commit" >&2
  exit 1
fi

prefix="${RAG_ENV_PREFIX:-rag_dev}"
port="${ABLATION_PORT:-18080}"
management_port="${MANAGEMENT_PORT:-8081}"
commit="$(git rev-parse HEAD)"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
out="eval/runs/ablation-$table-$stamp"

# name|embedding model key|chunks index|sections index (blank for fixed chunks)|modes
case "$table" in
  retrieval)
    groups=(
      "fixed-nomic|nomic|$prefix-chunks-nomic768-fixed512-v1||VECTOR BM25 HYBRID"
      "structural-nomic|nomic|$prefix-chunks-nomic768-struct400-v1|$prefix-sections-struct400-v1|VECTOR BM25 HYBRID"
    ) ;;
  embedding)
    groups=(
      "structural-nomic|nomic|$prefix-chunks-nomic768-struct400-v1|$prefix-sections-struct400-v1|HYBRID"
      "structural-bge-small|bge-small|$prefix-chunks-bgesmall384-struct400-v1|$prefix-sections-struct400-v1|HYBRID"
      "structural-bge-m3|bge-m3|$prefix-chunks-bgem3-1024-struct400-v1|$prefix-sections-struct400-v1|HYBRID"
    ) ;;
esac

if [[ "$dry" -eq 0 ]]; then
  if [[ -f .env ]]; then set -a; . ./.env; set +a; fi
  : "${ES_PASSWORD:?ES_PASSWORD is required (see .env.example)}"
  : "${RAG_KEY_DEV:?RAG_KEY_DEV is required (see .env.example)}"
  mkdir -p "$out"
  printf 'run\tmode\tchunks_index\tembedding_model\tcommit\n' > "$out/index.txt"
  ./gradlew -q :app:bootJar
  jar="$(ls app/build/libs/*.jar | grep -v -- '-plain' | head -1)"
else
  jar="app/build/libs/app.jar"
  echo "dry run: nothing is started; output would go to $out"
fi

app_pid=""
stop_app() {
  if [[ -n "$app_pid" ]]; then kill "$app_pid" 2>/dev/null || true; wait "$app_pid" 2>/dev/null || true; app_pid=""; fi
}
trap stop_app EXIT

for group in "${groups[@]}"; do
  IFS='|' read -r name model chunks sections modes <<<"$group"
  envs="RAG_ENV_PREFIX=$prefix RAG_EMBEDDING_MODEL=$model RAG_CHUNKS_INDEX=$chunks RAG_SECTIONS_INDEX=$sections"
  echo "+ START $name: $envs java -Xmx1g -jar $jar --server.port=$port"
  if [[ "$dry" -eq 0 ]]; then
    env $envs java -Xmx1g -jar "$jar" --server.port="$port" > "$out/$name.app.log" 2>&1 &
    app_pid=$!
    for _ in $(seq 120); do
      curl -sf "http://127.0.0.1:$management_port/actuator/health" >/dev/null 2>&1 && break
      kill -0 "$app_pid" 2>/dev/null || { echo "error: app for $name exited; see $out/$name.app.log" >&2; exit 1; }
      sleep 2
    done
  fi
  for mode in $modes; do
    args="--base-url=http://127.0.0.1:$port --mode=$mode --split=all --out=$out/$name-$mode"
    echo "+ RUN $name: ./gradlew :eval-runner:run --args=\"$args\""
    if [[ "$dry" -eq 0 ]]; then
      ./gradlew -q :eval-runner:run --args="$args"
      printf '%s\t%s\t%s\t%s\t%s\n' "$name" "$mode" "$chunks" "$model" "$commit" >> "$out/index.txt"
    fi
  done
  stop_app
done

[[ "$dry" -eq 1 ]] || echo "done: $out"
