# Phase 2 Structural Chunking, Hybrid Retrieval and Embedding Ablation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Structural chunking with a parent `sections` index, a BM25 leg with application-layer RRF behind `mode`, and the two ablations whose tables become the Phase 2 evidence.

**Architecture:** `core` gains `Chunker` (interface), `StructuralChunker`, `SectionParents` and `Rrf`, all pure. `app` gains a BM25 retriever, a `HybridSearch` service that fuses the legs, a sections index and endpoint, per-model embedding config, and ingest options. `eval-runner` gains `--mode`. No new modules.

**Spec:** `docs/specs/02-retrieval.md` (with specs 00 and 01 and ROADMAP). Section references (`§3.2`) are to spec 02 unless prefixed. Global constraints, pinned versions and image digests are those of `docs/specs/01-baseline-plan.md` and are not repeated.

## Global Constraints (additions to the Phase 1 plan)

- Same rules as Phase 1: English only, Mermaid only, no SPM text in git, limits on every container, pins, no secrets, eval numbers from runner output only.
- Permission filtering is an ES pre-filter on **both** legs. `Principal` is a required parameter of every retriever and of the sections lookup. No overload without it.
- Commit trailer: `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- Tick the ROADMAP §5 Phase 2 box named in each task in the commit that completes it.
- Work on a branch `phase2-retrieval`; merge to `main` only after the author approves the evidence.
- Pulling models onto the shared Ollama, and writing indices in the shared `rag_dev-*` pool, are expected. Anything outside `rag_dev-*` is not touched.

## Review Focus

1. **BM25 leg leaks a hit the caller may not see.** A `PUBLIC` principal must never get an `internal` hit, even when that hit is the only lexical match. Test: `Bm25RetrieverIT.publicCallerNeverSeesInternalOnlyMatch` (Task 6).
2. **A section that is too short or the last child of its parent.** Merge direction must not produce an empty chunk, a chunk across parents, or lost text. Test: `StructuralChunkerTest.mergesLastShortUnitBackwardWithinParent`, `.noChunkCrossesParents`, `.coversEveryCharacterAfterFirstMarker` (Task 2).
3. **Determinism.** The same document chunked twice gives identical chunk IDs and hashes; the CI fixture design (ROADMAP §8) depends on it. Test: `StructuralChunkerTest.isDeterministic` (Task 2).
4. **One leg empty or failing.** `HYBRID` with an empty BM25 result equals `VECTOR`; `BM25` works with Ollama down; `HYBRID` with Ollama down is `503`, never a silent BM25 result. Tests: `HybridSearchTest.*`, `SearchControllerIT.bm25WorksWithoutEmbedder`, `.hybridFailsWhenEmbedderDown` (Task 6).
5. **Sections endpoint existence leak.** An inaccessible section and an unknown id return the same `404` body. Test: `SectionsControllerIT.inaccessibleAndUnknownAreIndistinguishable` (Task 7).

---

## File Structure

```
core/src/main/java/rag/core/
  chunk/   Chunker (new interface), FixedChunker (implements it), StructuralChunker (new)
  chunk/   SectionParent (record), SectionParents (builds parents from a ParsedDocument)
  retrieve/ Rrf, RankedId
app/src/main/java/rag/app/
  RagProperties            (modified: models, legSize, indices, structural chunking)
  embed/   OllamaEmbedder  (modified: per-model config), EmbeddingConfig (modified)
  es/      IndexNames (modified), ChunkIndexAdmin (modified: sections mapping), SectionDocument, SectionWriter, SectionLookup
  ingest/  IngestCommand   (modified: --chunker --model --promote, sections)
  search/  Mode, Bm25Retriever, HybridSearch, VectorRetriever (modified), SearchController/Request/Hit (modified)
  sections/ SectionsController, SectionResponse
