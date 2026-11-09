# Phase 4 Rerank and Query Rewriting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Optional cross-encoder rerank on `/v1/search` and `/v1/ask` with a rerank-score refusal gate, server-side conversations with LLM query rewriting on `/v1/ask`, and eval-runner modes that measure both.

**Architecture:** A llama.cpp reranker container (profile `rerank`) behind a small `Reranker` client in `app`. A new `RetrievalPipeline` service wraps `HybridSearch`, adds candidate truncation, reranking and stage timings, and is the only retrieval entry point for both controllers. `AskService` gains the rerank gate and, via an in-memory `ConversationStore` and a `QueryRewriter`, multi-turn support. `eval-runner` gains `--rerank`, `--repeat`, a rerank-score sweep and `--multi-turn`.

**Tech Stack:** Java 25, Spring Boot 4.1.2 / Spring AI 2.0.2 (as pinned), JUnit 5, Testcontainers ES, llama.cpp server (arm64), jtokkit cl100k.

**Spec:** `docs/specs/04-rerank-rewrite.md` (with specs 00–03 and ROADMAP). Section references (`§4`) are to spec 04 unless prefixed.

## Global Constraints (additions to earlier plans)

- English only, Mermaid only, no SPM text in git, CPU and memory limits on every container (compose and any `docker run` in scripts), pinned versions and image digests, no secrets, eval numbers from runner output only.
- Permission filtering stays an ES pre-filter. The reranker only receives candidates that passed it.
- `rerank: false` (the default) must behave exactly as Phase 3: same hits, same scores, same response fields apart from additions.
- Prompt templates are never edited in place: `answer.v1.st` and `answer.v2.st` stay untouched; `rewrite.v1.st` is new.
- `decisions/04-rerank.md` and `eval/multi-turn.yaml` are author-owned: draft them in the scratchpad or evidence report, never commit them yourself.
- Model files (GGUF) and reranker reference outputs never go into git; `.cache/reranker/` is gitignored.
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. One logical change per commit, English imperative messages.
- Work on branch `phase4-rerank-rewrite`; merge to `main` only after the author approves the evidence.
- Tick the ROADMAP §5 Phase 4 box (and the §10 reranker verification item) in the commit that completes it.

## Review Focus

1. **`topK` larger than the candidate count** (e.g. `topK = 50, rerank: true`): candidates become `topK`, all hits returned, none dropped. Test: `RetrievalPipelineTest.candidatesGrowToTopK` (Task 4).
2. **Reranker answers out of order, with fewer results, or with an unknown index:** results are mapped by `index`; a missing or out-of-range index is `reranker-unavailable`, never a silently shorter list. Tests: `LlamaCppRerankerTest.mapsResultsByIndex`, `.missingIndexIsUnavailable` (Task 3).
3. **Empty retrieval with `rerank: true`:** no reranker call, normal `LOW_RETRIEVAL_SCORE` refusal. Test: `RetrievalPipelineTest.emptyHitsSkipReranker` (Task 4), `AskRerankIT.emptyHitsRefuseWithoutReranker` (Task 5).
4. **Expired conversation or concurrent turns on one conversation:** an expired id gets the same `404` as an unknown one; two concurrent appends both survive in order of completion. Tests: `ConversationStoreTest.expiredIsNotFound`, `.concurrentAppendsKeepBothTurns` (Task 8).
5. **Rewrite model failure or a follow-up after a refused turn:** model exception → `503 generation-unavailable` (same as answering), no turn stored; a refused previous turn contributes only its question to the rewrite prompt. Tests: `AskConversationIT.rewriteFailureIs503AndStoresNothing`, `QueryRewriterTest.refusedTurnHasNoAnswerInPrompt` (Task 9).

---

## File Structure

