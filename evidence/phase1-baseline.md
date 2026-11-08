# Phase 1 · Naive baseline

- **Date**: 2026-11-08
- **Spec**: `docs/specs/01-baseline.md`
- **Purpose**: the reference point for every later Phase. Deliberately not tuned.

All numbers below come from one eval-runner run (`eval/runs/20261108T134954Z/`, not committed)
and from the ingest logs of the same day. Per-item rows show ids and ranks only, never chunk text.

## 1. Configuration

| Item | Value |
|---|---|
| Retrieval | `VECTOR` only: kNN on `embedding`, classification pre-filter inside the kNN clause |
| Chunker | `fixed-512-64-cl100k` (512 cl100k tokens, 64 overlap, word boundaries) |
| Embedding | `ollama/nomic-embed-text`, 768 dims, `num_thread: 3`, task prefixes `search_document: ` / `search_query: ` |
| Index | `rag_dev-chunks-nomic768-fixed512-v1`, HNSW `m: 16`, `ef_construction: 100`, no quantisation |
| Query | `k = topK = 10` for the eval, `num_candidates: 100` |
| Caller | dev key, `restricted` clearance (spec 01 §5: per-clearance runs start in Phase 5) |
| Scoring | spec 00 §6.3 with the spec 01 §3.4 rule (expected section matches `sectionPath` or any `coveredSections` entry); nDCG uses binary gain per rank |
| Versions | `corpusVersion` 9dd88979729b, git commit 74fe9a8 (clean) |
| Machine | aarch64, 4 CPUs; shared Elasticsearch 9.5.5 and Ollama 0.40.15 on the same host |

## 2. Ingest

| Measure | Value |
|---|---|
| Documents | 16 (5 SPM modules, 8 Example Bank, 3 adversarial) |
| Chunks | 116 |
| Tokens (cl100k) | 55,111 |
| Parse + chunk | 2.1 s |
| Embed step (116 chunks, empty cache) | 94.1 s |
| Index step (create, bulk, alias) | 1.1 s |

Chunks per document: OR-1 45, TM-G-1 21, TM-G-2 17, OR-2 13, SA-2 8,
`eb-incident-mgmt-standard` 2, every other Markdown document 1.

## 3. Retrieval metrics (eval runner output, verbatim)

### Retrieval metrics

#### test

| Group | n | Recall@5 | Recall@10 | MRR | nDCG@10 |
|---|---|---|---|---|---|
| all | 10 | 0.850 | 0.900 | 0.833 | 0.792 |
| factual | 4 | 1.000 | 1.000 | 0.833 | 0.875 |
| exact-term | 2 | 0.500 | 0.500 | 0.500 | 0.500 |
| multi-hop | 2 | 0.750 | 1.000 | 1.000 | 0.903 |
| restricted | 2 | 1.000 | 1.000 | 1.000 | 0.807 |

#### dev

| Group | n | Recall@5 | Recall@10 | MRR | nDCG@10 |
|---|---|---|---|---|---|
| all | 26 | 0.821 | 0.865 | 0.817 | 0.776 |
| factual | 10 | 0.900 | 0.967 | 0.846 | 0.879 |
| exact-term | 6 | 0.667 | 0.750 | 0.688 | 0.634 |
| multi-hop | 5 | 0.667 | 0.667 | 0.867 | 0.615 |
| restricted | 5 | 1.000 | 1.000 | 0.867 | 0.900 |

### Latency (client-side, /v1/search)

p50 80 ms, p95 123 ms

### Items

| Id | Category | Split | First match rank | Recall@5 |
|---|---|---|---|---|
| g001 | factual | dev | 1 | 1.000 |
| g002 | factual | dev | 3 | 1.000 |
| g003 | factual | test | 1 | 1.000 |
| g004 | factual | dev | 1 | 1.000 |
| g005 | factual | dev | 1 | 1.000 |
| g006 | factual | test | 1 | 1.000 |
| g007 | factual | dev | 1 | 1.000 |
| g008 | factual | dev | 1 | 1.000 |
| g009 | factual | test | 1 | 1.000 |
| g010 | factual | dev | 1 | 1.000 |
| g011 | factual | dev | 1 | 1.000 |
| g012 | factual | dev | 8 | 0.000 |
| g013 | factual | test | 3 | 1.000 |
| g014 | factual | dev | 1 | 1.000 |
| g015 | exact-term | dev | 1 | 1.000 |
| g016 | exact-term | test | 1 | 1.000 |
| g017 | exact-term | dev | 1 | 1.000 |
| g018 | exact-term | dev | - | 0.000 |
| g019 | exact-term | dev | 8 | 0.000 |
| g020 | exact-term | test | - | 0.000 |
| g021 | exact-term | dev | 1 | 1.000 |
| g022 | exact-term | dev | 1 | 1.000 |
| g023 | multi-hop | dev | 1 | 0.500 |
| g024 | multi-hop | test | 1 | 0.500 |
| g025 | multi-hop | dev | 1 | 1.000 |
| g026 | multi-hop | dev | 3 | 0.333 |
| g027 | multi-hop | test | 1 | 1.000 |
| g028 | multi-hop | dev | 1 | 0.500 |
| g029 | multi-hop | dev | 1 | 1.000 |
| g030 | restricted | dev | 1 | 1.000 |
| g031 | restricted | dev | 1 | 1.000 |
| g032 | restricted | test | 1 | 1.000 |
| g033 | restricted | dev | 1 | 1.000 |
| g034 | restricted | dev | 3 | 1.000 |
| g035 | restricted | test | 1 | 1.000 |
| g036 | restricted | dev | 1 | 1.000 |

