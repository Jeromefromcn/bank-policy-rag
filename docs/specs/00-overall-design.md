# 00 · Overall Design Spec

- **Status**: Draft — awaiting author review
- **Scope**: whole project; every Phase spec (01–06) builds on this document
- **Source of truth**: `ROADMAP.md`. This spec does not repeat the roadmap; it fixes the
  cross-cutting decisions that later Phases depend on, and defines how success is measured.

---

## 1. Goals and non-goals

As in ROADMAP §0. In one sentence: a measurable, controllable RAG service whose answers are
cited, refused when unsupported, filtered by caller clearance at retrieval time, replayable
from the audit log, gated by eval numbers in CI, and reusable as MCP tools.

Additional non-goals fixed here:
- **Timing side channels** in refusals (a restricted-but-filtered query may be faster or
  slower than an empty one) are out of scope; only response content must be indistinguishable.
- **Write operations** via REST or MCP. The only write path is the ingest CLI.
- **Horizontal scaling.** Single application instance, single ES node.

## 2. Corpus scope

| Part | Source | Format | In git | Classification |
|---|---|---|---|---|
| Regulatory | HKMA SPM modules listed in `corpus/manifest.yaml` | PDF | **No** — user downloads to `corpus/raw/` | `public` |
| Internal | Example Bank, `corpus/internal/*.md` (8–12 docs) | Markdown + front matter | Yes | per front matter |
| Adversarial | `corpus/adversarial/*.md` (2–3 docs) | Markdown + front matter | Yes | per front matter (see Q3) |

Front matter for every Markdown document:

```yaml
---
docId: eb-incident-mgmt-standard     # stable, kebab-case, prefix eb- or adv-
title: Technology Incident Management Standard
version: "1.0"
classification: internal             # public | internal | restricted
sourceType: internal                 # internal | adversarial
fictional: true
---
```

`corpus/manifest.yaml` (no URLs):

```yaml
modules:
  - docId: spm-or-2
    code: OR-2
    title: Operational Resilience
    version: "V.1"            # as printed in the module
    revisionDate: 2022-05-31  # placeholder — verified in Phase 0
    sha256: "<hex>"
```

`corpusVersion` = first 12 hex chars of `sha256(manifest.yaml bytes + sorted internal/adversarial file hashes)`.
Including the Markdown hashes means editing an Example Bank document changes `corpusVersion`
(the roadmap only hashes the manifest; this is a deliberate extension).

## 3. Architecture

### 3.1 Components

```mermaid
flowchart LR
    subgraph clients["Callers"]
        rest["REST client"]
        agent["MCP client / agent"]
        runner["eval-runner"]
    end

    subgraph app["app (Spring Boot)"]
        sec["Security<br/>API key → Principal"]
        api["REST controllers /v1"]
        mcp["MCP tools"]
        svc["AskService / SearchService"]
        ret["Retrievers<br/>Vector · BM25 · Hybrid"]
        rr["Reranker client"]
        gen["Generator<br/>Spring AI ChatModel"]
        aud["AuditWriter"]
        ing["Ingest CLI"]
    end

    subgraph core["core (plain Java)"]
        parse["Parsers<br/>PDF · Markdown"]
        chunk["Chunkers<br/>fixed · structural"]
        rrf["RRF fusion"]
        cv["CitationValidator"]
        met["Metrics"]
    end

    rest --> sec
    agent --> sec
    runner --> sec
    sec --> api & mcp
    api & mcp --> svc
    svc --> ret --> rrf
    svc --> rr
    svc --> gen
    svc --> cv
    svc --> aud
    ing --> parse --> chunk
    runner -.-> met

    ret <--> es[("Elasticsearch")]
    aud --> es
    ing --> es
    ret -- "query embedding" --> emb["EmbeddingModel<br/>Ollama · Fixture"]
    ing --> emb
    gen <--> llm["DeepSeek / local Ollama"]
    rr <--> rerank["Reranker sidecar"]
```

Module rules:
- `core` has **no Spring and no Elasticsearch dependency**. Everything in it is unit-testable
  without containers.
- `app` owns all I/O: HTTP, MCP, ES, Ollama, DeepSeek, reranker.
- `eval-runner` talks to `app` **over HTTP with real API keys**, so evals exercise the real
  authentication and permission path. It uses `core` only for metric computation.

### 3.2 `/v1/ask` request flow

```mermaid
sequenceDiagram
    autonumber
    participant C as Caller
    participant S as Security
    participant A as AskService
    participant E as EmbeddingModel
    participant ES as Elasticsearch
    participant L as LLM
    participant V as CitationValidator
    participant AU as Audit

    C->>S: POST /v1/ask (X-Api-Key)
    S->>A: question + Principal{name, clearance, roles}
    A->>E: embed("search_query: " + question)
    A->>ES: kNN (filter: classification ∈ visible(clearance))
    A->>ES: BM25 (bool.filter: same)
    A->>A: RRF fuse → [rerank] → top-k
    alt top-1 similarity < threshold
        A->>AU: REFUSED (LOW_RETRIEVAL_SCORE or NO_ACCESSIBLE_SOURCES internally)
        A-->>C: REFUSED, reason LOW_RETRIEVAL_SCORE
    else enough evidence
        A->>L: rendered prompt (numbered, delimited sources)
        L-->>A: answer with [n] markers
        A->>V: validate citations
        alt validation fails
            A->>AU: REFUSED (CITATION_VALIDATION_FAILED)
            A-->>C: REFUSED
        else valid
            A->>AU: ANSWERED + full record
            A-->>C: ANSWERED + citations
        end
    end
```

