#!/usr/bin/env bash
# Builds vector-only CI fixtures from the Example Bank corpus (spec 05 §7.1). Needs Ollama; no SPM text.
set -euo pipefail
cd "$(dirname "$0")/.."

OLLAMA_URL="${OLLAMA_URL:-http://127.0.0.1:11434}"
export OLLAMA_URL
if ! curl -fsS "${OLLAMA_URL}/api/tags" >/dev/null 2>&1; then
  echo "error: Ollama is not reachable at ${OLLAMA_URL}" >&2
  exit 1
fi

RAG_EMBEDDING_MODEL="${RAG_EMBEDDING_MODEL:-nomic}" \
  ./gradlew :app:bootRun --args="fixture --out=eval/fixtures --chunker=structural"