## 4. `/v1/ask` smoke test

Three questions through the full path (retrieval → prompt `answer.v1` → `deepseek-flash`,
temperature 0). Phase 1 returns every retrieved source as a citation; no validation, no refusal.

| Golden id | Status | Cited docIds (ranks 1–5) | Prompt / completion tokens | Retrieval / generation ms |
|---|---|---|---|---|
| g002 | ANSWERED | eb-incident-mgmt-standard, eb-cyber-escalation-playbook, eb-incident-mgmt-standard, spm-or-2, spm-or-2 | 2110 / 60 | 60 / 1411 |
| g004 | ANSWERED | eb-change-mgmt-policy, eb-incident-mgmt-standard, adv-meeting-notes, spm-tm-g-2, spm-or-2 | 1498 / 67 | 53 / 998 |
| g021 | ANSWERED | spm-tm-g-2, spm-or-2, spm-tm-g-2, spm-tm-g-2, spm-tm-g-2 | 2769 / 162 | 83 / 1128 |

Answers (Example Bank questions only; the SPM-grounded answer to g021 is not recorded):

- **g002**: For an S1 incident, the Incident Manager must notify the Chief Information Officer (CIO) within **30 minutes of declaration** [3].
- **g004**: A normal change must be submitted to the CAB at least **5 business days** before the planned implementation date [1].

## 5. Observations

Drawn only from the numbers above.

1. **Headline (`test`, n = 10)**: Recall@5 0.850, MRR 0.833, nDCG@10 0.792. The `dev` split
   (n = 26) is close: 0.821 / 0.817 / 0.776. With 10 test items, one item moves Recall@5 by 0.1,
   so differences below that are noise.
2. **`exact-term` is the weakest category**: Recall@5 0.500 on `test` (n = 2) and 0.667 on `dev`
   (n = 6). Three of the eight items fail: g018 and g020 have no match in the top 10, and g019
   first matches at rank 8. This is what ROADMAP §1.1 predicts for pure vector retrieval and is
   the comparison Phase 2's BM25 / HYBRID modes are built to test.
3. **Example Bank recall is effectively document-level in Phase 1.** 10 of the 11 Markdown
   documents fit in one 512-token chunk, so that chunk covers every section of its document.
   Under the §3.4 rule, retrieving the document matches any expected section in it. This
   flatters the `factual` and `restricted` rows, which are mostly Example Bank items. Phase 2's
   structural chunking removes this effect, so a drop there would not necessarily be a regression.
4. **`restricted` scores 1.000 Recall@5 on both splits**, but the run uses a `restricted` key.
   It measures retrieval of restricted documents, not permission filtering. Leak and refusal
   tests are Phase 5.
5. **`multi-hop` items usually find one of their sources**: g023, g024 and g028 reach Recall@5
   0.500 and g026 0.333, all with a first match in the top 3. The second source (often the other
   corpus part) is missing from the top 5.
6. **One SPM item misses at rank 8**: g012, a three-section TM-G-2 question, has Recall@5 0.000.
7. **Search latency**: p50 80 ms, p95 123 ms client-side, including query embedding on the
   shared Ollama. The shared host adds noise from other tenants (decisions/01).
8. **Embedding cost is small for this corpus**: 116 chunks embedded in 94 s on 3 cores. ROADMAP
   §7.1 estimated 20–40 minutes for about 1,500 chunks; the corpus is about 13 times smaller.
9. **An adversarial document reaches the prompt**: for g004, `adv-meeting-notes` (public) is the
   third source sent to the model. The answer cited only source 1. Prompt hardening is Phase 3;
   injection tests are Phase 5.
