# Phase 2 · Chunking and retrieval-mode ablation

- **Date**: 2026-11-08
- **Spec**: `docs/specs/02-retrieval.md` (§7 retrieval ablation)
- **Purpose**: six runs, `{fixed, structural}` chunking × `{VECTOR, BM25, HYBRID}`, all with `nomic-embed-text`.

All numbers come from eval-runner output (`eval/runs/ablation-retrieval-20261108T144916Z/`, not committed) and from the ingest logs of the same day. Per-item rows show ids and ranks only, never chunk text.

## 1. Configuration

| Item | Value |
|---|---|
| Chunkers | `fixed-512-64-cl100k` (Phase 1) and `structural-v1-400-cl100k` (min 60 tokens, max 400, no overlap) |
| Embedding | `ollama/nomic-embed-text`, 768 dims, `num_thread: 3` |
| Indices | `rag_dev-chunks-nomic768-fixed512-v1`, `rag_dev-chunks-nomic768-struct400-v1` (+ `rag_dev-sections-struct400-v1`, 79 parents) |
| Retrieval | eval `topK = 10`; `HYBRID` = RRF (k = 60) over two legs of 50; BM25 over `content` + `docTitle`; kNN `num_candidates: 100`; classification pre-filter on both legs |
| Caller | dev key, `restricted` clearance |
| Scoring | spec 01 §3.4 (unchanged from the Phase 1 baseline) |
| Versions | `corpusVersion` 9dd88979729b, git commit 918e5b3 (clean: True) |
| Machine | aarch64, 4 CPUs; shared Elasticsearch 9.5.5 and Ollama 0.40.15 on the same host |

**Expectation stated before the runs** (spec 02 §8): hybrid retrieval clearly wins on `exact-term`; structural chunking is at least as good as fixed on Recall@5.

## 2. Chunking

| Chunker | Chunks | Tokens (cl100k) | Chunk tokens min / median / max |
|---|---|---|---|
| fixed-512-64 | 116 | 55,111 | not recorded in Phase 1 |
| structural-v1-400 | 340 | 46,487 | 9 / 108 / 400 |

Structural chunking indexes less text than fixed chunking (46,487 vs 55,111 tokens): it drops text before the first section marker (front matter, contents lists) and has no overlap. The Phase 1 chunks-per-document breakdown is in `evidence/phase1-baseline.md`.

## 3. Phase 1 baseline reproduced

Re-running `fixed` + `VECTOR` in this session gives Recall@5 / MRR / nDCG@10 of 0.821 / 0.817 / 0.776 (dev) and 0.850 / 0.833 / 0.792 (test): identical to the committed baseline.

## 4. Results (Recall@5 / MRR / nDCG@10)

### dev

| Group | n | Chunking | VECTOR | BM25 | HYBRID |
|---|---|---|---|---|---|
| all | 26 | fixed | 0.821 / 0.817 / 0.776 | 0.840 / 0.842 / 0.801 | 0.897 / 0.897 / 0.846 |
| all | 26 | structural | 0.853 / 0.729 / 0.741 | 0.782 / 0.748 / 0.730 | 0.821 / 0.752 / 0.761 |
| factual | 10 | fixed | 0.900 / 0.846 / 0.879 | 0.800 / 0.669 / 0.723 | 0.900 / 0.911 / 0.914 |
| factual | 10 | structural | 0.800 / 0.728 / 0.789 | 0.700 / 0.636 / 0.657 | 0.800 / 0.696 / 0.728 |
| exact-term | 6 | fixed | 0.667 / 0.688 / 0.634 | 0.917 / 0.867 / 0.845 | 0.917 / 0.783 / 0.779 |
| exact-term | 6 | structural | 0.917 / 0.681 / 0.682 | 0.917 / 0.722 / 0.779 | 0.917 / 0.639 / 0.721 |
| multi-hop | 5 | fixed | 0.667 / 0.867 / 0.615 | 0.667 / 1.000 / 0.705 | 0.767 / 1.000 / 0.709 |
| multi-hop | 5 | structural | 0.733 / 0.750 / 0.660 | 0.567 / 0.750 / 0.547 | 0.567 / 0.750 / 0.636 |
| restricted | 5 | fixed | 1.000 / 0.867 / 0.900 | 1.000 / 1.000 / 1.000 | 1.000 / 0.900 / 0.926 |
| restricted | 5 | structural | 1.000 / 0.767 / 0.796 | 1.000 / 1.000 / 1.000 | 1.000 / 1.000 / 1.000 |

