#!/usr/bin/env bash
# Runs the reranker parity check (docs/specs/04-rerank-rewrite.md §2) in a pinned, resource-limited Python
# container against the reranker on 127.0.0.1:18090. Downloads the reference model from Hugging Face.
#   scripts/rerank-parity/run.sh minilm|bge
set -euo pipefail
case "${1:-}" in
  minilm) model="cross-encoder/ms-marco-MiniLM-L6-v2" ;;
  bge) model="BAAI/bge-reranker-v2-m3" ;;
  *) echo "usage: $0 minilm|bge" >&2; exit 2 ;;
esac
here="$(cd "$(dirname "$0")" && pwd)"
# python:3.12-slim, multi-arch index digest
image="python:3.12-slim@sha256:5e2bdb1b0d542d5039cffb8181c65930093949194a65c365dfb1b0fe13a4fa5b"
docker run --rm --network host --cpus 2 --memory 4g \
  -v "$here:/parity:ro" -v rerank-parity-cache:/root/.cache \
  "$image" sh -c "pip install -q --root-user-action=ignore --index-url https://download.pytorch.org/whl/cpu torch==2.9.1 \
    && pip install -q --root-user-action=ignore sentence-transformers==5.1.2 pyyaml==6.0.3 scipy==1.16.3 \
    && python /parity/parity.py /parity/pairs.yaml $model http://127.0.0.1:18090"
