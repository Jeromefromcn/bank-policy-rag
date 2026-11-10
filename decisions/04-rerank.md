# 04 · Rerank

- **Status**: Accepted
- **Date**: 2026-11-10
- **Provenance**: drafted by Claude from real runner output (`eval/runs/20261109T152509Z`, `…T152525Z`, `…T152819Z`,
  `…T152936Z`, `…T164521Z`, `…T171341Z`, `…T153106Z-sweep`), summarised in `evidence/phase4-rerank.md`. The author
  accepted Claude's recommendation on 2026-11-10 and asked Claude to commit it.

## Context

Phase 4 adds an optional cross-encoder rerank over the hybrid top 20 (`"rerank": true` on `/v1/search` and `/v1/ask`),
served by a Python sidecar on 2 CPUs, and a separate refusal gate `rag.ask.min-rerank-probability` that applies only
to rerank requests. Three choices are open: whether rerank becomes the default, which model, and the gate value.

Runs: A no rerank, B `ms-marco-MiniLM-L6-v2`, C `bge-reranker-v2-m3`; dev n=26 and test n=10 answerable; latency from
3 repetitions per item. One test item moves Recall@5 by 0.05 to 0.10.

## Results (selected)

| | A, no rerank | B, MiniLM | C, bge |
|---|---|---|---|
| dev Recall@5 / MRR / nDCG@10 | 0.821 / 0.752 / 0.761 | 0.821 / 0.748 / 0.734 | 0.821 / 0.757 / 0.784 |
| test Recall@5 / MRR / nDCG@10 | 0.900 / 0.717 / 0.750 | 0.800 / 0.756 / 0.764 | 0.950 / 0.783 / 0.812 |
| test p95, server retrieval + rerank | 312 ms | 2323 ms | 62251 ms |
| Memory limit | – | 1 GB | 3 GB |

MiniLM rerank-score sweep (dev): at 0.01 to 0.10 the gate refuses 2 of 4 unanswerable items with 0 of 26 false
refusals; at 0.16 to 0.26, 3 of 4 with 1 of 26 (exact-term); at 0.85, 4 of 4 with 5 of 26. The Phase 3 cosine gate
needed 15.4% false refusal to refuse 2 of 4.

## Options

**A. Rerank off by default, MiniLM available on request (`rerank: true`).** Default latency and metrics stay as in
Phase 3. Callers who want the reordering pay about 2 s.

**B. MiniLM rerank on by default.** No retrieval gain on this sample (dev Recall@5 equal, test 0.900 to 0.800, two
multi-hop items worse), for about 2 s more per request.

**C. bge rerank on by default.** Best metrics on both splits (test Recall@5 0.950), but about 58 s per request on
2 CPUs and 3 GB of memory. Not usable on this machine; would need a GPU or a much larger CPU budget.

**Gate value (applies only to `rerank: true`).**
- **0.0 (off).** Refusals stay with the model's `NO_ANSWER`, which refused every unanswerable item in Phase 3.
- **0.05.** The middle of the 0.01 to 0.10 band where dev shows no false refusals; saves a model call on half of the
  dev unanswerable items. Rests on 4 unanswerable items.
- **About 0.20.** Refuses 3 of 4 dev unanswerable items at 3.8% false refusal (one exact-term item).

## Claude's recommendation (the author decides)

**Option A with MiniLM as the sidecar's default model, and the gate at 0.05.** The measured retrieval gain from rerank
on this corpus is nil for MiniLM and real but unaffordable for bge, so rerank should not be the default. The MiniLM
score is still the better refusal signal (2 of 4 unanswerable refused at no false refusal, against 15.4% for the
cosine), so a low gate costs nothing measurable when a caller opts in. Revisit if the reranker gets a GPU, or if the
golden set grows enough to show a MiniLM gain beyond one or two items.

## Decision

Option A with the gate at 0.05.

- **Rerank stays off by default.** Callers opt in per request with `"rerank": true`. The sidecar's default model is
  `cross-encoder/ms-marco-MiniLM-L6-v2` @ `233902d2`.
- **`rag.ask.min-rerank-probability = 0.05`.** It applies only to `rerank: true` requests on `/v1/ask`; the similarity
  gate (`decisions/03`) stays off.
- **Reason.** MiniLM gives no measured retrieval gain on this corpus (dev Recall@5 0.821 both ways, test 0.900 to 0.800)
  for about 2 s per request. bge-reranker-v2-m3 gains (test Recall@5 0.950) but costs about 58 s per request on 2 CPUs.
  The MiniLM score is still a better refusal signal than the cosine: at 0.05 it refuses 2 of 4 dev unanswerable items
  with 0 of 26 false refusals, where the cosine needed 15.4% false refusal for the same.
- **Accepted trade-off.** The gate value rests on 4 dev unanswerable items; it was not tuned on `test`.
- **Revisit when.** The reranker gets a GPU or a larger CPU budget (bge becomes affordable), or the golden set grows
  enough to show a MiniLM gain beyond one or two items.