### test

| Group | n | Chunking | VECTOR | BM25 | HYBRID |
|---|---|---|---|---|---|
| all | 10 | fixed | 0.850 / 0.833 / 0.792 | 0.750 / 0.828 / 0.760 | 0.850 / 0.848 / 0.793 |
| all | 10 | structural | 0.800 / 0.725 / 0.779 | 0.800 / 0.661 / 0.716 | 0.900 / 0.717 / 0.750 |
| factual | 4 | fixed | 1.000 / 0.833 / 0.875 | 0.750 / 0.778 / 0.825 | 1.000 / 0.833 / 0.875 |
| factual | 4 | structural | 1.000 / 1.000 / 1.000 | 1.000 / 0.875 / 0.908 | 1.000 / 0.875 / 0.908 |
| exact-term | 2 | fixed | 0.500 / 0.500 / 0.500 | 0.500 / 0.583 / 0.609 | 0.500 / 0.571 / 0.602 |
| exact-term | 2 | structural | 0.500 / 0.333 / 0.425 | 0.500 / 0.250 / 0.359 | 0.750 / 0.417 / 0.469 |
| multi-hop | 2 | fixed | 0.750 / 1.000 / 0.903 | 0.750 / 1.000 / 0.732 | 0.750 / 1.000 / 0.807 |
| multi-hop | 2 | structural | 0.500 / 0.292 / 0.471 | 0.500 / 0.306 / 0.404 | 0.750 / 0.417 / 0.465 |
| restricted | 2 | fixed | 1.000 / 1.000 / 0.807 | 1.000 / 1.000 / 0.807 | 1.000 / 1.000 / 0.807 |
| restricted | 2 | structural | 1.000 / 1.000 / 1.000 | 1.000 / 1.000 / 1.000 | 1.000 / 1.000 / 1.000 |

Recall@10 is in each run's `report.md`; it is omitted here to keep the table readable. `test` has 10 scored items (2 `exact-term`, 2 `multi-hop`, 2 `restricted`): a change of one item is not evidence.

### Latency (client-side, `/v1/search`, p50 / p95 ms)

| Chunking | VECTOR | BM25 | HYBRID |
|---|---|---|---|
| fixed | 97 / 138 | 24 / 38 | 100 / 143 |
| structural | 79 / 135 | 23 / 42 | 88 / 138 |

Shared Elasticsearch and Ollama; the vector and hybrid figures are dominated by the query-embedding call.

## 5. Per-item first-match rank, HYBRID, fixed → structural

Of 36 scored items, structural has a better first-match rank on 6, the same on 19, a worse one on 11 (a missing match counts as worse than any rank). Items that differ:

| Id | Category | fixed | structural |
|---|---|---|---|
| g001 | factual | 1 | 3 |
| g011 | factual | 1 | - |
| g012 | factual | 9 | 8 |
| g013 | factual | 3 | 2 |
| g014 | factual | 1 | 2 |
| g015 | exact-term | 1 | 3 |
| g016 | exact-term | 1 | 2 |
| g017 | exact-term | 1 | 2 |
| g018 | exact-term | 2 | 1 |
| g019 | exact-term | 5 | 2 |
| g020 | exact-term | 7 | 3 |
| g021 | exact-term | 1 | 2 |
| g023 | multi-hop | 1 | 4 |
| g024 | multi-hop | 1 | 3 |
| g027 | multi-hop | 1 | 2 |
| g029 | multi-hop | 1 | 2 |
| g034 | restricted | 2 | 1 |

