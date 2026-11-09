# Phase 4 · Cross-encoder rerank

- **Date**: 2026-11-09
- **Spec**: `docs/specs/04-rerank-rewrite.md` (plan: `docs/specs/04-rerank-rewrite-plan.md`)
- **Purpose**: measure whether a cross-encoder reranker over the hybrid top 20 improves retrieval enough to pay for
  its latency, and whether its score is a better refusal signal than the bi-encoder cosine (`decisions/03`).

All numbers come from eval-runner output (`eval/runs/…`, not committed). Item ids and counts only: no source text
appears here.

## 1. Configuration

| Item | Value |
|---|---|
| Index | `rag_dev-chunks-nomic768-struct400-v1` (structural chunks, `nomic-embed-text`), corpus `9dd88979729b` |
| Retrieval | `HYBRID`; with rerank, the top 20 hybrid hits are scored and the top k returned |
| Passage cut | Each passage cut to 256 cl100k tokens for the reranker only |
| Reranker sidecar | `reranker/` (Python, `sentence-transformers==5.1.2`, `torch==2.9.1` CPU), base `python:3.12-slim@sha256:5e2bdb1b0d54…`, built image `sha256:d42142b5f942…` |
| Model B | `cross-encoder/ms-marco-MiniLM-L6-v2` @ `233902d25c440f23af6f7d6e94d2946bac0bee0a`, limits 2 CPUs / 1 GB |
| Model C | `BAAI/bge-reranker-v2-m3` @ `953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e`, limits 2 CPUs / **3 GB** (raised for this run only), app reranker timeout 600 s |
| Machine | aarch64, 4 CPUs, shared Elasticsearch and Ollama |
| Repetitions | Each item ran 3 times (`--repeat=3`); metrics come from the first repetition, latency from all three |

| Run | dev | test | Commit (clean tree) |
|---|---|---|---|
| A, no rerank | `20261109T152509Z` | `20261109T152525Z` | `8107c80` |
| B, MiniLM | `20261109T152819Z` | `20261109T152936Z` | `8107c80` |
| C, bge-reranker-v2-m3 | `20261109T164521Z` | `20261109T171341Z` | `af30b9c` |
| Rerank-score sweep (B, dev) | `20261109T153106Z-sweep` | – | `8107c80` |

`af30b9c` differs from `8107c80` only in the run script (reranker timeout passthrough for bge); the app and the
retrieval code are the same.

## 2. Parity check

`scripts/rerank-parity/run.sh minilm|bge`: 5 self-made queries × 4 passages, sidecar scores against
`sentence-transformers` `CrossEncoder` in a separate container.

| Model | Spearman (per query) | Top-1 agreement | Max abs logit difference |
|---|---|---|---|
| MiniLM | 1.000 on 5 of 5 | 5 of 5 | 0.0000 |
| bge-reranker-v2-m3 | 1.000 on 5 of 5 | 5 of 5 | 0.0000 |

Same weights and library on both sides, so this check confirms the HTTP path and the index mapping. The llama.cpp
route was dropped because MiniLM failed this check there (spec §2).

## 3. Retrieval metrics

### dev (n=26 answerable; 4 unanswerable skipped)

| Group | n | A R@5 / MRR / nDCG@10 | B (MiniLM) | C (bge) |
|---|---|---|---|---|
| all | 26 | 0.821 / 0.752 / 0.761 | 0.821 / 0.748 / 0.734 | 0.821 / 0.757 / 0.784 |
| factual | 10 | 0.800 / 0.696 / 0.728 | 0.800 / 0.675 / 0.706 | 0.800 / 0.747 / 0.787 |
| exact-term | 6 | 0.917 / 0.639 / 0.721 | 0.917 / 0.756 / 0.782 | 0.833 / 0.729 / 0.801 |
| multi-hop | 5 | 0.567 / 0.750 / 0.636 | 0.567 / 0.867 / 0.654 | 0.667 / 0.667 / 0.615 |
| restricted | 5 | 1.000 / 1.000 / 1.000 | 1.000 / 0.767 / 0.810 | 1.000 / 0.900 / 0.926 |

Recall@10, all: A 0.904, B 0.853, C 0.923.

### test (n=10 answerable; 2 unanswerable skipped)

| Group | n | A R@5 / MRR / nDCG@10 | B (MiniLM) | C (bge) |
|---|---|---|---|---|
| all | 10 | 0.900 / 0.717 / 0.750 | 0.800 / 0.756 / 0.764 | 0.950 / 0.783 / 0.812 |
| factual | 4 | 1.000 / 0.875 / 0.908 | 1.000 / 1.000 / 1.000 | 1.000 / 1.000 / 1.000 |
| exact-term | 2 | 0.750 / 0.417 / 0.469 | 0.750 / 0.225 / 0.325 | 0.750 / 0.625 / 0.522 |
| multi-hop | 2 | 0.750 / 0.417 / 0.465 | 0.250 / 0.556 / 0.496 | 1.000 / 0.292 / 0.536 |
| restricted | 2 | 1.000 / 1.000 / 1.000 | 1.000 / 1.000 / 1.000 | 1.000 / 1.000 / 1.000 |

