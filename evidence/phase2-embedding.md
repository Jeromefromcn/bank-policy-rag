# Phase 2 · Embedding model ablation

- **Date**: 2026-11-08
- **Spec**: `docs/specs/02-retrieval.md` (§5, §7 embedding ablation)
- **Purpose**: `HYBRID` + structural chunking (`structural-v1-400-cl100k`), three embedding models. Only the model varies: all three index the same 340 chunks.

All numbers come from eval-runner output (`eval/runs/ablation-embedding-20261108T145008Z/`, not committed) and from the ingest logs of the same day.

## 1. Models

| Key | Ollama model | Dims | Notes |
|---|---|---|---|
| nomic | `nomic-embed-text` (F16) | 768 | default; task prefixes on both sides |
| bge-small | `hf.co/CompendiumLabs/bge-small-en-v1.5-gguf:Q8_0` | 384 | community GGUF, **8-bit quantised** (the others are F16); query instruction prefix only |
| bge-m3 | `bge-m3` (F16) | 1024 | no prefixes |

The bge-small weights are quantised and the other two are not, so any bge-small result mixes model size with quantisation.

Setup: commit 918e5b3 (clean: True), `corpusVersion` 9dd88979729b, aarch64 4 CPUs, shared Elasticsearch and Ollama, `num_thread: 3`, embed batch 16, empty embedding cache for each model, `topK = 10`, caller `restricted`.

## 2. Ingest cost (340 chunks, 46,487 cl100k tokens)

| Model | Embed step (s) | Index step (ms) |
|---|---|---|
| nomic | 74.0 | 1021 |
| bge-small | 18.2 | 852 |
| bge-m3 | 217.8 | 839 |

Embed-step wall time from the ingest logs. The index-size column of the spec is omitted: the `rag_dev` user cannot read `_cat`/index stats (spec 01 §2: its only cluster privilege is `GET /`).

## 3. Retrieval quality and query latency

| Model | Split | Group | n | Recall@5 | Recall@10 | MRR | nDCG@10 |
|---|---|---|---|---|---|---|---|
| nomic | dev | all | 26 | 0.821 | 0.904 | 0.752 | 0.761 |
| nomic | dev | exact-term | 6 | 0.917 | 1.000 | 0.639 | 0.721 |
| nomic | test | all | 10 | 0.900 | 0.900 | 0.717 | 0.750 |
| nomic | test | exact-term | 2 | 0.750 | 0.750 | 0.417 | 0.469 |
| bge-small | dev | all | 26 | 0.840 | 0.872 | 0.808 | 0.789 |
| bge-small | dev | exact-term | 6 | 1.000 | 1.000 | 0.917 | 0.914 |
| bge-small | test | all | 10 | 0.900 | 0.950 | 0.703 | 0.761 |
| bge-small | test | exact-term | 2 | 0.750 | 0.750 | 0.417 | 0.469 |
| bge-m3 | dev | all | 26 | 0.821 | 0.872 | 0.790 | 0.766 |
| bge-m3 | dev | exact-term | 6 | 0.917 | 1.000 | 0.681 | 0.743 |
| bge-m3 | test | all | 10 | 0.900 | 0.900 | 0.683 | 0.733 |
| bge-m3 | test | exact-term | 2 | 0.750 | 0.750 | 0.375 | 0.447 |

| Model | `/v1/search` p50 / p95 (ms) |
|---|---|
| nomic | 97 / 145 |
| bge-small | 65 / 118 |
| bge-m3 | 143 / 248 |

Latency includes the query-embedding call to the shared Ollama and the BM25 leg; it is noisy.

## 4. Observations

Written only from the tables above. `dev` n = 26 (`exact-term` n = 6); `test` n = 10 (`exact-term` n = 2). A single run per model.

1. **The three models are within noise of each other on overall retrieval.** `dev` Recall@5 is 0.821 / 0.840 / 0.821 (nomic / bge-small / bge-m3), a spread of 0.019, less than one item's worth at n = 26. `test` Recall@5 is 0.900 for all three. MRR on `dev` is 0.752 / 0.808 / 0.790 and nDCG@10 0.761 / 0.789 / 0.766; bge-small is highest on both, bge-m3 is not above it on any headline metric in `dev`. Recall@10 on `dev` is the only metric where nomic leads (0.904 vs 0.872 for both bge models).
2. **`exact-term`:** bge-small reaches Recall@5 1.000 and MRR 0.917 on `dev` against 0.917 / 0.639 (nomic) and 0.917 / 0.681 (bge-m3). On `test` (n = 2) nomic and bge-small are identical (0.750 / 0.417) and bge-m3 is slightly lower in MRR (0.375). The `BM25` leg is shared by all three, which probably limits how far the embedding model can matter under `HYBRID`; this run does not isolate that.
3. **Cost differs much more than quality.** Embed step for 340 chunks: bge-small 18.2 s, nomic 74.0 s, bge-m3 217.8 s, so bge-m3 took 2.9× nomic's time and 12× bge-small's. Query latency p50 follows the same order (65 / 97 / 143 ms).
4. **Answer to the Phase 2 question.** On this corpus and golden set the larger model gives no measurable gain over the small ones, and the smallest model scored at least as well on most `dev` metrics at about a quarter of nomic's ingest time. This does not show that small models are better in general: the corpus is 16 documents, the sets are small, and the effect sizes are inside what one item can move.
5. **Caveats.** bge-small is a quantised (Q8_0) community GGUF while the others are F16, so its result mixes size and quantisation. All three share the 400-token structural chunks, which suit bge-small's 512-token limit and were not tuned for any model. Latency and embed times are from a shared host with `num_thread: 3`.