```
compose.yaml                               (modified: reranker service, profile rerank)
scripts/fetch-reranker-models.sh           (new: download pinned GGUFs, check sha256)
scripts/rerank-parity/{pairs.yaml,parity.py,run.sh}  (new: §2 parity check, self-made text)
scripts/run-rerank.sh                      (new: Phase 4 retrieval runs A/B/C and sweep)
core/src/main/java/rag/core/chunk/TokenCounter        (modified: truncate)
core/src/main/java/rag/core/rerank/RerankOrder        (new: stable ordering, sigmoid)
app/src/main/java/rag/app/
  RagProperties                            (modified: Rerank, Conversation records; Ask.minRerankProbability)
  rerank/Reranker, LlamaCppReranker, RerankUnavailableException   (new)
  search/RetrievalPipeline                 (new) SearchRequest, SearchController, SearchResponse (modified)
  ask/AskService, AskController, AskRequest, AskResponse (modified)
  conversation/ConversationStore, Conversation, Turn (new)
  conversation/QueryRewriter               (new)
  web/ProblemTypes, ApiExceptionHandler    (modified: reranker-unavailable)
app/src/main/resources/prompts/rewrite.v1.st   (new)
eval-runner/src/main/java/rag/eval/
  SearchClient, AskClient, Sweep, EvalRunner, ReportWriter, RunMetadata (modified)
  MultiTurnSet, MultiTurnRun, MultiTurnReport (new)
```

---

### Task 1: Verification, reranker service and parity check

**Files:**
- Create: `scripts/fetch-reranker-models.sh`, `scripts/rerank-parity/pairs.yaml`, `scripts/rerank-parity/parity.py`, `scripts/rerank-parity/run.sh`
- Modify: `compose.yaml`, `.gitignore` (add `.cache/reranker/`), `docs/specs/04-rerank-rewrite.md` (§2 table), `README.md` (reranker setup, one short section)

**Interfaces:**
- Produces: a running reranker at `http://127.0.0.1:18090` answering `POST /v1/rerank {"query": str, "documents": [str], "top_n": int}` with `{"results": [{"index": int, "relevance_score": float}]}` (shape confirmed in Step 4; if it differs, record it and Task 3 follows the recorded shape). Env `RERANKER_MODEL_FILE` selects the GGUF.

- [ ] **Step 1: Pin the image.** Pick the latest llama.cpp `server` release tag at least two weeks old; record tag + multi-arch digest. Pin the two GGUFs by Hugging Face repo + commit revision + filename (prefer F16; MiniLM candidate `cstr/ms-marco-MiniLM-L-6-v2-GGUF`, bge candidate `gpustack/bge-reranker-v2-m3-GGUF`), and their sha256 after download.
- [ ] **Step 2: `fetch-reranker-models.sh`** downloads both files into `.cache/reranker/` by pinned revision URL and fails on a sha256 mismatch. Run it; expect two files and `OK` twice.
- [ ] **Step 3: compose service.** `reranker`: pinned image, `profiles: [rerank]`, command `-m /models/${RERANKER_MODEL_FILE} --reranking --ctx-size 512 --threads 2 --parallel 1 --host 0.0.0.0 --port 8080`, bind mount `./.cache/reranker:/models:ro`, ports `127.0.0.1:18090:8080`, limits `cpus: "2"`, `memory: ${RERANKER_MEMORY:-1G}`. Run `scripts/check-compose-limits.sh` → passes. `docker compose --profile rerank up -d reranker` (only this service; spec §10 item 1 option a).
- [ ] **Step 4: Response shape.** One `curl` with two self-made documents; record whether scores are raw logits and whether `index` is preserved, in spec §2.
- [ ] **Step 5: Parity check.** `pairs.yaml`: 5 self-made queries × 4 self-made passages each (fictional bank text, no SPM). `parity.py` scores them with `sentence-transformers` `CrossEncoder` (official weights, pinned `sentence-transformers` and `torch` versions) and with the running reranker, prints per-query Spearman, top-1 agreement and max |Δlogit|. `run.sh` runs `parity.py` in `python:3.12-slim@<digest>` with `--cpus 2 --memory 2g`, network to the reranker. Pass: top-1 identical on all 5 queries and Spearman ≥ 0.99. Run for MiniLM, then for bge-reranker-v2-m3 (restart reranker with `RERANKER_MODEL_FILE` and `RERANKER_MEMORY=3G`).
- [ ] **Step 6: If MiniLM fails parity** stop and report to the author before switching to the Python sidecar fallback (§3); do not switch silently.
- [ ] **Step 7: Record** image, digests, GGUF revisions and sha256, cold start, `docker stats` RSS, parity numbers in spec §2. Commit: `Add reranker service and parity check` (also commits this plan and the approved spec if not yet committed).

### Task 2: Core truncation and ordering