Same comparison under `VECTOR`: better on 7, same on 15, worse on 14.

## 6. Observations

Written only from the tables above. Differences of one item are not evidence (`test` n = 10, `exact-term` n = 6 on `dev`); `dev` is the primary basis.

1. **Baseline reproduced.** The `fixed` + `VECTOR` run matches the committed Phase 1 numbers exactly, so the new runs are comparable to it.
2. **Expectation 1 (hybrid wins on `exact-term`) holds for one configuration only.** On fixed chunks, `exact-term` Recall@5 on `dev` goes 0.667 (`VECTOR`) → 0.917 (`BM25`) → 0.917 (`HYBRID`). On structural chunks `VECTOR` already scores 0.917, so `HYBRID` adds nothing (0.917), and `exact-term` MRR is lower under `HYBRID` (0.639) than under `BM25` (0.722) or `VECTOR` (0.681). On `test` (n = 2) fixed scores 0.500 in all three modes. In `dev`, `BM25` alone matches `HYBRID` on `exact-term` Recall@5 for both chunkers: the gain comes from the lexical leg, and fusion did not add to it.
3. **Hybrid helps fixed chunks overall, not structural ones.** Fixed, `dev`: Recall@5 / MRR / nDCG@10 go 0.821 / 0.817 / 0.776 (`VECTOR`) → 0.897 / 0.897 / 0.846 (`HYBRID`). Structural, `dev`: Recall@5 goes 0.853 → 0.821 while MRR and nDCG@10 rise (0.729 → 0.752, 0.741 → 0.761). On `test` the signs differ again (fixed Recall@5 0.850 → 0.850; structural 0.800 → 0.900), which is what a 10-item set does.
4. **Expectation 2 (structural ≥ fixed on Recall@5) is not met under `HYBRID` or `BM25`.** `dev`: 0.821 vs 0.897 (`HYBRID`), 0.782 vs 0.840 (`BM25`); under `VECTOR` structural is higher (0.853 vs 0.821). `test` has no consistent direction. MRR on `dev` is lower for structural in all three modes (0.729 / 0.748 / 0.752 vs 0.817 / 0.842 / 0.897). Per item, the first relevant hit is ranked worse by structural on 11 items, better on 6, the same on 19 (`HYBRID`); under `VECTOR` 14 worse, 7 better, 15 the same. Structural chunking finds the relevant section about as often in the top 5 but ranks it lower. `multi-hop` is where the Recall@5 drop under `HYBRID` is largest (`dev` 0.767 → 0.567, n = 5).
5. **Possible causes, not tested in Phase 2.** (a) The scoring rule (spec 01 §3.4) accepts a fixed chunk if *any* section it spans matches, while a structural chunk spans one section, so the fixed chunker has an easier target; the rule was chosen to keep baselines comparable, but it favours large chunks. (b) Structural chunks are small (median 108 tokens) and carry no title or section-path header, so they give the embedder little context. Neither is verified here; (b) is what a contextual header (Phase 4 candidate) or parent expansion (Phase 3) would address.
6. **Best configuration on `dev` is fixed + `HYBRID`** (Recall@5 0.897, MRR 0.897, nDCG@10 0.846). On `test` structural + `HYBRID` has the higher Recall@5 (0.900 vs 0.850) and fixed the higher MRR (0.848 vs 0.717). The numbers do not settle which chunker to carry forward on retrieval metrics alone; the benefit structural chunking was built for (returning the whole parent section) is not measured until Phase 3.
7. **Latency.** `BM25` alone is 23–24 ms p50; any mode that embeds the query is 79–100 ms, so `HYBRID` costs about what `VECTOR` costs plus the BM25 leg. Shared host, noisy.

## 7. State left behind

Four indices exist in the `rag_dev-*` pool (`fixed`+nomic from Phase 1; structural+nomic, +bge-small, +bge-m3 from this Phase) plus `rag_dev-sections-struct400-v1`. The `rag_dev-chunks` alias was not moved and still points at the Phase 1 fixed index.
