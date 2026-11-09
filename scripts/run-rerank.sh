#!/usr/bin/env bash
# Runs the Phase 4 rerank evals (docs/specs/04-rerank-rewrite.md §5, §7.1): one app process on the structural nomic
# index, then one eval-runner call. Retrieval only: no LLM tokens. It only reads indices; it never moves an alias.
#
#   scripts/run-rerank.sh [--dry-run] none|minilm|bge [--split=dev|test|all] [--sweep]
#
# none   = no rerank (run A); minilm / bge = rerank with that model (runs B / C). The reranker sidecar
# (`docker compose --profile rerank up -d reranker`) must already serve the matching model; the script checks.
# Retrieval runs repeat every item 3 times for latency; --sweep runs the rerank-score threshold sweep on dev instead.
# Needs ES_PASSWORD and RAG_KEY_DEV (from .env). Throw-away keys for the internal and public callers are generated per
# run and never printed. Env: RAG_ENV_PREFIX (default rag_dev), RERANK_PORT (default 18082),
# RERANKER_URL (default http://127.0.0.1:18090).
set -euo pipefail

usage() { echo "usage: $0 [--dry-run] none|minilm|bge [--split=dev|test|all] [--sweep]" >&2; exit 2; }

dry=0
variant=""
split="dev"
sweep=0
for arg in "$@"; do
  case "$arg" in
    --dry-run) dry=1 ;;
    none|minilm|bge) variant="$arg" ;;
    --split=dev|--split=test|--split=all) split="${arg#--split=}" ;;
    --sweep) sweep=1 ;;
    *) usage ;;
  esac
done
[[ -n "$variant" ]] || usage
[[ "$sweep" -eq 0 || "$variant" != none ]] || { echo "error: --sweep needs a reranker (minilm or bge)" >&2; exit 2; }

case "$variant" in
  minilm) model="cross-encoder/ms-marco-MiniLM-L6-v2" ;;
  bge) model="BAAI/bge-reranker-v2-m3" ;;
  none) model="" ;;
esac

cd "$(cd "$(dirname "$0")/.." && pwd)"

if [[ -n "$(git status --porcelain)" ]]; then
  echo "error: working tree is dirty; commit first so every report records a real commit" >&2
  exit 1
fi

prefix="${RAG_ENV_PREFIX:-rag_dev}"
port="${RERANK_PORT:-18082}"
reranker_url="${RERANKER_URL:-http://127.0.0.1:18090}"
chunks="$prefix-chunks-nomic768-struct400-v1"
sections="$prefix-sections-struct400-v1"
out="eval/runs"
log="$out/rerank-$variant.app.log"

if [[ -n "$model" && "$dry" -eq 0 ]]; then
  served="$(curl -sf "$reranker_url/health" | python3 -c 'import json,sys; print(json.load(sys.stdin)["model"])' || true)"
  if [[ "$served" != "$model" ]]; then
    echo "error: reranker at $reranker_url serves '${served:-nothing}', expected '$model'" >&2
    exit 1
  fi
fi

if [[ "$dry" -eq 0 ]]; then
  if [[ -f .env ]]; then set -a; . ./.env; set +a; fi
  mkdir -p "$out"
  : "${ES_PASSWORD:?ES_PASSWORD is required (see .env.example)}"
  : "${RAG_KEY_DEV:?RAG_KEY_DEV is required (see .env.example)}"
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
echo "+ START nomic: RAG_ENV_PREFIX=$prefix RAG_EMBEDDING_MODEL=nomic RAG_CHUNKS_INDEX=$chunks RAG_SECTIONS_INDEX=$sections RERANKER_URL=$reranker_url $key_vars java -Xmx1g -jar $jar --server.port=$port"
if [[ "$dry" -eq 0 ]]; then
  RAG_ENV_PREFIX="$prefix" RAG_EMBEDDING_MODEL=nomic RAG_CHUNKS_INDEX="$chunks" RAG_SECTIONS_INDEX="$sections" \
  RERANKER_URL="$reranker_url" \
  RAG_SECURITY_APIKEYS_0_KEY="$RAG_KEY_DEV" RAG_SECURITY_APIKEYS_0_PRINCIPAL=dev RAG_SECURITY_APIKEYS_0_CLEARANCE=restricted \
  RAG_SECURITY_APIKEYS_1_KEY="$RAG_KEY_INTERNAL" RAG_SECURITY_APIKEYS_1_PRINCIPAL=eval-internal RAG_SECURITY_APIKEYS_1_CLEARANCE=internal \
  RAG_SECURITY_APIKEYS_2_KEY="$RAG_KEY_PUBLIC" RAG_SECURITY_APIKEYS_2_PRINCIPAL=eval-public RAG_SECURITY_APIKEYS_2_CLEARANCE=public \
    java -Xmx1g -jar "$jar" --server.port="$port" > "$log" 2>&1 &
  app_pid=$!
  ready=0
  for _ in $(seq 120); do
    if curl -sf "http://127.0.0.1:$port/actuator/health" >/dev/null 2>&1; then ready=1; break; fi
    kill -0 "$app_pid" 2>/dev/null || { echo "error: app exited; see $log" >&2; exit 1; }
    sleep 2
  done
  [[ "$ready" -eq 1 ]] || { echo "error: app not healthy after 240s; see $log" >&2; exit 1; }
fi

args="--base-url=http://127.0.0.1:$port --mode=HYBRID --out=$out"
if [[ -n "$model" ]]; then args="$args --rerank=true --reranker-model=$model"; fi
if [[ "$sweep" -eq 1 ]]; then
  args="$args --sweep=true"
else
  args="$args --split=$split --repeat=3"
fi
echo "+ RUN $variant: ./gradlew :eval-runner:run --args=\"$args\""
if [[ "$dry" -eq 0 ]]; then
  ./gradlew -q :eval-runner:run --args="$args"
  echo "done: $out"
fi