**Files:**
- Modify: `core/src/main/java/rag/core/chunk/TokenCounter.java`
- Create: `core/src/main/java/rag/core/rerank/RerankOrder.java`
- Test: `core/src/test/java/rag/core/chunk/TokenCounterTest.java`, `core/src/test/java/rag/core/rerank/RerankOrderTest.java`

**Interfaces:**
- Produces: `String TokenCounter.truncate(String text, int maxTokens)`; `static List<Integer> RerankOrder.order(List<Double> scores)` (indices, descending score, ties by ascending index); `static double RerankOrder.sigmoid(double logit)`.

- [ ] **Step 1: Failing tests.**
  - `truncateLeavesShortTextUnchanged`: text of 10 tokens, max 256 → identical string.
  - `truncateCutsToExactlyMaxTokens`: a 600-token self-made text → `count(result) == 256` and `text.startsWith(result)`.
  - `orderSortsDescendingWithStableTies`: `[0.1, 0.9, 0.9, -2.0]` → `[1, 2, 0, 3]`.
  - `sigmoidMapsLogits`: `sigmoid(0) == 0.5`, `sigmoid(10) > 0.9999`, `sigmoid(-10) < 0.0001`, monotonic on a grid.
- [ ] **Step 2:** `./gradlew :core:test --tests '*TokenCounterTest' --tests '*RerankOrderTest'` → FAIL (missing symbols).
- [ ] **Step 3: Implement.** `truncate` encodes ordinary, keeps the first `maxTokens` tokens and decodes; if the decoded text is not a prefix of the input (multi-byte split), drop tokens from the end until it is.
- [ ] **Step 4:** Same command → PASS.
- [ ] **Step 5: Commit** `Add token truncation and rerank ordering`.

### Task 3: Reranker client

**Files:**
- Create: `app/src/main/java/rag/app/rerank/Reranker.java`, `LlamaCppReranker.java`, `RerankUnavailableException.java`
- Modify: `RagProperties.java` (new `Rerank` record), `application.yaml`, `web/ProblemTypes.java` (`RERANKER_UNAVAILABLE = urn:rag:problem:reranker-unavailable`), `web/ApiExceptionHandler.java` (`503`)
- Test: `app/src/test/java/rag/app/rerank/LlamaCppRerankerTest.java`

**Interfaces:**
- Produces: `interface Reranker { List<Double> score(String query, List<String> passages); }` returning one raw score per passage, in input order. `RagProperties.Rerank(String url, int candidates, int maxPassageTokens, Duration timeout)`, YAML `rag.rerank.url: ${RERANKER_URL:http://127.0.0.1:18090}`, `candidates: 20`, `max-passage-tokens: 256`, `timeout: 5s`. `RagProperties` gets a `rerank` component with a non-null default.

- [ ] **Step 1: Failing tests** (stub HTTP server, as `OllamaEmbedderTest`):
  - `mapsResultsByIndex`: server returns results in order `[2, 0, 1]` → scores returned in input order.
  - `missingIndexIsUnavailable`: 3 passages, 2 results → `RerankUnavailableException`.
  - `outOfRangeIndexIsUnavailable`, `malformedBodyIsUnavailable`, `http500IsUnavailable`.
  - `timeoutIsUnavailable`: server sleeps past a 200 ms timeout → exception within 2 s.
  - `sendsTopNEqualToPassageCount`: request body has `top_n == passages.size()`.
- [ ] **Step 2:** `./gradlew :app:test --tests '*LlamaCppRerankerTest'` → FAIL.
- [ ] **Step 3: Implement** with Spring `RestClient` and a request factory carrying `timeout` as connect and read timeout.
- [ ] **Step 4:** Same command → PASS.
- [ ] **Step 5: Commit** `Add llama.cpp reranker client`.

### Task 4: Retrieval pipeline and `/v1/search` rerank

**Files:**
- Create: `app/src/main/java/rag/app/search/RetrievalPipeline.java`
- Modify: `SearchRequest.java` (`Options(int topK, Mode mode, boolean rerank)`, stop rejecting `rerank`), `SearchController.java`, `SearchResponse.java`
- Test: `app/src/test/java/rag/app/search/RetrievalPipelineTest.java`, `SearchControllerIT.java` (extend), `app/src/test/java/rag/app/support/StubReranker.java`, `TestBeans.java`