Run A reproduces the Phase 2 hybrid figures on dev (0.821 / 0.752 / 0.761).

## 4. Latency (ms, server-side `retrieval + rerank`, all 3 repetitions)

| Run | dev n | dev p50 | dev p95 | test n | test p50 | test p95 |
|---|---|---|---|---|---|---|
| A, no rerank | 78 | 69 | 114 | 30 | 76 | 312 |
| B, MiniLM | 78 | 2094 | 2385 | 30 | 2179 | 2323 |
| C, bge | 78 | 57798 | 63463 | 30 | 58003 | 62251 |

The rerank stage alone: B dev p50 2024 / p95 2289; C dev p50 57724 / p95 63398. The retrieval stage stays at
68 to 74 ms p50 in all runs. The first repetition (cold) does not differ materially from the later ones for B or C.

## 5. Rerank-score refusal sweep (B, MiniLM, dev)

Signal: the best `sigmoid(logit)` among the reranked hits. dev sample sizes: 26 answerable, 4 unanswerable,
5 restricted-below-clearance. One item moves a rate by 20 to 25 points.

| Threshold | Unanswerable refused (n=4) | Restricted refused (n=5) | False refusal (n=26) | False refusal by category |
|---|---|---|---|---|
| 0.00 | 0% | 0% | 0% | – |
| 0.01 to 0.10 | 50% | 0% | 0% | – |
| 0.11 to 0.15 | 50% | 0% | 3.8% | exact-term 1 of 6 |
| 0.16 to 0.26 | 75% | 0% | 3.8% | exact-term 1 of 6 |
| 0.28 to 0.48 | 75% | 0% | 11.5% | exact-term 2 of 6, multi-hop 1 of 5 |
| 0.50 to 0.60 | 75% | 20% | 11.5% | exact-term 2 of 6, multi-hop 1 of 5 |
| 0.80 | 75% | 20% | 19.2% | exact-term 2 of 6, multi-hop 3 of 5 |
| 0.85 to 0.90 | 100% | 20% | 19.2% | exact-term 2 of 6, multi-hop 3 of 5 |
| 0.95 | 100% | 40% | 30.8% | adds factual 1 of 10, multi-hop 4 of 5 |

For comparison, the Phase 3 cosine gate (`decisions/03`) refused 25% of dev unanswerable items at 7.7% false refusal
(0.68) and 50% at 15.4% (0.70). C was not swept: B's gate is usable, and the plan sweeps C only otherwise.

## 6. Headline

On test (n=10), MiniLM rerank changed Recall@5 from **0.900 to 0.800** and the p95 of server `retrieval + rerank`
from **312 ms to 2323 ms**. bge-reranker-v2-m3 raised test Recall@5 to 0.950 at a p95 of 62251 ms.

## 7. Observations (from the numbers above)

- MiniLM leaves dev Recall@5 unchanged (0.821) and lowers test Recall@5 from 0.900 to 0.800 through the two multi-hop
  items: g024 (Recall@5 0.5 to 0.0, first match rank 3 to 9) and g027 (1.0 to 0.5). It raises MRR on dev exact-term (0.639 to 0.756) and multi-hop (0.750 to 0.867), and
  lowers it on dev restricted (1.000 to 0.767; g030 rank 1 to 3, g033 rank 1 to 2) and test exact-term
  (0.417 to 0.225; g016 rank 2 to 5).
- MiniLM lowers Recall@10 on dev (0.904 to 0.853): reordering the top 20 pushed some relevant hits that hybrid ranked
  in the top 10 out of it.
- bge is the only variant that improves every headline metric on both splits (dev nDCG@10 0.761 to 0.784; test
  Recall@5 0.900 to 0.950, MRR 0.717 to 0.783). Its gains come from g028 (dev, Recall@5 0.5 to 1.0) and g024 (test,
  0.5 to 1.0); it loses g019 (dev exact-term, 0.5 to 0.0).
- bge costs about 58 s per request on 2 CPUs, which is unusable for an interactive API on this machine. MiniLM costs
  about 2 s per request.
- The MiniLM score separates better than the cosine on dev: at 0.01 to 0.10 it refuses 2 of 4 unanswerable items with
  no false refusals, where the cosine gate needed 15.4% false refusal for the same 50%. Above 0.27 the false refusals
  grow in exact-term and multi-hop, as with the cosine gate. The gate refuses restricted-below-clearance items only at
  0.50 and above (1 of 5), so below that it leaves them to the model, as in Phase 3.
- Sample sizes are small (26 / 10 answerable, 4 / 2 unanswerable). One test item moves Recall@5 by 0.05 to 0.10,
  and every difference between A and B on test is one or two items.

## 8. Not run yet

- Generation with rerank (spec §7.3, plan Task 12) is optional and has not run.
