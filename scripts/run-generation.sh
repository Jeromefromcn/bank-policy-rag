#!/usr/bin/env bash
# Runs the Phase 3 evals (docs/specs/03-generation.md §3.2, §7): one app process on the structural nomic index,
# then one eval-runner call. It only reads indices; it never moves an alias.
#
#   scripts/run-generation.sh [--dry-run] sweep                    # refusal threshold sweep, dev only, no LLM tokens
#   scripts/run-generation.sh [--dry-run] generate [--split=dev]   # answers + judge (spends DeepSeek tokens)
#
# Needs ES_PASSWORD and RAG_KEY_DEV (from .env); `generate` also needs DEEPSEEK_API_KEY in the environment.
# RAG_ASK_MIN_SIMILARITY (the value from decisions/03) is passed through to the app when set.
# Throw-away keys for the internal and public callers are generated per run and never printed.
# Env: RAG_ENV_PREFIX (default rag_dev), GENERATION_PORT (default 18081).
set -euo pipefail

usage() { echo "usage: $0 [--dry-run] sweep | generate [--split=dev|test|all]" >&2; exit 2; }

dry=0
step=""
split="dev"
for arg in "$@"; do
  case "$arg" in
    --dry-run) dry=1 ;;
    sweep|generate) step="$arg" ;;
    --split=dev|--split=test|--split=all) split="${arg#--split=}" ;;
    *) usage ;;
  esac
done
[[ -n "$step" ]] || usage

cd "$(cd "$(dirname "$0")/.." && pwd)"

if [[ -n "$(git status --porcelain)" ]]; then
  echo "error: working tree is dirty; commit first so every report records a real commit" >&2
  exit 1
fi

prefix="${RAG_ENV_PREFIX:-rag_dev}"
port="${GENERATION_PORT:-18081}"
management_port="${MANAGEMENT_PORT:-8081}"
chunks="$prefix-chunks-nomic768-struct400-v1"
sections="$prefix-sections-struct400-v1"
out="eval/runs"

if [[ "$dry" -eq 0 ]]; then
  if [[ -f .env ]]; then set -a; . ./.env; set +a; fi
  mkdir -p "$out"
  : "${ES_PASSWORD:?ES_PASSWORD is required (see .env.example)}"
  : "${RAG_KEY_DEV:?RAG_KEY_DEV is required (see .env.example)}"
  if [[ "$step" == "generate" ]]; then : "${DEEPSEEK_API_KEY:?DEEPSEEK_API_KEY is required for generate}"; fi
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

# Env-defined list entries replace the YAML list, so all three callers (0 = dev, 1 = internal, 2 = public) are set here.
key_vars="RAG_SECURITY_APIKEYS_0_KEY RAG_SECURITY_APIKEYS_0_PRINCIPAL RAG_SECURITY_APIKEYS_0_CLEARANCE RAG_SECURITY_APIKEYS_1_KEY RAG_SECURITY_APIKEYS_1_PRINCIPAL RAG_SECURITY_APIKEYS_1_CLEARANCE RAG_SECURITY_APIKEYS_2_KEY RAG_SECURITY_APIKEYS_2_PRINCIPAL RAG_SECURITY_APIKEYS_2_CLEARANCE"
echo "+ START nomic: RAG_ENV_PREFIX=$prefix RAG_EMBEDDING_MODEL=nomic RAG_CHUNKS_INDEX=$chunks RAG_SECTIONS_INDEX=$sections RAG_ASK_MIN_SIMILARITY=${RAG_ASK_MIN_SIMILARITY:-0.0} MANAGEMENT_PORT=$management_port $key_vars java -Xmx1g -jar $jar --server.port=$port"
if [[ "$dry" -eq 0 ]]; then
  RAG_ENV_PREFIX="$prefix" RAG_EMBEDDING_MODEL=nomic RAG_CHUNKS_INDEX="$chunks" RAG_SECTIONS_INDEX="$sections" \
  RAG_ASK_MIN_SIMILARITY="${RAG_ASK_MIN_SIMILARITY:-0.0}" \
  RAG_SECURITY_APIKEYS_0_KEY="$RAG_KEY_DEV" RAG_SECURITY_APIKEYS_0_PRINCIPAL=dev RAG_SECURITY_APIKEYS_0_CLEARANCE=restricted \
  RAG_SECURITY_APIKEYS_1_KEY="$RAG_KEY_INTERNAL" RAG_SECURITY_APIKEYS_1_PRINCIPAL=eval-internal RAG_SECURITY_APIKEYS_1_CLEARANCE=internal \
  RAG_SECURITY_APIKEYS_2_KEY="$RAG_KEY_PUBLIC" RAG_SECURITY_APIKEYS_2_PRINCIPAL=eval-public RAG_SECURITY_APIKEYS_2_CLEARANCE=public \
    java -Xmx1g -jar "$jar" --server.port="$port" > "$out/generation-$step.app.log" 2>&1 &
  app_pid=$!
  ready=0
  for _ in $(seq 120); do
    if curl -sf "http://127.0.0.1:$management_port/actuator/health" >/dev/null 2>&1; then ready=1; break; fi
    kill -0 "$app_pid" 2>/dev/null || { echo "error: app exited; see $out/generation-$step.app.log" >&2; exit 1; }
    sleep 2
  done
  [[ "$ready" -eq 1 ]] || { echo "error: app not healthy after 240s; see $out/generation-$step.app.log" >&2; exit 1; }
fi

if [[ "$step" == "sweep" ]]; then
  args="--base-url=http://127.0.0.1:$port --mode=HYBRID --sweep=true --out=$out"
else
  args="--base-url=http://127.0.0.1:$port --mode=HYBRID --generation=true --split=$split --out=$out"
fi
echo "+ RUN $step: ./gradlew :eval-runner:run --args=\"$args\""
if [[ "$dry" -eq 0 ]]; then
  ./gradlew -q :eval-runner:run --args="$args"
  echo "done: $out"
fi