**Interfaces:**
- Consumes: `HybridSearch.searchWithDiagnostics`, `Reranker.score`, `TokenCounter.truncate`, `RerankOrder`.
- Produces: `RetrievalPipeline.Result retrieve(String query, int topK, Mode mode, boolean rerank, Principal principal)` with `record Result(List<SearchHit> hits, Double bestVectorSimilarity, Double bestRerankProbability, long retrievalMs, long rerankMs)`. Reranked hits: `score = rerankProbability`, `scores` gains `rerankScore`, `rerankProbability`, `fusedRank` (1-based). `SearchResponse.Diagnostics(Double bestVectorSimilarity, Double bestRerankProbability, Latency latencyMs)` with `record Latency(long retrieval, long rerank)`.

- [ ] **Step 1: Failing unit tests** (fake `HybridSearch` results, `StubReranker` returning fixed scores and recording calls):
  - `rerankFalseReturnsHybridResultUnchanged`: hits equal to `HybridSearch` output for `topK`, `bestRerankProbability == null`, reranker not called, `rerankMs == 0`.
  - `rerankReordersAndTruncatesToTopK`: 20 candidates, scores make candidate 7 best → first hit is candidate 7, `fusedRank == 8`, 5 hits returned.
  - `rerankRequestsTwentyCandidates`: `HybridSearch` asked for `topK = 20` when request `topK = 5`.
  - `candidatesGrowToTopK`: request `topK = 50` → `HybridSearch` asked for 50, 50 hits returned.
  - `passagesAreTruncatedTo256Tokens`: a 600-token candidate reaches the reranker with 256 tokens; the returned hit's `text` is the full text.
  - `emptyHitsSkipReranker`.
- [ ] **Step 2:** `./gradlew :app:test --tests '*RetrievalPipelineTest'` → FAIL.
- [ ] **Step 3: Implement** `RetrievalPipeline`; wire `SearchController` to it; diagnostics filled from `Result`.
- [ ] **Step 4: Failing IT** in `SearchControllerIT` (Testcontainers ES, `FakeEmbedder`, `StubReranker` bean):
  - `rerankTrueReordersByStubScores`.
  - `publicCallerRerankRequestHasNoInternalChunk`: index has `public` and `internal` chunks matching the query; a `public` key's reranker request contains only `public` chunk texts.
  - `rerankerDownIs503`: stub throws `RerankUnavailableException` → `503`, problem type `urn:rag:problem:reranker-unavailable`.
  - `rerankFalseResponseMatchesPhase3`: hits, scores and `bestVectorSimilarity` equal to a run through `HybridSearch` directly.
- [ ] **Step 5:** `./gradlew :app:test --tests '*SearchControllerIT' --tests '*RetrievalPipelineTest'` → PASS; full `./gradlew :app:test` → PASS.
- [ ] **Step 6: Commit** `Add optional rerank to the retrieval path`.

### Task 5: `/v1/ask` rerank and rerank gate

**Files:**
- Modify: `AskService.java` (use `RetrievalPipeline`), `AskController.java` (pass `rerank`), `RagProperties.Ask` (`minRerankProbability`), `application.yaml` (`min-rerank-probability: ${RAG_ASK_MIN_RERANK_PROBABILITY:0.0}`)
- Test: `app/src/test/java/rag/app/ask/AskRerankIT.java`

**Interfaces:**
- Consumes: `RetrievalPipeline.retrieve`.
- Produces: `AskService.ask(String question, int topK, Mode mode, boolean rerank, Principal principal)`; `latencyMs.rerank` populated.

- [ ] **Step 1: Failing ITs:**
  - `rerankGateRefusesWithoutCallingModel`: `min-rerank-probability = 0.9`, stub scores give best probability 0.5 → `REFUSED/LOW_RETRIEVAL_SCORE`, `StubChatModel.calls() == 0`.
  - `rerankGateIgnoredWhenRerankFalse`: same property, `rerank: false` → model called.
  - `answerPromptUsesRerankedOrder`: source `[1]` in the rendered prompt is the stub's top chunk.
  - `emptyHitsRefuseWithoutReranker`.
  - `latencyIncludesRerankStage`: `latencyMs.rerank >= 0` and present; `0` when `rerank: false`.
