#!/usr/bin/env bash
# Runs the Phase 4 multi-turn eval (docs/specs/04-rerank-rewrite.md §7.2): one app process on the structural nomic
# index, then one eval-runner call. Costs LLM tokens (turn 1 answer, rewrite, follow-up answer per item), so run it
# once per split. It only reads indices; it never moves an alias. No rerank.
#
#   scripts/run-multi-turn.sh [--dry-run] [--split=dev|test]
#
# Needs ES_PASSWORD and RAG_KEY_DEV (from .env) and DEEPSEEK_API_KEY. Throw-away keys for the internal and public
# callers are generated per run and never printed. Env: RAG_ENV_PREFIX (default rag_dev), MULTI_TURN_PORT
# (default 18083), MULTI_TURN_FILE (default eval/multi-turn.yaml).
set -euo pipefail

usage() { echo "usage: $0 [--dry-run] [--split=dev|test]" >&2; exit 2; }

dry=0
split="dev"
for arg in "$@"; do
  case "$arg" in
    --dry-run) dry=1 ;;
    --split=dev|--split=test) split="${arg#--split=}" ;;
    *) usage ;;
  esac
done

cd "$(cd "$(dirname "$0")/.." && pwd)"

if [[ -n "$(git status --porcelain)" ]]; then
  echo "error: working tree is dirty; commit first so every report records a real commit" >&2
  exit 1
fi

set_file="${MULTI_TURN_FILE:-eval/multi-turn.yaml}"
[[ -f "$set_file" ]] || { echo "error: multi-turn set $set_file not found" >&2; exit 1; }

prefix="${RAG_ENV_PREFIX:-rag_dev}"
port="${MULTI_TURN_PORT:-18083}"
management_port="${MANAGEMENT_PORT:-8081}"
chunks="$prefix-chunks-nomic768-struct400-v1"
sections="$prefix-sections-struct400-v1"
out="eval/runs"
log="$out/multi-turn.app.log"

if [[ "$dry" -eq 0 ]]; then
  if [[ -f .env ]]; then set -a; . ./.env; set +a; fi
  mkdir -p "$out"
  : "${ES_PASSWORD:?ES_PASSWORD is required (see .env.example)}"
  : "${RAG_KEY_DEV:?RAG_KEY_DEV is required (see .env.example)}"
  : "${DEEPSEEK_API_KEY:?DEEPSEEK_API_KEY is required}"
  ./gradlew -q :app:bootJar
  jar="$(ls app/build/libs/*.jar | grep -v -- '-plain' | head -1)"
  export RAG_KEY_INTERNAL="eval-internal-$(openssl rand -hex 16)"
  export RAG_KEY_PUBLIC="eval-public-$(openssl rand -hex 16)"
else
  jar="app/build/libs/app.jar"
  echo "dry run: nothing is started"
fi

app_pid=""
stop_app() {
  if [[ -n "$app_pid" ]]; then kill "$app_pid" 2>/dev/null || true; wait "$app_pid" 2>/dev/null || true; app_pid=""; fi
}
trap stop_app EXIT

key_vars="RAG_SECURITY_APIKEYS_{0,1,2}_{KEY,PRINCIPAL,CLEARANCE}"
echo "+ START nomic: RAG_ENV_PREFIX=$prefix RAG_EMBEDDING_MODEL=nomic RAG_CHUNKS_INDEX=$chunks RAG_SECTIONS_INDEX=$sections $key_vars java -Xmx1g -jar $jar --server.port=$port"
if [[ "$dry" -eq 0 ]]; then
  RAG_ENV_PREFIX="$prefix" RAG_EMBEDDING_MODEL=nomic RAG_CHUNKS_INDEX="$chunks" RAG_SECTIONS_INDEX="$sections" \
  RAG_SECURITY_APIKEYS_0_KEY="$RAG_KEY_DEV" RAG_SECURITY_APIKEYS_0_PRINCIPAL=dev RAG_SECURITY_APIKEYS_0_CLEARANCE=restricted \
  RAG_SECURITY_APIKEYS_1_KEY="$RAG_KEY_INTERNAL" RAG_SECURITY_APIKEYS_1_PRINCIPAL=eval-internal RAG_SECURITY_APIKEYS_1_CLEARANCE=internal \
  RAG_SECURITY_APIKEYS_2_KEY="$RAG_KEY_PUBLIC" RAG_SECURITY_APIKEYS_2_PRINCIPAL=eval-public RAG_SECURITY_APIKEYS_2_CLEARANCE=public \
    java -Xmx1g -jar "$jar" --server.port="$port" > "$log" 2>&1 &
  app_pid=$!
  ready=0
  for _ in $(seq 120); do
    if curl -sf "http://127.0.0.1:$management_port/actuator/health" >/dev/null 2>&1; then ready=1; break; fi
    kill -0 "$app_pid" 2>/dev/null || { echo "error: app exited; see $log" >&2; exit 1; }
    sleep 2
  done
  [[ "$ready" -eq 1 ]] || { echo "error: app not healthy after 240s; see $log" >&2; exit 1; }
fi

args="--base-url=http://127.0.0.1:$port --mode=HYBRID --out=$out --multi-turn=true --multi-turn-file=$set_file --split=$split"
echo "+ RUN multi-turn: ./gradlew :eval-runner:run --args=\"$args\""
if [[ "$dry" -eq 0 ]]; then
  ./gradlew -q :eval-runner:run --args="$args"
  echo "done: $out"
fi