eval-runner/src/main/java/rag/eval/  EvalRunner, SearchClient, RunMetadata, ReportWriter (modified: mode)
scripts/ run-ablation.sh, tests/test-run-ablation.sh
```

---

## Task 0: Branch, spec status, §2 verification

**Files:** `docs/specs/02-retrieval.md`, `docs/specs/02-retrieval-plan.md`

- [ ] **Step 1:** `git switch -c phase2-retrieval`. Set the spec status to `Approved <date>`. Commit spec and plan: `Add Phase 2 spec and plan`.
- [ ] **Step 2 (ES `rrf` licence):** read Elastic's subscription page for the `rrf` retriever; then, once Task 3's Testcontainers helper exists, run one `POST _search` with an `rrf` retriever against a default-licence container. Write the page finding and the live result into spec §2. Tick the ROADMAP §10 item in the same commit.
- [ ] **Step 3 (models):** **ask the author** before pulling anything onto the shared Ollama. Then pull the exact `bge-small-en-v1.5` and `bge-m3` tags, call `/api/embed` once each, confirm 384 / 1024 dims, and write tags and dims into spec §2 and §5. If no official `bge-small-en-v1.5` tag exists, use a GGUF import and record its provenance.
- [ ] **Step 4:** Commit `Record Phase 2 verification results`.

## Task 1: `Rrf` (pure)

**Files:** `core/.../retrieve/Rrf.java`, `RankedId.java`; test `RrfTest`.

- [ ] **Step 1: tests first.** `fuse(List<List<String>> legs, int k, int topK)` returns `List<RankedId{id, score, legRanks}>`. Cases, all hand-computed with `k = 60`: two legs with overlap (`1/61 + 1/62`); an id in one leg only; tie-break (best single-leg rank, then id ascending); one empty leg; both empty; `topK` truncation; duplicate id inside one leg counts its first rank only.
- [ ] **Step 2:** implement; run `./gradlew :core:test --tests RrfTest`.
- [ ] **Step 3:** commit `Add reciprocal rank fusion`.

## Task 2: `StructuralChunker` and parents

**Files:** `core/.../chunk/Chunker.java`, `StructuralChunker.java`, `SectionParent.java`, `SectionParents.java`; modify `FixedChunker`; tests `StructuralChunkerTest`, `SectionParentsTest`.

- [ ] **Step 1: tests first**, using self-made text and hand-built `ParsedDocument`s (same helpers as `FixedChunkerTest`):
  - one chunk per marker span when all spans are within `[min, max]`;
  - front matter before the first marker produces no chunk (`coversEveryCharacterAfterFirstMarker`);
  - short unit merges into the next sibling in the same depth-1 parent; `coveredSections` lists both leaves; `sectionPath` is the first unit's;
  - last short unit of a parent merges backward (`mergesLastShortUnitBackwardWithinParent`);
  - a parent made of a single short unit stays as one chunk;
  - oversize unit splits on sentence boundaries, each piece ≤ max, consecutive `ordinal`s, same `sectionPath`;
  - one sentence longer than max becomes its own chunk, no infinite loop;
  - `noChunkCrossesParents`; `isDeterministic`; `version()` is `structural-v1-400-cl100k` for `(400, 60)`;
  - Markdown-style document (markers are headings only) works identically;
  - chunk `sectionId` equals the parent's `sectionId`.
  `SectionParentsTest`: one parent per depth-1 path; `content` is the text from its first marker to the next depth-1 marker; `pages` from page spans; documents with no markers yield no parents.
- [ ] **Step 2:** extract `Chunker { String version(); List<Chunk> chunk(ParsedDocument) }`; `FixedChunker implements Chunker` unchanged otherwise (its tests must still pass untouched).
- [ ] **Step 3:** implement; run `./gradlew :core:test`.
- [ ] **Step 4:** commit `Add structural chunker and section parents`. Tick: ROADMAP §5 Phase 2 "Structural chunking" is **not** ticked yet (parent–child lands in Task 5).

## Task 3: Properties, per-model embedder, index names

**Files:** `RagProperties`, `application.yaml`, `OllamaEmbedder`, `EmbeddingConfig`, `IndexNames`, `.env.example`; tests `OllamaEmbedderTest`, `IndexNamesTest`.

- [ ] **Step 1: tests first.** `OllamaEmbedderTest`: for each of the three model configs, the stubbed server receives the right query prefix, document prefix (none for bge) and the adapter rejects a wrong-dimension response naming the model. `IndexNamesTest`: `rag_dev-chunks-nomic768-struct400-v1`, `rag_dev-chunks-bgesmall384-struct400-v1`, `rag_dev-chunks-bgem3-1024-struct400-v1` (as in spec §5), `rag_dev-sections-struct400-v1`, aliases, and the existing fixed names unchanged.
- [ ] **Step 2:** add properties: `rag.embedding.models.<name>.{id, short-name, dims, query-prefix, document-prefix}` with the three entries; `rag.embedding.model` selects one (default `nomic-embed-text`); `rag.chunking.{kind, structural.max-tokens (400), structural.min-tokens (60)}`; `rag.retrieval.{leg-size (50), chunks-index, sections-index}` (blank → alias). Env overrides: `RAG_EMBEDDING_MODEL`, `RAG_CHUNKS_INDEX`, `RAG_SECTIONS_INDEX`.
- [ ] **Step 3:** generalise `OllamaEmbedder` from the config; remove the hard-coded nomic check and constants.
- [ ] **Step 4:** run `./gradlew :app:test --tests '*Embedder*' --tests IndexNamesTest`; commit `Configure embedding prefixes and dims per model`.

## Task 4: Sections mapping and writer

**Files:** `ChunkIndexAdmin`, `SectionDocument`, `SectionWriter`; test `SectionIndexIT` (Testcontainers).

- [ ] **Step 1: test first.** Create a sections index, bulk-write two parents, read them back; mapping is `dynamic: strict`, `content` uses the `english` analyzer, `_meta` records `corpusVersion` and `chunkerVersion`; `pointAlias` works for the sections alias.
- [ ] **Step 2:** implement (`rag-sections` fields per ROADMAP §4.1; no `embedding`). Chunk docs keep their mapping.
- [ ] **Step 3:** run; commit `Add sections index and writer`.

## Task 5: Ingest options

**Files:** `IngestCommand`; test `IngestIT`, `IngestCliContextIT` (extend).

- [ ] **Step 1: tests first.** `--chunker=structural` builds the chunks index and the sections index and every chunk's `sectionId` exists in sections; `--chunker=fixed` builds no sections index and indexes exactly as Phase 1; without `--promote` the alias is **not** moved (alias target unchanged); with `--promote` it is; `--step=embed` with a second model fills only that model's cache; an unknown `--chunker`/`--model` fails with a message naming the allowed values; the CLI context test still starts without a chat model.
- [ ] **Step 2:** implement: `--chunker=fixed|structural` (default from `rag.chunking.kind`, default `fixed` so Phase 1 behaviour is unchanged), `--model=<configured name>`, `--promote`. `--promote` replaces the unconditional `pointAlias` of Phase 1. The ingest log prints chunk count, token statistics (min / median / max) and per-step times for the evidence.
- [ ] **Step 3:** run; commit `Add chunker and model options to ingest`. Tick ROADMAP §5 Phase 2 "Structural chunking".

## Task 6: BM25 leg, `HYBRID`, `mode`

**Files:** `Mode`, `Bm25Retriever`, `HybridSearch`, `VectorRetriever`, `SearchRequest`, `SearchController`, `SearchHit`, `AskService` (call site); tests `Bm25RetrieverIT`, `HybridSearchTest`, `SearchControllerIT`.

- [ ] **Step 1: tests first.**
  - `Bm25RetrieverIT`: `multi_match` over `content` and `docTitle`; a module code present only in `docTitle` is found; `publicCallerNeverSeesInternalOnlyMatch`; `restricted` caller sees it.
  - `HybridSearchTest` (stub legs): fusion order matches `Rrf`; `scores` carry `vectorRank` / `bm25Rank`, absent for a missing leg; empty BM25 leg equals `VECTOR`; embedder failure in `HYBRID` propagates (`503`), in `BM25` is never called.
  - `SearchControllerIT`: `mode` default `HYBRID`; each mode returns 200; bad mode `400`; `rerank: true` still `400`; `bm25WorksWithoutEmbedder`; `hybridFailsWhenEmbedderDown`; the Phase 1 `VECTOR` pre-filter test still passes.
- [ ] **Step 2:** implement. Both retrievers read `rag.retrieval.chunks-index` (blank → alias) and take `Principal`. `VectorRetriever` returns raw ranked hits; `HybridSearch` owns mode dispatch and fusion. `/v1/ask` calls `HybridSearch` with the request's `mode` (default `HYBRID`); its Phase 1 tests stay green with `mode = VECTOR` where they depend on vector order.
- [ ] **Step 3:** run `./gradlew :app:test`; commit `Add BM25 retrieval and RRF hybrid search`. Tick ROADMAP §5 Phase 2 "BM25 leg + RRF".

## Task 7: `GET /v1/sections/{sectionId}`

**Files:** `SectionLookup`, `SectionsController`, `SectionResponse`; test `SectionsControllerIT`.

- [ ] **Step 1: tests first.** Returns the parent for a visible id; `inaccessibleAndUnknownAreIndistinguishable` (identical status and problem body); `PUBLIC` caller gets `404` for an `internal` section; no key → `401`; fixed-chunker deployments (no sections index) → `404`.
- [ ] **Step 2:** implement with a `bool.filter` on `classification` plus `term sectionId` (pre-filter, same rule as the legs).
- [ ] **Step 3:** run; commit `Add section lookup endpoint`. Tick ROADMAP §5 Phase 2 "Parent–child".

## Task 8: Eval runner `--mode`

**Files:** `EvalRunner`, `SearchClient`, `RunMetadata`, `ReportWriter`; tests in `eval-runner`.

- [ ] **Step 1: tests first** (stub server): `--mode=BM25` is sent in the request body; default is `HYBRID`; the report header contains the mode, chunker version and embedding model; an invalid mode exits `2`.
- [ ] **Step 2:** implement; report filename and directory unchanged.
- [ ] **Step 3:** run `./gradlew :eval-runner:test`; commit `Pass retrieval mode through the eval runner`.

## Task 9: Ablation script

**Files:** `scripts/run-ablation.sh`, `scripts/tests/test-run-ablation.sh`, `.env.example`.

- [ ] **Step 1: test first** (stubbed `git`, `curl` and `java` on `PATH`): dirty tree → exit 1 with a message; unknown table name → exit 2; a dry run (`--dry-run`) prints the exact app/runner invocations without running them.
- [ ] **Step 2:** implement `run-ablation.sh {retrieval|embedding}`. For each index it starts the app with `RAG_CHUNKS_INDEX` / `RAG_SECTIONS_INDEX` / `RAG_EMBEDDING_MODEL`, waits for `/actuator/health`, runs the runner for each mode, stops the app. Output is the usual `eval/runs/<timestamp>/` directories plus one `index.txt` per table mapping run → mode, index and commit. It never moves an alias.
- [ ] **Step 3:** run the bash test; commit `Add ablation driver script`.

## Task 10: Execute the experiments and write the evidence

No product code. Nothing here is estimated: every number is copied from runner or ingest output.

- [ ] **Step 1:** build indices in `rag_dev-*`: `fixed` + `nomic` (rebuild; the Phase 1 index is the same), `structural` + `nomic`, `structural` + `bge-small`, `structural` + `bge-m3`. Record chunk counts, token statistics and embed-step wall time for each (empty cache for `bge-*`). Record index size from `_cat/indices` if the user can read it, otherwise omit the column and say so.
- [ ] **Step 2:** `scripts/run-ablation.sh retrieval` (six runs) and `... embedding` (three runs). Also re-run the Phase 1 row and state any difference from the committed baseline (spec §7).
- [ ] **Step 3:** write `evidence/phase2-chunking-retrieval.md` and `evidence/phase2-embedding.md` per spec §8: expectation first, then tables with `n`, then observations written only from the numbers. Contradictions are reported as they are.
- [ ] **Step 4:** tick the two ROADMAP §5 ablation boxes and the Phase 2 line in Progress in the same commit as the evidence: `Record Phase 2 ablation results`.
- [ ] **Step 5:** **stop for author review.** Merge to `main` only after approval, via the finishing-a-development-branch skill.

## Self-review against the spec

| Spec | Task |
|---|---|
| §2 verification | 0 |
| §3.1–3.2 chunker | 2 |
| §3.3 parents, endpoint | 2, 4, 5, 7 |
| §4 BM25, RRF, modes | 1, 6 |
| §5 models | 3 |
| §6 index selection, `--promote` | 3, 5, 9 |
| §7 runner, ablation | 8, 9, 10 |
| §8 evidence | 10 |
| §9 tests | every task |