- [ ] **Step 2:** `./gradlew :app:test --tests '*AskRerankIT'` → FAIL.
- [ ] **Step 3: Implement.** The rerank gate runs after the existing similarity gate, only when `rerank` and `minRerankProbability > 0`; internal cause logged as for the similarity gate.
- [ ] **Step 4:** `./gradlew :app:test` → PASS (including unchanged `AskControllerIT`, `AskGateIT`).
- [ ] **Step 5: Commit** `Add rerank and a rerank-score gate to /v1/ask`.

### Task 6: Eval runner: rerank flag, repetitions, rerank sweep, run script

**Files:**
- Modify: `SearchClient.java` (`rerank` constructor flag; parse `bestRerankProbability` and `latencyMs`), `AskClient.java` (`rerank` flag), `Sweep.java` (`Signal` enum `VECTOR_SIMILARITY | RERANK_PROBABILITY`), `EvalRunner.java` (`--rerank=true`, `--repeat=N`), `ReportWriter.java`, `RunMetadata.java` (records `rerank`, `repeat`, reranker model from `RERANKER_MODEL_FILE` passed as `--reranker-model`)
- Create: `scripts/run-rerank.sh`
- Test: `SearchClientTest`, `SweepTest`, `ReportWriterTest`, `EvalRunnerTest` (extend)

**Interfaces:**
- Produces: `SearchClient(URI base, String apiKey, String mode, boolean rerank)`; `SearchResult` gains `Double bestRerankProbability, Long serverRetrievalMs, Long serverRerankMs`. `ItemResult` gains `List<Long> latenciesMs, List<Long> serverRerankMs` (one per repetition; scores from repetition 1). Report shows p50/p95 of client total, server `retrieval`, server `rerank`, server `retrieval + rerank`, over all repetitions, plus repetition 1 alone as "cold".

- [ ] **Step 1: Failing tests:**
  - `SearchClientTest.sendsRerankFlagAndParsesDiagnostics`.
  - `SweepTest.rerankSignalUsesRerankProbability`: observations with probabilities, table rows computed on them; `noSignal` counts nulls.
  - `ReportWriterTest.reportsLatencyOverAllRepetitionsAndColdRun`: 2 items × 3 repetitions → p95 over 6 values; cold p95 over 2.
  - `EvalRunnerTest.repeatScoresFirstRepetitionOnly`: stub server returns different hits on repetition 2 → scores from repetition 1.
- [ ] **Step 2:** `./gradlew :eval-runner:test` → FAIL.
- [ ] **Step 3: Implement.** `run-rerank.sh [--dry-run] {none|minilm|bge} [--split=…] [--sweep]` mirrors `run-generation.sh` (clean tree check, structural nomic index, port 18082), starts the app with `RERANKER_URL`, runs `--repeat=3`, and refuses `minilm|bge` if the reranker container is not serving the matching `RERANKER_MODEL_FILE`.
- [ ] **Step 4:** `./gradlew :eval-runner:test` → PASS; `scripts/run-rerank.sh --dry-run minilm` prints the commands.
- [ ] **Step 5: Commit** `Add rerank runs and rerank-score sweep to the eval runner`.

### Task 7: Rerank runs and evidence

**Files:**
- Create: `evidence/phase4-rerank.md`
- Draft only (scratchpad): `decisions/04-rerank.md`