## 4. Permission model

Fixed now, because it shapes the Phase 1 index mapping and retriever interfaces even though
enforcement tests land in Phase 5.

- `Clearance` is an ordered enum: `PUBLIC < INTERNAL < RESTRICTED`.
  `visible(c) = { l | l ≤ c }`.
- `Principal{name, clearance, roles}` is the only identity type below the security layer.
  Retrievers, services and MCP tools receive it as a **required parameter** — there is no
  overload without a principal, so a call site cannot forget the filter.
- Filtering is a **pre-filter** in both legs (kNN `filter`, BM25 `bool.filter`) and in
  `GET /v1/sections/{id}` / `get_section`. Post-filtering is forbidden (ROADMAP §4.2).
- **Phase 1–4**: the filter is already applied, with a single dev key at `RESTRICTED`
  clearance. **Phase 5** adds the configured key set, role checks on audit endpoints, and the
  leak tests. No mapping or interface change happens in Phase 5.
- **Existence leak rule**: a resource the caller cannot see behaves exactly like a resource
  that does not exist — same status code, same body shape, same refusal reason
  (`/ask` → `LOW_RETRIEVAL_SCORE`; `/sections/{id}` → `404` problem+json).
- `NO_ACCESSIBLE_SOURCES` is determined by a second, unfiltered **count-only** query run
  after a refusal, solely for the audit record and metrics. Its result never reaches the
  response. (See Q4 — this query itself touches restricted documents.)

## 5. Cross-cutting conventions

| Topic | Decision |
|---|---|
| IDs | `queryId`, `answerId`: `q_` / `a_` + ULID. `sectionId`, `chunkId`: deterministic hashes per ROADMAP §4.1–4.2 |
| Section paths | Compared component-wise: `"3.2"` prefixes `"3.2.1"` but **not** `"3.20"` |
| Index naming | `rag-chunks-<model><dims>-<chunker>-v<n>` behind alias `rag-chunks`; `rag-sections-v<n>` behind `rag-sections`; `rag-audit` |
| Versions recorded | `corpusVersion`, `chunkerVersion`, `embeddingModel`, `promptTemplateVersion`, model id + params — on every chunk and every audit record as applicable |
| Prompt templates | `app/src/main/resources/prompts/<name>.v<n>.st`; never edited in place, a change is a new version |
| Errors | RFC 9457 `application/problem+json` with a stable `type` URI per error kind |
| Config | Every resource limit, JVM flag, threshold and model id is a property; nothing hard-coded |
| Embedding prefixes | Applied inside the `EmbeddingModel` adapter per model, not by callers |
| Determinism | Re-ingesting the same corpus with the same chunker yields byte-identical `chunkId` and `contentHash` sets; this is a test |

**Fixture embedding mode.** `EmbeddingModel` has two implementations: Ollama, and a
`FixtureEmbeddingModel` that looks up vectors by `sha256(prefixed text)` from the fixture
files (ROADMAP §8) and fails loudly on a miss. CI runs the app with the fixture
implementation, so the same HTTP path is exercised without Ollama.

## 6. Evaluation method

### 6.1 Golden set

Schema and distribution as in ROADMAP §5 Phase 0. Additional rules:
- `expectedSources` is empty for `unanswerable` items.
- For `restricted` items, `minClearance` is above `public`.
- Every item is human-verified before commit (ROADMAP §10).

### 6.2 How each item is run

| Category | Retrieval metrics run as | Refusal check run as |
|---|---|---|
| factual, exact-term, multi-hop | `RESTRICTED` key | — (must answer) |
| restricted | key with clearance = `minClearance` | key with clearance below `minClearance` → must refuse |
| unanswerable | excluded from retrieval metrics | `RESTRICTED` key → must refuse |

### 6.3 Metric definitions

A retrieved chunk **matches** an expected source if `docId` is equal and the expected
`section` is a component-wise prefix of the chunk's `sectionPath`. Multiple chunks matching
the same expected source count once (the first, highest-ranked one).

- **Recall@k** = (expected sources matched in top k) / (number of expected sources), averaged
  over items. Reported for k = 5 (primary) and k = 10.
- **MRR** = 1 / rank of the first matching chunk within top 10, else 0; averaged.
- **nDCG@10**: binary gain per newly matched expected source; ideal DCG assumes
  `min(|expected|, 10)` matches at the top.