- [ ] **Step 1:** Clean tree. Runs A (`none`), B (`minilm`), C (`bge`, `RERANKER_MEMORY=3G`), each `--split=dev` then `--split=test`, `--repeat=3`. Then `--sweep` for B on `dev` (and C if B's gate looks unusable).
- [ ] **Step 2: Write `evidence/phase4-rerank.md`** per §9 from the run directories: configuration, parity, Recall@5/MRR/nDCG@10 per category × split for A/B/C, latency table, sweep table, the headline sentence (test Recall@5 A→B, p95 of server `retrieval + rerank` A→B), observations from numbers only, `n` everywhere.
- [ ] **Step 3: Draft `decisions/04-rerank.md`** (options: adopt MiniLM / adopt bge / keep off; gate value) in the scratchpad and hand it to the author with the evidence. Wait for the author's decision.
- [ ] **Step 4:** If the author adopts a default, set `rag.rerank` default and gate value as decided (one commit). Commit evidence: `Record Phase 4 rerank results`. Tick ROADMAP §5 rerank and rerank-threshold boxes in that commit only if the author's decision is committed.

### Task 8: Conversation store

**Files:**
- Create: `app/src/main/java/rag/app/conversation/ConversationStore.java`, `Conversation.java`, `Turn.java`
- Modify: `RagProperties.java` (`Conversation(Duration ttl, int maxTurns, int maxConversations)`), `application.yaml` (`ttl: 30m`, `max-turns: 5`, `max-conversations: 1000`)
- Test: `app/src/test/java/rag/app/conversation/ConversationStoreTest.java`

**Interfaces:**
- Produces: `record Turn(String question, String rewrittenQuery, String status, String answer)`; `ConversationStore(RagProperties, java.time.Clock)`; `String create(Principal p)`; `Optional<List<Turn>> history(String id, Principal p)` (empty for unknown, expired, foreign principal or changed clearance); `void append(String id, Principal p, Turn t)` (no-op if not found). Ids `cv_` + monotonic ULID.

- [ ] **Step 1: Failing tests** (fake `Clock`):
  - `createdIsEmptyHistory`; `appendThenHistoryInOrder`.
  - `keepsOnlyLastFiveTurns`: 7 appends → turns 3..7.
  - `expiredIsNotFound`: advance 31 min idle → empty; a read inside 30 min refreshes idle time.
  - `foreignPrincipalIsNotFound`, `changedClearanceIsNotFound`.
  - `evictsOldestBeyondMaxConversations`: max 2, create 3 → the first is not found.
  - `concurrentAppendsKeepBothTurns`: two threads append to one id → 2 turns.
- [ ] **Step 2:** `./gradlew :app:test --tests '*ConversationStoreTest'` → FAIL.
- [ ] **Step 3: Implement** with a `LinkedHashMap` in access order guarded by one lock (small, bounded map; simplicity over throughput). A `Clock` bean defaults to `Clock.systemUTC()`.
- [ ] **Step 4:** Same command → PASS.
- [ ] **Step 5: Commit** `Add in-memory conversation store`.

### Task 9: Query rewriting and multi-turn `/v1/ask`

**Files:**
- Create: `app/src/main/java/rag/app/conversation/QueryRewriter.java`, `app/src/main/resources/prompts/rewrite.v1.st`
- Modify: `AskService.java`, `AskController.java` (accept `conversationId`), `AskRequest.java` (javadoc), `AskResponse.java` (`conversationId`, `rewrittenQuery`; `Latency` gains `rewrite`), `README.md` (conversations are in memory and lost on restart)
- Test: `app/src/test/java/rag/app/conversation/QueryRewriterTest.java`, `app/src/test/java/rag/app/ask/AskConversationIT.java`

**Interfaces:**
- Consumes: `ConversationStore`, `ChatModel`, `TokenCounter.truncate`.
- Produces: `QueryRewriter.Rewrite rewrite(List<Turn> history, String followUp)` with `record Rewrite(String query, boolean fellBack, Integer promptTokens, Integer completionTokens, long latencyMs)`. `AskResponse` = Phase 3 fields + `conversationId`, `rewrittenQuery`; `Latency(long retrieval, long rerank, long generation, long rewrite)`; `usage` sums rewrite + answer calls (null components stay null only if both are null).

- [ ] **Step 1: Failing unit tests** (`StubChatModel`):
  - `historyIsFencedAsData`: rendered prompt wraps each turn in `<<<TURN n>>>…<<<END TURN n>>>`, neutralises those markers inside history text (as `answer.v2` does), places the follow-up after the turns.
  - `answersAreTruncatedTo300Tokens`.
  - `refusedTurnHasNoAnswerInPrompt`.
  - `blankOrMultiLineOrTooLongFallsBack`: replies `""`, `"a\nb"`, 513 chars → `query == followUp`, `fellBack == true`.
  - `usesTemperatureZeroAndMaxTokens128`.
- [ ] **Step 2:** `./gradlew :app:test --tests '*QueryRewriterTest'` → FAIL.
- [ ] **Step 3: Implement** `QueryRewriter` and `rewrite.v1.st` with the instructions of §6.2.
- [ ] **Step 4:** Same → PASS.
- [ ] **Step 5: Failing ITs:**
  - `firstTurnCreatesConversationAndMakesOneModelCall`: response has `conversationId` starting `cv_`, `rewrittenQuery == null`, `calls() == 1`.
  - `followUpRewritesAndRetrievesWithRewrittenQuery`: stub replies with a rewrite then an answer; `calls() == 2`, `rewrittenQuery` equals the stub rewrite, the answer prompt's question is the rewritten query and contains no history.
  - `foreignConversationIdIs404LikeUnknown`: body of a foreign id equals body of `cv_unknown`.
  - `rewriteFailureIs503AndStoresNothing`: stub fails the rewrite call → `503`; next follow-up sees the same history as before.
  - `refusedTurnIsStoredWithoutAnswer`.
  - `usageSumsRewriteAndAnswer`.
- [ ] **Step 6:** `./gradlew :app:test` → PASS.
- [ ] **Step 7: Commit** `Add conversations and query rewriting to /v1/ask`.

### Task 10: Multi-turn eval mode

**Files:**
- Create: `eval-runner/src/main/java/rag/eval/MultiTurnSet.java`, `MultiTurnRun.java`, `MultiTurnReport.java`; `scripts/run-multi-turn.sh`
- Modify: `AskClient.java` (`conversationId` in and out, `rewrittenQuery`), `EvalRunner.java` (`--multi-turn=true --multi-turn-file=eval/multi-turn.yaml`)
- Test: `MultiTurnSetTest`, `MultiTurnRunTest`, `MultiTurnReportTest`
- Draft only (scratchpad): `eval/multi-turn.yaml`

**Interfaces:**
- Produces: `record MultiTurnItem(String id, String split, List<String> turns, String followUp, String standaloneGoldenId, List<ExpectedSource> expectedSources, Clearance minClearance)`; `MultiTurnSet.load(Path, Map<String, GoldenItem> golden)` validates that `turns` and `standaloneGoldenId` exist in the golden set. For each item `MultiTurnRun` produces Recall@5 / MRR for `raw`, `rewrite`, `standalone`. For `raw` and `standalone` it calls `/v1/search`; for `rewrite` it asks turn 1 via `/v1/ask` with a new conversation, then asks the follow-up with that `conversationId` and scores `/v1/search` on the returned `rewrittenQuery`, so all three variants are scored with the same search call shape.

- [ ] **Step 1: Failing tests:** loader rejects an unknown golden id and a missing `followUp`; run scores three variants from a stub server; the report contains item ids, variant and scores only (assert that no follow-up text and no rewritten query text appears in `report.md`); rewritten queries go to `rewrites.jsonl` in the run dir.
- [ ] **Step 2:** `./gradlew :eval-runner:test` → FAIL.
- [ ] **Step 3: Implement.** `standaloneGoldenId` is the golden item whose question is the standalone form of the follow-up; its `expectedSources` default the item's `expectedSources` when the latter is omitted.
- [ ] **Step 4:** `./gradlew :eval-runner:test` → PASS.
- [ ] **Step 5: Commit** `Add multi-turn eval mode`.
- [ ] **Step 6: Draft `eval/multi-turn.yaml`** (about 12 items, 8 dev / 4 test, built from golden pairs; follow-ups must be ambiguous without context) in the scratchpad and give it to the author to review and commit.

### Task 11: Multi-turn run and evidence

**Files:** Create `evidence/phase4-rewrite.md`.

- [ ] **Step 1:** After the author commits `eval/multi-turn.yaml`: `scripts/run-multi-turn.sh --split=dev`, then `--split=test` once.
- [ ] **Step 2:** Write the report per §9 (raw / rewrite / standalone Recall@5 and MRR per split with `n`; tokens and latency added by rewrite from `usage` and `latencyMs.rewrite`; fallbacks and failures by item id).
- [ ] **Step 3: Commit** `Record Phase 4 rewrite results`, ticking the ROADMAP §5 `conversationId` box.

### Task 12: Optional generation check and close-out

- [ ] **Step 1 (optional, §7.3):** if rerank was adopted, `scripts/run-generation.sh generate --split=dev` with rerank on (add a `--rerank` passthrough to that script in the same commit); add a section to `evidence/phase4-rerank.md`. If skipped, state it there.
- [ ] **Step 2:** Tick ROADMAP Progress Phase 4 and the §10 "arm64 reranker approach" item; commit `Mark Phase 4 complete in the roadmap`.
- [ ] **Step 3:** Whole-branch review, then hand to the author for merge.