- **Refusal accuracy**, reported separately for `unanswerable` and `restricted`:
  correctly refused / total. Also reported: **false refusal rate** on answerable items.
- **Faithfulness** (Phase 3): for each `expectedPoint`, the judge returns a binary verdict
  "stated in the answer **and** supported by a cited chunk"; score = supported points / total.
- **Judge agreement** (Phase 3): fraction of the 20 human-labelled point verdicts on which
  judge and human agree; Cohen's κ reported alongside.
- **Latency**: p50 and p95 per stage, from the response `latencyMs` fields.

Every report breaks metrics down by `category`, and records `corpusVersion`,
`chunkerVersion`, `embeddingModel`, git commit and machine.

## 7. Success criteria

No absolute numbers are set before Phase 1 measures a baseline.

**Hard rules — any violation fails the project, regardless of metrics:**
1. Zero `restricted` content in any response to a lower-clearance caller, across permission
   tests, injection tests and the eval run.
2. Refusals for inaccessible content are indistinguishable in content from refusals for
   absent content.
3. No adversarial-document instruction is followed in the injection tests.
4. Any audit record can be replayed; a `contentHash` mismatch is detected and reported.
5. Re-ingest determinism (§5) holds.
6. No SPM text, PDF or HKMA URL exists anywhere in git history.
7. Every container has CPU and memory limits (`check-compose-limits.sh` passes).

**Relative criteria — measured against the Phase 1 baseline, reported honestly either way:**
- Phase 2: HYBRID beats VECTOR on `exact-term` Recall@5; structural chunking is at least as
  good as fixed chunking overall. If not, the report explains why.
- Phase 3: a refusal threshold exists that lowers wrong-answer rate on `unanswerable` without
  raising false refusals beyond a level the author accepts in `decisions/03`.
- Phase 4: rerank is adopted only if the Recall@5 gain justifies the p95 cost; the decision is
  recorded either way.
- Phase 5: CI gate fails on a Recall@5 or MRR drop greater than 0.02 vs `eval/baseline.json`.

## 8. Spec plan

| Spec | Phase | Main content |
|---|---|---|
| `00-overall-design.md` | 0 | This document |
| `01-baseline.md` | 1 | compose, ingest v1, VECTOR search, `/ask`, eval runner v1 |
| `02-retrieval.md` | 2 | structural chunking, parent–child, BM25, RRF, ablations |
| `03-grounded-generation.md` | 3 | citations, refusal threshold, prompt hardening, eval runner v2 |
| `04-rerank-rewrite.md` | 4 | optional; written only if Phase 4 is taken on |
| `05-productionisation.md` | 5 | auth, audit + replay, observability, injection tests, CI gate (may split into 05a/05b) |
| `06-mcp.md` | 6 | MCP tools, auth parity, integration record |

Each Phase spec has a matching `NN-<name>-plan.md` implementation plan. Phase 0 content work
(manifest, internal documents, golden set) needs no separate spec beyond §2 and §6 here.

## 9. Phase 0 work plan

1. Verify pending items and record results in §10 below.
2. Write `corpus/manifest.yaml`, `scripts/verify-corpus.sh`, `.env.example`.
3. Author writes / finalises Example Bank documents and adversarial samples.
4. Author writes / finalises `eval/golden-set.yaml` (40+ items, distribution per ROADMAP).
5. Author writes `decisions/01-corpus-and-stack.md`, including the CI URL choice (ROADMAP §8).
6. Tick Phase 0 in `ROADMAP.md`.

## 10. Verification results (filled in during Phase 0)

| Item | Result | Date |
|---|---|---|
| Spring Boot / Spring AI versions | _pending_ | |
| DeepSeek model names | _pending_ | |
| SPM module versions | _pending_ | |
| HKMA terms unchanged since 2026-11 | _pending_ | |

Items for later Phases (ES `rrf` licence, arm64 reranker, in-process ONNX) are verified in
their own specs.

## 11. Open questions for the author

- **Q1 · Golden set overfitting.** The refusal threshold (Phase 3, 4) is swept on the same
  golden set it is evaluated on. Split into dev (~70%) / test (~30%) and report test only?
  With ~40 items the test split is small. *Suggestion: split, and report both.*
- **Q2 · Faithfulness scope.** The roadmap's metric checks expected points only, so unsupported
  extra claims go unpunished. Add a claim-level check (judge every cited sentence)?
  *Suggestion: add it as a second metric in Phase 3.*
- **Q3 · Adversarial classification.** Should adversarial documents be `public` (reachable by
  anyone, worst case) or mixed? *Suggestion: `public`, plus one `internal` sample.*
- **Q4 · `NO_ACCESSIBLE_SOURCES` detection.** It requires an unfiltered count query, which
  contradicts "restricted documents never enter the pipeline" in spirit. Keep it (count only,
  audit only), or drop the reason entirely? *Suggestion: keep it, count only, admin-visible.*
- **Q5 · `corpusVersion` definition.** Accept the extension in §2 that includes Markdown
  file hashes?
