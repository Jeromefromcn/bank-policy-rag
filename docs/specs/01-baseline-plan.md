# Phase 1 Naive Baseline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A working naive RAG baseline — ingest SPM PDFs and Example Bank Markdown with fixed-length chunks into Elasticsearch, serve `/v1/search` (VECTOR) and `/v1/ask`, and measure retrieval with an eval runner whose output becomes `evidence/phase1-baseline.md`.

**Architecture:** Three Gradle modules. `core` is plain Java (parsing, chunking, IDs, corpus versioning, golden set, metrics) with no Spring and no ES dependency. `app` is Spring Boot 4.1.2 and owns all I/O (ES, Ollama, DeepSeek, HTTP, ingest CLI). `eval-runner` is a plain Java CLI that calls `app` over HTTP with a real API key and uses `core` for metrics.

**Tech Stack:** Java 25, Gradle 9.9.0 (Kotlin DSL), Spring Boot 4.1.2, Spring AI 2.0.2 (DeepSeek chat, Ollama embedding), elasticsearch-java 9.5.6, PDFBox 3.0.8, jtokkit 1.1.0, SnakeYAML 2.6, Jackson 3 (Boot-managed), JUnit Jupiter 6 / AssertJ / Testcontainers 2.0.5 (Boot-managed).

**Spec:** `docs/specs/01-baseline.md` (with `docs/specs/00-overall-design.md` and `ROADMAP.md`). Section references below (`§3.4`) are to spec 01 unless prefixed.

## Global Constraints

- All repo content in English. Diagrams in Mermaid only.
- **No SPM text anywhere in git**: no PDFs, no extracted/chunked text, no HKMA URLs. Test fixtures use self-made text; the test PDF is generated in-test with PDFBox.
- Every container has CPU and memory limits (compose and Testcontainers). Testcontainers ES: `ES_JAVA_OPTS=-Xms512m -Xmx512m`, memory 1.5 GB, nanoCPUs 2 cores.
- Pin every dependency version and image tag. ES image everywhere: `elasticsearch:9.5.5@sha256:a418cab5c89773ab827d1d146e6c95a3658226216d8d934d404c33e7a09a147d`. Ollama: `ollama/ollama:0.40.15@sha256:0e59999e0e8f9fd4e704bd788a89b7e4cad399367fb1365f8c102ac662288761`.
- No secrets in git. Secrets come from `.env` / env vars only.
- Permission filtering is an ES pre-filter (kNN `filter`), never a post-filter. `Principal` is a required parameter of every retriever method — no overload without it.
- Every resource limit, threshold, model id and JVM flag is a property.
- Eval numbers only from real runner output.
- TDD; one logical change per commit; English imperative commit messages ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Tick the matching `ROADMAP.md` §5 Phase 1 box in the commit that completes it (named per task below).
- Java base package `rag` (`rag.core`, `rag.app`, `rag.eval`).
- Test commands: `./gradlew :<module>:test --tests '<Class>'`. Integration tests in `app` need Docker.

## Review Focus

1. **SPM PDF whose `Structure` list or body restart can't be found** → ingest fails naming the file; it must not silently attribute every chunk to an empty section (which would quietly zero recall). Test: `SpmSectionMarkersTest.failsWhenBodyStartNotFound` (Task 3).
2. **A single "word" longer than 512 tokens** (table row or long identifier without spaces) → chunker emits it as its own oversize chunk and keeps progressing; no infinite loop, no dropped text. Test: `FixedChunkerTest.oversizeWordBecomesItsOwnChunk` (Task 5).
3. **Blank `query`, `topK` 0 or 51, missing body** → `400` problem+json, never `500`. Test: `SearchControllerIT.rejectsInvalidRequests` (Task 11).
4. **Ollama down or model not pulled at query time** → `503` problem+json (`urn:rag:problem:embedding-unavailable`) within the configured timeout, not a hang. Test: `SearchControllerIT.embeddingFailureReturns503` (Task 11).
5. **Golden-set typo: an expected `docId` not in the corpus** → eval runner fails before running, naming the item. Test: `GoldenSetTest.rejectsUnknownDocId` (Task 7).

---

## File Structure

```
settings.gradle.kts, build.gradle.kts, gradle/wrapper/*, gradlew, gradlew.bat
compose.yaml
scripts/check-compose-limits.sh, scripts/tests/test-check-compose-limits.sh
core/src/main/java/rag/core/
  model/   Clearance, SectionPath, SourceType, PageSpan, SectionMarker, ParsedDocument
  parse/   HeaderFooterStripper, PdfParser, SpmSectionMarkers, MarkdownParser
  chunk/   TokenCounter, Chunk, FixedChunker
  ids/     Hashes
  corpus/  Manifest, CorpusVersion, CorpusLoader
  eval/    GoldenItem, GoldenSet, RankedHit, SourceMatcher, RetrievalMetrics
app/src/main/java/rag/app/
  RagApplication, RagProperties
  security/ Principal, ApiKeyAuthFilter, SecurityConfig
  web/      ProblemTypes, ApiExceptionHandler
  embed/    Embedder, OllamaEmbedder, EmbeddingCache, EmbeddingUnavailableException
  es/       IndexNames, ChunkIndexAdmin, ChunkDocument, ChunkWriter
  ingest/   IngestCommand
  search/   VectorRetriever, SearchHit, SearchController, SearchRequest, SearchResponse
  ask/      PromptRenderer, AskService, AskController, AskRequest, AskResponse
  info/     IndexInfoContributor
app/src/main/resources/ application.yaml, application-prod.yaml, prompts/answer.v1.st
app/src/test/java/rag/app/support/ EsTestContainer, FakeEmbedder, StubChatModel
eval-runner/src/main/java/rag/eval/ EvalRunner, SearchClient, RunMetadata, ReportWriter
evidence/phase1-baseline.md
```

---

### Task 1: Gradle skeleton and core model types

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `core/build.gradle.kts`, `app/build.gradle.kts`, `eval-runner/build.gradle.kts`, Gradle wrapper files
- Create: `core/src/main/java/rag/core/model/{Clearance,SectionPath,SourceType}.java`
- Test: `core/src/test/java/rag/core/model/{ClearanceTest,SectionPathTest}.java`

**Interfaces:**
- Produces:
  - `enum Clearance { PUBLIC, INTERNAL, RESTRICTED; static Clearance parse(String lower); Set<Clearance> visible(); String wire() /* lower-case */ }`
  - `record SectionPath(List<String> components) implements Comparable<SectionPath>`: `static SectionPath parse(String dotted)` (`"3.2.1"` → `["3","3.2","3.2.1"]` chain; `""` → empty), `static SectionPath empty()`, `boolean isEmpty()`, `String leaf()`, `boolean isPrefixOf(SectionPath other)` (component-wise), `compareTo` numeric component-wise on the leaf.
  - `enum SourceType { REGULATORY, INTERNAL, ADVERSARIAL; static SourceType parse(String); String wire() }`

- [ ] **Step 1: Generate the wrapper.** Gradle is not installed. Download `https://services.gradle.org/distributions/gradle-9.9.0-bin.zip` and its `.sha256` into the scratchpad, verify, unzip, run `gradle wrapper --gradle-version 9.9.0 --distribution-type bin` in the repo. Commit `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.{jar,properties}` (`distributionSha256Sum` set).
- [ ] **Step 2: Write build files.** Root: Java toolchain 25 for all subprojects, `mavenCentral()`, JUnit Platform. Versions: Spring Boot Gradle plugin `4.1.2`; every module imports `platform("org.springframework.boot:spring-boot-dependencies:4.1.2")` (BOM only — `core` gets no Spring code). `app` also imports `platform("org.springframework.ai:spring-ai-bom:2.0.2")` and declares `co.elastic.clients:elasticsearch-java:9.5.6` explicitly (Boot manages 9.4.8; highest wins). `core`: `org.apache.pdfbox:pdfbox:3.0.8`, `com.knuddels:jtokkit:1.1.0`, `org.yaml:snakeyaml`. `app` `bootRun { jvmArgs(providers.gradleProperty("appJvmArgs").getOrElse("-Xmx1g").split(" ")) }`. `eval-runner`: `application` plugin, mainClass `rag.eval.EvalRunner`, depends on `:core`, Jackson 3 databind.
- [ ] **Step 3: Write failing tests**

```java
@Test void visibleIsCumulative() {
  assertThat(Clearance.INTERNAL.visible()).containsExactlyInAnyOrder(PUBLIC, INTERNAL);
  assertThat(Clearance.PUBLIC.visible()).containsExactly(PUBLIC);
}
@Test void parseRejectsUnknown() { assertThatThrownBy(() -> Clearance.parse("secret")).isInstanceOf(IllegalArgumentException.class); }

@Test void parseBuildsChain() { assertThat(SectionPath.parse("3.2.1").components()).containsExactly("3", "3.2", "3.2.1"); }
@Test void prefixIsComponentWise() {
  assertThat(SectionPath.parse("3.2").isPrefixOf(SectionPath.parse("3.2.1"))).isTrue();
  assertThat(SectionPath.parse("3.2").isPrefixOf(SectionPath.parse("3.20"))).isFalse();
  assertThat(SectionPath.parse("3.2").isPrefixOf(SectionPath.parse("3.2"))).isTrue();
  assertThat(SectionPath.empty().isPrefixOf(SectionPath.parse("1"))).isFalse(); // empty matches nothing
}
@Test void compareIsNumeric() { assertThat(SectionPath.parse("2.10")).isGreaterThan(SectionPath.parse("2.9")); }
```

- [ ] **Step 4: Run** `./gradlew :core:test` — Expected: FAIL (classes missing).
- [ ] **Step 5: Implement** the three types.
- [ ] **Step 6: Run** `./gradlew :core:test` and `./gradlew build` — Expected: PASS, all three modules compile.
- [ ] **Step 7: Commit** `Add Gradle multi-module skeleton and core model types`

---

### Task 2: compose.yaml and limits check

**Files:**
- Create: `compose.yaml`, `scripts/check-compose-limits.sh`, `scripts/tests/test-check-compose-limits.sh`
- Modify: `ROADMAP.md` (tick "Local environment: `compose.yaml`…")

- [ ] **Step 1: Write the failing test** `scripts/tests/test-check-compose-limits.sh` in the style of `test-verify-corpus.sh` (`check name want got out needle`). Cases, each a compose file written to a temp dir:
  - `all limited` → exit 0
  - `missing memory` on one service → exit 1, output names the service and `memory`
  - `missing cpus` → exit 1, names `cpus`
  - `limits under a profile-only service` still checked (service with `profiles: [x]` and no limits → exit 1)
- [ ] **Step 2: Run** `bash scripts/tests/test-check-compose-limits.sh` — Expected: FAIL (script missing).
- [ ] **Step 3: Implement** `check-compose-limits.sh [compose-file]` (default `compose.yaml`): `docker compose -f F --profile '*' config --format json | jq` over `.services`, report every service lacking `.deploy.resources.limits.cpus` or `.memory`, exit 1 if any.
- [ ] **Step 4: Write `compose.yaml`**: services `elasticsearch` and `ollama` mirroring the shared stacks (images per Global Constraints; ES `cpus: "2"`, `memory: 3G`, `ES_JAVA_OPTS=-Xms1536m -Xmx1536m`, `discovery.type=single-node`, `xpack.security.enabled=true`, http/transport SSL off, `ELASTIC_PASSWORD=${ES_PASSWORD}`, port `127.0.0.1:9200:9200`; Ollama `cpus: "3"`, `memory: 2.5G`, `OLLAMA_MAX_LOADED_MODELS=1`, `OLLAMA_NUM_PARALLEL=1`, `127.0.0.1:11434:11434`; named volumes). Header comment: on the author's server the shared stacks are used instead (decisions/01 Decision 5).
- [ ] **Step 5: Run** the test script and `scripts/check-compose-limits.sh` — Expected: all `ok`, exit 0.
- [ ] **Step 6: Commit** `Add compose.yaml and compose limits check` (with the ROADMAP tick).

---

### Task 3: PDF parsing — header/footer stripping and SPM section markers

**Files:**
- Create: `core/.../model/{PageSpan,SectionMarker,ParsedDocument}.java`, `core/.../parse/{HeaderFooterStripper,PdfParser,SpmSectionMarkers}.java`
- Test: `core/src/test/java/rag/core/parse/{HeaderFooterStripperTest,SpmSectionMarkersTest,PdfParserTest}.java`, `core/src/test/java/rag/core/parse/TestPdfs.java` (builds PDFs in-test)

**Interfaces:**
- Produces:
  - `record PageSpan(int page /*1-based*/, int start, int end /*exclusive char offsets into ParsedDocument.text*/)`
  - `record SectionMarker(int offset, SectionPath path)`
  - `record ParsedDocument(String docId, String title, String docVersion, SourceType sourceType, Clearance classification, String text, List<PageSpan> pages, List<SectionMarker> markers)`
  - `HeaderFooterStripper.strip(List<List<String>> pagesOfLines) -> List<List<String>>` — zone = first 3 / last 3 non-blank lines; remove a zone line if its `\d+`→`#` form occurs in that zone on ≥ 60% of pages; remove a line that is only digits in the last-3 zone.
  - `SpmSectionMarkers.find(String text) -> List<SectionMarker>`; throws `IllegalStateException("…body start not found…")` if no `Structure` line or no second `1.` after it.
  - `PdfParser.parse(Path pdf, String docId, String title, String docVersion) -> ParsedDocument` (sourceType `REGULATORY`, classification `PUBLIC`; pages joined with `\n`).

- [ ] **Step 1: Write failing tests**

```java
// HeaderFooterStripperTest
@Test void removesRecurringHeaderAndPageNumbers() {
  var pages = List.of(
    List.of("Policy Manual", "AB-1   Sample Module   V.1 - 01.01.2020", "1. Intro", "text a", "1"),
    List.of("Policy Manual", "AB-1   Sample Module   V.1 - 01.01.2020", "text b", "2"),
    List.of("Policy Manual", "AB-1   Sample Module   V.1 - 01.01.2020", "text c", "3"));
  assertThat(HeaderFooterStripper.strip(pages)).containsExactly(
    List.of("1. Intro", "text a"), List.of("text b"), List.of("text c"));
}
@Test void keepsBodyLineThatAppearsOnFewPages() { /* "Note:" first line on 1 of 3 pages → kept */ }

// SpmSectionMarkersTest — text is self-made
static final String DOC = """
    Purpose
      Self-made purpose text.
    Structure
      1.  Introduction
          1.1  Background
      2.  Controls
    1.  Introduction
        1.1  Background text here.
        1.1.1  Detail text.
    1 This is a footnote line.
    2.  Controls
        2.1  Control text.
        1.5  wrapped line starting with a number
    """;
@Test void markersStartAtBodyAndFollowNumbering() {
  assertThat(SpmSectionMarkers.find(DOC)).extracting(m -> m.path().leaf())
      .containsExactly("1", "1.1", "1.1.1", "2", "2.1");   // footnote "1 This" and non-monotonic "1.5" rejected
}
@Test void markerOffsetsPointAtLineStart() { /* offset of "2.1" marker == DOC.indexOf("2.1  Control") minus leading spaces? → assert DOC.substring(offset).stripLeading().startsWith("2.1") */ }
@Test void failsWhenBodyStartNotFound() {
  assertThatThrownBy(() -> SpmSectionMarkers.find("Structure\n 1. Only toc\n"))
      .isInstanceOf(IllegalStateException.class).hasMessageContaining("body start");
}

// PdfParserTest — TestPdfs.sample(Path) writes a 3-page PDF with PDFBox (Standard 14 Helvetica):
// each page has the two header lines and a page-number footer; page 1 has Purpose/Structure/body start.
@Test void parsesPagesWithoutHeadersAndRecordsSpans() {
  var doc = PdfParser.parse(TestPdfs.sample(tmp), "test-ab-1", "Sample Module", "V.1");
  assertThat(doc.text()).doesNotContain("Policy Manual").contains("Control text.");
  assertThat(doc.pages()).extracting(PageSpan::page).containsExactly(1, 2, 3);
  assertThat(doc.pages().get(2).end()).isEqualTo(doc.text().length());
  assertThat(doc.markers()).isNotEmpty();
}
```

- [ ] **Step 2: Run** `./gradlew :core:test --tests 'rag.core.parse.*'` — Expected: FAIL.
- [ ] **Step 3: Implement.** Marker regex `^\s*(\d+\.|\d+(?:\.\d+)+)\s+\S` (multiline), strip trailing `.` before `SectionPath.parse`. Body start = second line matching `^\s*1\.\s` after the first line equal (trimmed) to `Structure`; scan for markers only from there. Monotonic guard: accept a candidate only if `path.compareTo(lastAccepted) > 0` (`compareTo` from Task 1). PdfParser: `PDFTextStripper` with `setSortByPosition(true)`, `setStartPage/EndPage` per page, split lines, `HeaderFooterStripper.strip`, join.
- [ ] **Step 4: Run** the same tests — Expected: PASS.
- [ ] **Step 5: Verify locally against the real PDFs (not committed).** A throwaway JShell/scratch main under the scratchpad calling `PdfParser.parse` on each `corpus/raw/*.pdf`: print per module the page count, first 10 marker leaves, and any header line that survived. Expected: no exception, first markers `1, 1.1, …`, no `Supervisory Policy Manual` in text. Record the counts in the task's commit message body; print no SPM text into any file in the repo.
- [ ] **Step 6: Commit** `Add PDF parsing with header stripping and SPM section markers`

---

### Task 4: Markdown parsing

**Files:**
- Create: `core/.../parse/MarkdownParser.java`
- Test: `core/src/test/java/rag/core/parse/MarkdownParserTest.java`

**Interfaces:**
- Produces: `MarkdownParser.parse(String content, String sourceName) -> ParsedDocument` (pages empty; markers from headings `^(#{1,6})\s+(\d+(?:\.\d+)*)\s+\S` → `SectionPath.parse(number)`; the heading line's offset is the marker offset). Text = body after front matter.

- [ ] **Step 1: Write failing tests**

```java
static final String MD = """
    ---
    docId: eb-sample
    title: Sample Standard
    version: "1.0"
    classification: internal
    sourceType: internal
    fictional: true
    ---
    *Fictional.*
    # Sample Standard
    ## 1 Scope
    1.1 Numbered paragraph, not a section.
    ## 2 Rules
    ### 2.1 Detail
    """;
@Test void readsFrontMatter() { var d = MarkdownParser.parse(MD, "x.md");
  assertThat(d.docId()).isEqualTo("eb-sample"); assertThat(d.classification()).isEqualTo(Clearance.INTERNAL);
  assertThat(d.sourceType()).isEqualTo(SourceType.INTERNAL); assertThat(d.text()).doesNotContain("docId:"); }
@Test void headingNumbersAreMarkers() { assertThat(MarkdownParser.parse(MD, "x.md").markers())
  .extracting(m -> m.path().leaf()).containsExactly("1", "2", "2.1"); }
@Test void crlfLineEndingsParse() { assertThat(MarkdownParser.parse(MD.replace("\n", "\r\n"), "x.md").markers()).hasSize(3); }
@Test void missingClassificationFails() { assertThatThrownBy(() -> MarkdownParser.parse(MD.replace("classification: internal\n", ""), "x.md"))
  .hasMessageContaining("classification").hasMessageContaining("x.md"); }
@Test void unknownClassificationFails() { /* "classification: secret" → message contains "secret" */ }
@Test void missingFrontMatterFails() { /* content without leading --- */ }
```

- [ ] **Step 2: Run** `./gradlew :core:test --tests 'rag.core.parse.MarkdownParserTest'` — Expected: FAIL.
- [ ] **Step 3: Implement** (normalise `\r\n` → `\n` first; SnakeYAML `SafeLoader`; required keys `docId,title,version,classification,sourceType`).
- [ ] **Step 4: Run** — Expected: PASS. Also run a scratch check parsing every file in `corpus/internal` and `corpus/adversarial`: all parse, each has ≥ 1 marker.
- [ ] **Step 5: Commit** `Add Markdown parsing with front matter and heading markers`

---

### Task 5: Fixed chunker and deterministic IDs

**Files:**
- Create: `core/.../chunk/{TokenCounter,Chunk,FixedChunker}.java`, `core/.../ids/Hashes.java`
- Test: `core/src/test/java/rag/core/chunk/FixedChunkerTest.java`, `core/src/test/java/rag/core/ids/HashesTest.java`

**Interfaces:**
- Produces:
  - `TokenCounter.cl100k()`, `int count(String)` (jtokkit `EncodingType.CL100K_BASE`, `countTokensOrdinary`).
  - `Hashes.sha256Hex(String) / sha256Hex(byte[])`, `Hashes.sectionId(String docId, SectionPath p)` = first 16 hex of `sha256(docId + "#" + String.join(".", leaf))` — leaf string, empty path → `""`; `Hashes.chunkId(String sectionId, int ordinal, String chunkerVersion)` = first 16 hex of `sha256(sectionId + ordinal + chunkerVersion)`.
  - `record Chunk(String chunkId, String docId, String sectionId, int ordinal, String content, String contentHash, int tokenCount, int start, int end, List<Integer> pages, SectionPath sectionPath, List<String> coveredSections /*leaf strings, document order, distinct*/)`
  - `FixedChunker(TokenCounter tc, int maxTokens, int overlapTokens)`, `String version()` → `"fixed-" + max + "-" + overlap + "-cl100k"`, `List<Chunk> chunk(ParsedDocument doc)`. `ordinal` is the chunk's index within the document (fixed chunks have no real section parent).

- [ ] **Step 1: Write failing tests** (self-made text; build docs with a helper `doc(text, markers, pages)`)

```java
@Test void windowsRespectMaxAndOverlap() {   // 2000 words "w0 w1 …", max 50, overlap 10
  var chunks = new FixedChunker(tc, 50, 10).chunk(doc);
  assertThat(chunks).allSatisfy(c -> assertThat(c.tokenCount()).isLessThanOrEqualTo(50));
  for (int i = 1; i < chunks.size(); i++) {
    var prev = chunks.get(i - 1); var cur = chunks.get(i);
    assertThat(cur.start()).isLessThan(prev.end());                                   // overlaps
    assertThat(tc.count(doc.text().substring(cur.start(), prev.end()))).isGreaterThanOrEqualTo(10);
  }
  assertThat(chunks.getLast().end()).isEqualTo(doc.text().stripTrailing().length());   // nothing dropped
}
@Test void contentIsExactSourceSlice() { chunks.forEach(c -> assertThat(c.content()).isEqualTo(doc.text().substring(c.start(), c.end()))); }
@Test void sectionAttribution() {   // markers at "1" offset 0, "1.1" mid-chunk-0, "2" inside chunk 1
  assertThat(chunks.get(0).sectionPath().leaf()).isEqualTo("1");
  assertThat(chunks.get(0).coveredSections()).containsExactly("1", "1.1");
  assertThat(chunks.get(1).sectionPath().leaf()).isEqualTo("1.1");   // in effect at its start
  assertThat(chunks.get(1).coveredSections()).containsExactly("1.1", "2");
}
@Test void textBeforeFirstMarkerHasEmptySection() { /* first chunk before any marker → sectionPath empty, coveredSections starts with the first marker it contains, else [] */ }
@Test void pagesAreOverlappingSpans() { /* chunk spanning page 1→2 boundary → pages [1,2]; Markdown doc → [] */ }
@Test void oversizeWordBecomesItsOwnChunk() {   // a 3000-char token-dense word between normal words, max 50
  var chunks = new FixedChunker(tc, 50, 10).chunk(docWithHugeWord);
  assertThat(chunks).anySatisfy(c -> assertThat(c.content()).isEqualTo(hugeWord));
  assertThat(chunks.getLast().end()).isEqualTo(docWithHugeWord.text().stripTrailing().length());
}
@Test void deterministic() { assertThat(chunker.chunk(doc)).isEqualTo(chunker.chunk(doc)); }
@Test void versionString() { assertThat(new FixedChunker(tc, 512, 64).version()).isEqualTo("fixed-512-64-cl100k"); }

// HashesTest
@Test void idsAreSixteenHexAndStable() { assertThat(Hashes.chunkId("abc", 0, "v")).hasSize(16).matches("[0-9a-f]+")
  .isEqualTo(Hashes.chunkId("abc", 0, "v")).isNotEqualTo(Hashes.chunkId("abc", 1, "v")); }
```

- [ ] **Step 2: Run** `./gradlew :core:test --tests 'rag.core.chunk.*' --tests 'rag.core.ids.*'` — Expected: FAIL.
- [ ] **Step 3: Implement.** Algorithm (the tests do not fully pin it):

```
words = regex \S+ matches with (start,end); tokens(i..j) = tc.count(text[words[i].start, words[j].end))
i = 0
while i < words.size:
    j = largest index ≥ i with tokens(i..j) ≤ max   (j = i if even one word exceeds max)
    emit chunk text[words[i].start, words[j].end)
    if j == last: break
    next = smallest k in (i, j] with tokens(k..j) ≥ overlap ... choose the LARGEST k such that tokens(k..j) ≥ overlap
    i = max(next, i + 1)     // always progress
```
Count tokens on the substring (not by summing per-word counts). Use incremental growth with a binary search on j to keep ingest time reasonable.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `Add fixed-length chunker with section attribution and deterministic IDs`

---

### Task 6: Corpus loading and corpusVersion

**Files:**
- Create: `core/.../corpus/{Manifest,CorpusVersion,CorpusLoader}.java`
- Test: `core/src/test/java/rag/core/corpus/{CorpusVersionTest,CorpusLoaderTest}.java`

**Interfaces:**
- Consumes: `PdfParser.parse`, `MarkdownParser.parse` (Tasks 3–4).
- Produces:
  - `record Manifest(List<Module> modules)`, `record Module(String docId, String code, String title, String version, String revisionDate, String file, String sha256)`, `static Manifest load(Path)`.
  - `CorpusVersion.compute(Path corpusRoot) -> String` (12 hex): sha256 over `manifest.yaml` bytes, then for each `internal/*.md` and `adversarial/*.md` sorted by path relative to the **repo root** (e.g. `corpus/internal/eb-x.md`), the bytes of `relPath + "\n" + sha256hex(file) + "\n"`.
  - `CorpusLoader(Path corpusRoot).load() -> List<ParsedDocument>`: verifies each PDF's sha256 against the manifest (mismatch or missing file → `IllegalStateException` naming the file), parses PDFs (docVersion = manifest `version`) then Markdown sorted by path. Duplicate `docId` → fail.
  - `CorpusLoader.knownDocIds(Path corpusRoot) -> Set<String>` (manifest + front matter only, no PDF parsing; used by Task 7).

- [ ] **Step 1: Write failing tests** with a temp corpus (self-made manifest, a generated PDF from `TestPdfs`, two Markdown files):

```java
@Test void versionChangesWhenMarkdownChanges() { var v1 = CorpusVersion.compute(root); Files.writeString(md, "…edited…"); assertThat(CorpusVersion.compute(root)).hasSize(12).isNotEqualTo(v1); }
@Test void versionIsStable() { assertThat(CorpusVersion.compute(root)).isEqualTo(CorpusVersion.compute(root)); }
@Test void loadsAllDocumentsInOrder() { assertThat(new CorpusLoader(root).load()).extracting(ParsedDocument::docId).containsExactly("test-ab-1", "adv-x", "eb-a"); }
@Test void shaMismatchFails() { /* manifest sha wrong → message contains "AB-1.pdf" */ }
@Test void missingPdfFails() { }
@Test void duplicateDocIdFails() { }
@Test void knownDocIdsReadsWithoutParsingPdfs() { /* PDF deleted → still returns ids */ }
```

- [ ] **Step 2: Run** `./gradlew :core:test --tests 'rag.core.corpus.*'` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — Expected: PASS. Scratch check: `new CorpusLoader(Path.of("corpus")).load()` on the real corpus succeeds with 16 documents (5 SPM + 8 internal + 3 adversarial).
- [ ] **Step 5: Commit** `Add corpus loader with sha256 verification and corpusVersion`

---

### Task 7: Golden set and retrieval metrics

**Files:**
- Create: `core/.../eval/{GoldenItem,GoldenSet,RankedHit,SourceMatcher,RetrievalMetrics}.java`
- Test: `core/src/test/java/rag/core/eval/{GoldenSetTest,SourceMatcherTest,RetrievalMetricsTest}.java`

**Interfaces:**
- Produces:
  - `record ExpectedSource(String docId, String section)`; `record GoldenItem(String id, String question, String category, List<ExpectedSource> expectedSources, List<String> expectedPoints, Clearance minClearance, String split)`.
  - `GoldenSet.load(Path yaml, Set<String> knownDocIds) -> List<GoldenItem>`; validates category ∈ {factual, exact-term, multi-hop, unanswerable, restricted}, split ∈ {dev, test}, unique ids, non-empty `expectedSources` unless unanswerable, every expected `docId` ∈ `knownDocIds`. Errors name the item id.
  - `record RankedHit(String docId, List<String> sectionPath /*chain*/, List<String> coveredSections)`.
  - `SourceMatcher.matches(ExpectedSource e, RankedHit h) -> boolean` — docId equal and `SectionPath.parse(e.section())` is a prefix of the hit's section path **or** of `SectionPath.parse(c)` for any covered section c (§3.4).
  - `record ItemScores(double recall5, double recall10, double reciprocalRank, double ndcg10)`; `RetrievalMetrics.score(List<ExpectedSource> expected, List<RankedHit> ranked) -> ItemScores` per spec 00 §6.3 (each expected source counted once at its highest-ranked match; MRR within top 10; nDCG binary gain per newly matched expected source, ideal = `min(|expected|, 10)` at top, log2(rank+1) discount).

- [ ] **Step 1: Write failing tests**

```java
// SourceMatcherTest
@Test void coveredSectionCounts() { assertThat(SourceMatcher.matches(new ExpectedSource("d", "2.3"),
  new RankedHit("d", List.of("2", "2.2"), List.of("2.2", "2.3.1")))).isTrue(); }
@Test void noFalsePrefix() { assertThat(SourceMatcher.matches(new ExpectedSource("d", "3.2"), new RankedHit("d", List.of("3", "3.20"), List.of("3.20")))).isFalse(); }
@Test void docIdMustMatch() { }

// RetrievalMetricsTest — hand-computed
@Test void twoExpectedOneFoundAtRank3() {   // expected A,B; ranked: x, x, A, A(dup), x…
  var s = RetrievalMetrics.score(List.of(A, B), ranked);
  assertThat(s.recall5()).isEqualTo(0.5); assertThat(s.reciprocalRank()).isCloseTo(1.0 / 3, offset(1e-9));
  assertThat(s.ndcg10()).isCloseTo((1 / log2(4)) / (1 / log2(2) + 1 / log2(3)), offset(1e-9));
}
@Test void foundOnlyAtRank7() { /* recall5 0, recall10 1, rr 1/7 */ }
@Test void nothingFound() { /* all zeros */ }

// GoldenSetTest
@Test void loadsRealGoldenSet() { var items = GoldenSet.load(Path.of("../eval/golden-set.yaml"), CorpusLoader.knownDocIds(Path.of("../corpus")));
  assertThat(items).hasSize(42); assertThat(items).filteredOn(i -> i.split().equals("test")).hasSize(12); }
@Test void rejectsUnknownDocId() { /* temp yaml with docId "eb-typo" → message contains item id and "eb-typo" */ }
@Test void rejectsUnknownCategory() { }
@Test void rejectsMissingSplit() { }
```

- [ ] **Step 2: Run** `./gradlew :core:test --tests 'rag.core.eval.*'` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `Add golden set loader and retrieval metrics`

---

### Task 8: App bootstrap — properties, API-key security, problem+json

**Files:**
- Create: `app/.../RagApplication.java`, `RagProperties.java`, `security/{Principal,ApiKeyAuthFilter,SecurityConfig}.java`, `web/{ProblemTypes,ApiExceptionHandler}.java`, `app/src/main/resources/{application.yaml,application-prod.yaml}`
- Create: `app/src/test/java/rag/app/support/EsTestContainer.java`, `app/src/test/resources/application-test.yaml`
- Test: `app/src/test/java/rag/app/security/ApiKeyAuthIT.java`
- Modify: `.env.example`

**Interfaces:**
- Produces:
  - `record Principal(String name, Clearance clearance, Set<String> roles)` — set as the Spring Security authentication principal; controllers take `@AuthenticationPrincipal Principal p`.
  - `@ConfigurationProperties("rag") record RagProperties(String envPrefix, Security security, Embedding embedding, Retrieval retrieval, Chunking chunking, Corpus corpus)` with `Security(List<ApiKey> apiKeys)`, `ApiKey(String key, String principal, Clearance clearance, Set<String> roles)`, `Embedding(String model, int numThread, int batchSize, Path cacheDir, Duration timeout)`, `Retrieval(int numCandidates)`, `Chunking(int maxTokens, int overlapTokens)`, `Corpus(Path root)`.
  - `ProblemTypes`: constants `urn:rag:problem:unauthorized`, `…:invalid-request`, `…:unsupported-mode`, `…:embedding-unavailable`, `…:generation-unavailable`.
  - `EsTestContainer.get()` — singleton `ElasticsearchContainer` with the pinned image (`asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch")`), password `test-password`, `xpack.security.http.ssl.enabled=false`, `ES_JAVA_OPTS=-Xms512m -Xmx512m`, and `withCreateContainerCmdModifier` setting memory 1.5 GB and nanoCPUs 2e9. `EsTestContainer.register(DynamicPropertyRegistry)` sets `spring.elasticsearch.uris/username(elastic)/password`.
- Defaults in `application.yaml`: `rag.env-prefix: ${RAG_ENV_PREFIX:rag_dev}`; `spring.elasticsearch.uris: ${ES_URL:http://127.0.0.1:9200}`, `username: ${rag.env-prefix}`, `password: ${ES_PASSWORD}`; `rag.embedding: {model: nomic-embed-text, num-thread: 3, batch-size: 16, cache-dir: .cache/embeddings, timeout: 30s}`; `rag.retrieval.num-candidates: 100`; `rag.chunking: {max-tokens: 512, overlap-tokens: 64}`; `rag.corpus.root: corpus`; one api key `${RAG_KEY_DEV}` → `dev`, `restricted`, roles `[]`; `spring.ai.model.chat: deepseek`, `spring.ai.model.embedding: ollama`; `spring.ai.deepseek.chat.options: {model: deepseek-flash, temperature: 0.0, max-tokens: 1024}`; `spring.ai.ollama.base-url: ${OLLAMA_URL:http://127.0.0.1:11434}`; `spring.mvc.problemdetails.enabled: true`; actuator exposes `health,info` only. `application-prod.yaml`: `rag.env-prefix: rag`.
- Security: stateless; `/actuator/health` permitAll; everything else authenticated by `X-Api-Key` (`MessageDigest.isEqual` against each configured key; blank configured keys never match). Entry point writes `401` `application/problem+json` with type `unauthorized`. Startup fails if no non-blank key is configured.

- [ ] **Step 1: Write failing test** `ApiKeyAuthIT` (`@SpringBootTest(webEnvironment = RANDOM_PORT)`, `@ActiveProfiles("test")`, ES via `EsTestContainer`, test profile sets `rag.security.api-keys[0].key=test-key`, AI autoconfig satisfied by stub beans from Task 9/12 — for now `spring.ai.model.chat=none`, `spring.ai.model.embedding=none` in `application-test.yaml`):

```java
@Test void missingKeyIs401Problem() { var r = rest.postForEntity("/v1/search", Map.of("query", "x"), String.class);
  assertThat(r.getStatusCode().value()).isEqualTo(401);
  assertThat(r.getHeaders().getContentType().toString()).startsWith("application/problem+json");
  assertThat(r.getBody()).contains("urn:rag:problem:unauthorized"); }
@Test void wrongKeyIs401() { }
@Test void healthIsOpen() { assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode().value()).isEqualTo(200); }
@Test void validKeyPassesAuth() { /* GET /v1/does-not-exist with key → 404, not 401 */ }
```

- [ ] **Step 2: Run** `./gradlew :app:test --tests 'rag.app.security.*'` — Expected: FAIL.
- [ ] **Step 3: Implement.** `RagApplication.main`: if `args[0] == "ingest"`, build with `WebApplicationType.NONE` and exit with `SpringApplication.exit(...)`; otherwise run normally.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Update `.env.example`**: `ES_URL=http://127.0.0.1:9200`, add `RAG_ENV_PREFIX=rag_dev` and `OLLAMA_URL=http://127.0.0.1:11434` with one-line comments.
- [ ] **Step 6: Commit** `Add app bootstrap with API-key authentication and problem details`

---

### Task 9: Embedder with Ollama adapter and cache

**Files:**
- Create: `app/.../embed/{Embedder,OllamaEmbedder,EmbeddingCache,EmbeddingUnavailableException}.java`
- Create: `app/src/test/java/rag/app/support/FakeEmbedder.java`
- Test: `app/src/test/java/rag/app/embed/{OllamaEmbedderTest,EmbeddingCacheTest,OllamaEmbedderLocalTest}.java`

**Interfaces:**
- Produces:
  - `interface Embedder { String modelId(); /* "ollama/nomic-embed-text" */ String shortName(); /* "nomic" */ int dims(); float[] embedQuery(String q); List<float[]> embedDocuments(List<String> texts); }` — prefixes are added inside the implementation: `search_query: ` / `search_document: ` for nomic.
  - `OllamaEmbedder(EmbeddingModel springAi, RagProperties.Embedding cfg)` — calls Spring AI `EmbeddingModel.call(new EmbeddingRequest(prefixed, OllamaEmbeddingOptions{model, numThread}))` in batches of `batchSize`; dims 768 checked on every result (mismatch → `IllegalStateException`). Any transport error or timeout → `EmbeddingUnavailableException`. (Verify the exact Spring AI 2.0.2 options class/builder name; if `numThread` is not exposed, call Ollama `/api/embed` with `RestClient` instead and note it in the commit.)
  - `EmbeddingCache(Path dir)`: `Optional<float[]> get(String modelId, String contentHash)`, `void put(String modelId, String contentHash, float[] v)` — file `<dir>/<modelId with '/'→'_'>/<contentHash>.f32`, little-endian float32, written via temp file + atomic move.
  - `FakeEmbedder(int dims)` (test): deterministic, hashes each lower-cased word into a bucket, L2-normalised; texts sharing words are close. `modelId()` = `"fake/hash"`, `shortName()` = `"fake"`.

- [ ] **Step 1: Write failing tests**

```java
// OllamaEmbedderTest — Spring AI EmbeddingModel mocked (Mockito)
@Test void addsTaskPrefixes() { embedder.embedQuery("what is X"); verify(model).call(argThat(r -> r.getInstructions().equals(List.of("search_query: what is X")))); }
@Test void batchesDocuments() { embedder.embedDocuments(List.of(/*35 texts*/)); verify(model, times(3)).call(any()); }   // batch 16
@Test void passesNumThread() { /* captured options numThread == 3, model == "nomic-embed-text" */ }
@Test void wrongDimsFails() { }
@Test void transportErrorBecomesUnavailable() { when(model.call(any())).thenThrow(new ResourceAccessException("down"));
  assertThatThrownBy(() -> embedder.embedQuery("x")).isInstanceOf(EmbeddingUnavailableException.class); }

// EmbeddingCacheTest
@Test void roundTrip() { cache.put("ollama/nomic-embed-text", "ab12", new float[]{1f, -2.5f}); assertThat(cache.get("ollama/nomic-embed-text", "ab12")).hasValueSatisfying(v -> assertThat(v).containsExactly(1f, -2.5f)); }
@Test void missIsEmpty() { }

// OllamaEmbedderLocalTest — @Tag("local"), excluded from default test task; real Ollama at OLLAMA_URL
@Test void realOllamaReturns768() { }
```

- [ ] **Step 2: Run** `./gradlew :app:test --tests 'rag.app.embed.*'` — Expected: FAIL. Configure `test { useJUnitPlatform { excludeTags("local") } }` and a `localTest` task including only `local`.
- [ ] **Step 3: Implement.** Wire `OllamaEmbedder` as a `@Bean Embedder` conditional on an `EmbeddingModel` bean; set the Spring AI HTTP client read timeout from `rag.embedding.timeout`.
- [ ] **Step 4: Run** unit tests — PASS; run `./gradlew :app:localTest` — PASS against the shared Ollama.
- [ ] **Step 5: Commit** `Add Ollama embedder with task prefixes and embedding cache`

---

### Task 10: ES chunk index and ingest CLI

**Files:**
- Create: `app/.../es/{IndexNames,ChunkIndexAdmin,ChunkDocument,ChunkWriter}.java`, `app/.../ingest/IngestCommand.java`
- Test: `app/src/test/java/rag/app/es/IndexNamesTest.java`, `app/src/test/java/rag/app/ingest/IngestIT.java`
- Modify: `ROADMAP.md` (tick "Ingest v1")

**Interfaces:**
- Consumes: `CorpusLoader`, `CorpusVersion`, `FixedChunker`, `Embedder`, `EmbeddingCache`.
- Produces:
  - `IndexNames.chunksAlias(prefix)` → `<prefix>-chunks`; `IndexNames.chunksIndex(prefix, embedder.shortName(), dims, chunkerVersion, int v)` → e.g. `rag_dev-chunks-nomic768-fixed512-v1` (chunker short form = `chunkerVersion` with `-<overlap>-cl100k` dropped, i.e. `fixed-512-64-cl100k` → `fixed512`); v = 1 in Phase 1.
  - `record ChunkDocument(...)` — all ROADMAP §4.2 fields plus `coveredSections`, `docVersion`; built by `ChunkDocument.of(Chunk, ParsedDocument, float[] embedding, String corpusVersion, String chunkerVersion, String embeddingModel, Instant ingestedAt)`.
  - `ChunkIndexAdmin`: `boolean exists(String index)`, `void create(String index, int dims, Map<String,String> meta)` (mapping: keyword fields; `docTitle` text + `keyword` subfield; `content` text `analyzer: english`; `pages`/`ordinal`/`tokenCount` integer; `ingestedAt` date; `embedding` dense_vector dims, `similarity: cosine`, `index: true`, `index_options {type: hnsw, m: 16, ef_construction: 100}`; `settings.number_of_replicas: 0`; `_meta` = meta), `void delete(String index)`, `void pointAlias(String alias, String index)` (single `_aliases` call: remove alias from any index, add to this one), `Map<String,String> aliasMeta(String alias)`.
  - `ChunkWriter.bulkIndex(String index, List<ChunkDocument> docs)` — batches of 200, `_id = chunkId`, fails on any item error, refresh at end.
  - `IngestCommand implements ApplicationRunner` active only when the first arg is `ingest`; options `--step=all|embed|index` (default `all`), `--recreate`. Steps: load corpus → chunk → **embed** (for each chunk: cache hit or embed in batches, `put`) → **index** (every vector must be in cache, else fail naming the count of misses; existing index without `--recreate` → fail; create, bulk, point alias). Logs: document count, chunk count, total tokens, embed-step and index-step wall time (ms), cache hits/misses.

- [ ] **Step 1: Write failing tests**

```java
// IndexNamesTest
@Test void physicalName() { assertThat(IndexNames.chunksIndex("rag_dev", "nomic", 768, "fixed-512-64-cl100k", 1)).isEqualTo("rag_dev-chunks-nomic768-fixed512-v1"); }

// IngestIT — Testcontainers ES, FakeEmbedder(8) as @Primary Embedder, temp corpus (generated PDF + 2 self-made md, one internal one restricted), temp cache dir
@Test void ingestCreatesIndexAliasAndMeta() {
  run("ingest");
  assertThat(admin.aliasMeta("rag_dev-chunks")).containsKeys("corpusVersion", "chunkerVersion", "embeddingModel")
      .containsEntry("chunkerVersion", "fixed-512-64-cl100k");
  assertThat(countDocs("rag_dev-chunks")).isEqualTo(expectedChunkCount);
  // mapping assertion: embedding.index_options.type == "hnsw", m == 16
}
@Test void reingestIsDeterministic() { run("ingest"); var a = chunkIdsAndHashes(); run("ingest", "--recreate"); assertThat(chunkIdsAndHashes()).isEqualTo(a); }
@Test void existingIndexWithoutRecreateFails() { }
@Test void indexStepFailsOnCacheMiss() { run("ingest", "--step=index") /* empty cache */ → fails, message contains "cache"; }
@Test void embedStepThenIndexStep() { run("ingest", "--step=embed"); assertThat(exists(index)).isFalse(); run("ingest", "--step=index"); assertThat(exists(index)).isTrue(); }
```

- [ ] **Step 2: Run** `./gradlew :app:test --tests 'rag.app.es.*' --tests 'rag.app.ingest.*'` — Expected: FAIL.
- [ ] **Step 3: Implement.** Use the Boot-autoconfigured `ElasticsearchClient`.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `Add Elasticsearch chunk index and two-step ingest CLI` (with the ROADMAP "Ingest v1" tick).

---

### Task 11: Vector retrieval and `/v1/search`

**Files:**
- Create: `app/.../search/{VectorRetriever,SearchHit,SearchController,SearchRequest,SearchResponse}.java`, `app/.../info/IndexInfoContributor.java`
- Test: `app/src/test/java/rag/app/search/{VectorRetrieverIT,SearchControllerIT}.java`

**Interfaces:**
- Consumes: `Embedder`, `IndexNames`, `ChunkIndexAdmin.aliasMeta`, `Principal`.
- Produces:
  - `record SearchHit(String chunkId, String sectionId, String docId, String docTitle, List<String> sectionPath, List<String> coveredSections, List<Integer> pages, String classification, double score, Map<String, Object> scores /* vectorRank, similarity */, String text)`.
  - `VectorRetriever.search(String query, int topK, Principal principal) -> List<SearchHit>` — kNN on `<prefix>-chunks`, field `embedding`, `k = topK`, `num_candidates = max(numCandidates, topK)`, `filter: terms classification ∈ principal.clearance().visible()` (wire strings) **inside the knn clause**. `score` = ES `_score`; `similarity` = `2 * _score - 1` (ES cosine score transform). `vectorRank` = 1-based position.
  - `POST /v1/search`: `SearchRequest(String query, Integer topK, String mode, Boolean rerank)`; response `SearchResponse(String queryId /* "q_" + ULID */, List<SearchHit> hits)`. Validation: blank `query`, `topK` outside 1–50 → 400 `invalid-request`; `mode` ∉ {null, VECTOR} → 400 `unsupported-mode`; `rerank == true` → 400 `unsupported-mode`; `EmbeddingUnavailableException` → 503 `embedding-unavailable`.
  - `IndexInfoContributor` adds `rag: {corpusVersion, chunkerVersion, embeddingModel, index}` to `/actuator/info` from `aliasMeta`; `{}` (no error) if the alias is missing.
  - ULID via `com.github.f4b6a3:ulid-creator:5.2.4`.

- [ ] **Step 1: Write failing tests**

```java
// VectorRetrieverIT — index built via ChunkIndexAdmin/ChunkWriter with FakeEmbedder(8)
@Test void preFilterExcludesHigherClassificationAndStillFillsTopK() {
  // 1 internal chunk identical to the query + 6 public chunks less similar
  var hits = retriever.search("tolerance for disruption", 5, new Principal("bob", PUBLIC, Set.of()));
  assertThat(hits).hasSize(5).allSatisfy(h -> assertThat(h.classification()).isEqualTo("public"));
}
@Test void restrictedSeesInternalFirst() { assertThat(retriever.search(q, 5, restricted).getFirst().classification()).isEqualTo("internal"); }
@Test void ranksAndSimilarity() { /* vectorRank 1..n, similarity within [-1,1] and descending */ }

// SearchControllerIT
@Test void searchReturnsContractShape() { /* queryId starts "q_", hit has chunkId, docId, sectionPath, coveredSections, pages, classification, score, scores.vectorRank, text */ }
@Test void rejectsInvalidRequests() { for (var body : List.of(Map.of(), Map.of("query", " "), Map.of("query", "x", "topK", 0), Map.of("query", "x", "topK", 51)))
  assertProblem(post(body), 400, "urn:rag:problem:invalid-request"); }
@Test void hybridAndRerankUnsupported() { assertProblem(post(Map.of("query", "x", "mode", "HYBRID")), 400, "urn:rag:problem:unsupported-mode"); }
@Test void embeddingFailureReturns503() { /* @MockitoBean Embedder throwing EmbeddingUnavailableException */ assertProblem(post(Map.of("query", "x")), 503, "urn:rag:problem:embedding-unavailable"); }
@Test void infoExposesVersions() { /* GET /actuator/info with key → rag.chunkerVersion == "fixed-512-64-cl100k" */ }
@Test void infoRequiresKey() { /* without key → 401 */ }
```

- [ ] **Step 2: Run** `./gradlew :app:test --tests 'rag.app.search.*'` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `Add pre-filtered vector retrieval and /v1/search`

---

### Task 12: `/v1/ask` with DeepSeek

**Files:**
- Create: `app/.../ask/{PromptRenderer,AskService,AskController,AskRequest,AskResponse}.java`, `app/src/main/resources/prompts/answer.v1.st`
- Create: `app/src/test/java/rag/app/support/StubChatModel.java`
- Test: `app/src/test/java/rag/app/ask/{PromptRendererTest,AskControllerIT}.java`
- Modify: `ROADMAP.md` (tick "`/v1/search` … `/v1/ask`" and "Integration tests")

**Interfaces:**
- Consumes: `VectorRetriever.search`, Spring AI `ChatModel`.
- Produces:
  - `PromptRenderer.render(String question, List<SearchHit> hits) -> Prompt` (system + user messages from `answer.v1.st`; `templateVersion()` → `"answer.v1"`). Sources rendered as `[n] docId=… section=<leaf or "-">\n<text>`, n from 1.
  - `AskService.ask(String question, int topK, Principal p) -> AskResponse`.
  - `AskResponse(String answerId /* "a_"+ULID */, String status /* "ANSWERED" */, String answer, List<Citation> citations, Usage usage, Latency latencyMs)`; `Citation(int ref, String chunkId, String docId, List<String> sectionPath, List<Integer> pages, String quote /* first 300 chars of hit text */)`; `Usage(Integer promptTokens, Integer completionTokens)` from `ChatResponse.getMetadata().getUsage()` (null if absent — never estimated); `Latency(long retrieval, long rerank /*0*/, long generation)`.
  - `POST /v1/ask`: `AskRequest(String question, Integer topK /*default 5*/, String mode, Boolean rerank, String conversationId)`; same validation as search, plus `conversationId != null` → 400 `invalid-request`; LLM failure → 503 `generation-unavailable`.
  - Zero hits → `ANSWERED`, `answer` = `"No sources were found for this question."`, empty citations, LLM **not** called, `latencyMs.generation = 0`.
- Prompt v1 copy (system message):

```
You answer questions about banking supervisory guidance and Example Bank internal policies.
Answer only from the numbered sources below. Cite every claim with its source number in
square brackets, for example [1]. If the sources do not contain the answer, say so.
```

- [ ] **Step 1: Write failing tests**

```java
// PromptRendererTest
@Test void numbersSourcesFromOne() { var p = renderer.render("Q?", List.of(hit("eb-a", "2"), hit("spm-or-2", "3.2")));
  assertThat(userText(p)).contains("[1] docId=eb-a section=2").contains("[2] docId=spm-or-2 section=3.2").contains("Q?"); }

// AskControllerIT — StubChatModel returns "Answer [1]." with usage 100/10; FakeEmbedder index
@Test void answersWithNumberedSources() { var r = ask("tolerance for disruption");
  assertThat(r.status()).isEqualTo("ANSWERED"); assertThat(r.answerId()).startsWith("a_");
  assertThat(r.citations()).extracting(Citation::ref).containsExactly(1, 2, 3, 4, 5);
  assertThat(r.usage().promptTokens()).isEqualTo(100); assertThat(stub.calls()).isEqualTo(1); }
@Test void publicCallerPromptHasNoInternalSources() { /* via stub's captured prompt, with a PUBLIC test key */ }
@Test void zeroHitsSkipsLlm() { /* empty index alias → stub.calls() == 0, citations empty */ }
@Test void conversationIdRejected() { }
@Test void llmFailureReturns503() { }
```

  (Add a second test key `public-key` → `PUBLIC` in `application-test.yaml` only.)
- [ ] **Step 2: Run** `./gradlew :app:test --tests 'rag.app.ask.*'` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew :app:test` — Expected: all PASS.
- [ ] **Step 5: Commit** `Add /v1/ask with DeepSeek generation and numbered sources` (with both ROADMAP ticks).

---

### Task 13: Eval runner v1

**Files:**
- Create: `eval-runner/src/main/java/rag/eval/{EvalRunner,SearchClient,RunMetadata,ReportWriter}.java`
- Test: `eval-runner/src/test/java/rag/eval/{SearchClientTest,ReportWriterTest,EvalRunnerTest}.java`
- Modify: `ROADMAP.md` (tick "Eval runner v1")

**Interfaces:**
- Consumes: `GoldenSet.load`, `CorpusLoader.knownDocIds`, `RetrievalMetrics.score`, `RankedHit`.
- Produces:
  - CLI: `./gradlew :eval-runner:run --args="--base-url=http://127.0.0.1:8080 [--split=all|dev|test] [--golden=eval/golden-set.yaml] [--corpus=corpus] [--out=eval/runs]"`, key from env `RAG_KEY_DEV` (missing → exit 2 with message). Gradle `run` task working dir = repo root.
  - `SearchClient(URI base, String apiKey)`: `List<RankedHit> search(String query, int topK)` (`mode: VECTOR`, records client-side latency ms per call), `Map<String,String> info()` from `/actuator/info` `rag`. Non-2xx → exception with status and problem `type`.
  - `RunMetadata`: timestamp (UTC), git commit + dirty (`git rev-parse HEAD`, `git status --porcelain`), `uname -m`, `availableProcessors`, base URL, info map, split.
  - `ReportWriter.write(Path dir, RunMetadata meta, List<ItemResult> items)` → `report.json` (meta, per-item scores and the first-match ranks, aggregates) and `report.md`: metadata table; aggregate table rows = `all`, then each category, columns `n | Recall@5 | Recall@10 | MRR | nDCG@10`, one table per split (`test` first, then `dev`); latency p50/p95 (nearest-rank). Per-item table: id, category, split, first-match rank, Recall@5 — **no question text, no chunk text**. Numbers to 3 decimals.
  - `record ItemResult(GoldenItem item, ItemScores scores, Integer firstMatchRank, long latencyMs)`.
  - Items with category `unanswerable` are skipped (and counted in the metadata as skipped).

- [ ] **Step 1: Write failing tests**

```java
// SearchClientTest — JDK HttpServer stub
@Test void sendsKeyAndMode() { /* stub asserts X-Api-Key and body.mode == "VECTOR", topK == 10 */ }
@Test void mapsHitsToRankedHits() { }
@Test void problemResponseRaises() { /* 401 problem → exception message contains "401" and "urn:rag:problem:unauthorized" */ }

// ReportWriterTest
@Test void markdownHasSplitTablesAndNoText() { /* two fake items; md contains "| all |", "| exact-term |", "### test" before "### dev"; does not contain question text */ }
@Test void percentilesNearestRank() { /* latencies 10..100 → p50 50, p95 100 */ }

// EvalRunnerTest
@Test void skipsUnanswerableAndFiltersSplit() { /* stub server, tiny golden yaml + corpus dir with known ids → only answerable items in split queried */ }
@Test void missingKeyExits2() { }
```

- [ ] **Step 2: Run** `./gradlew :eval-runner:test` — Expected: FAIL.
- [ ] **Step 3: Implement.** Plain `java.net.http.HttpClient` + Jackson 3.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `Add eval runner v1 for retrieval metrics` (with the ROADMAP tick).

---

### Task 14: Baseline run and evidence report

**Prerequisite (author):** `.env` in the repo root with `ES_PASSWORD` (= `APP_RAG_DEV_PASSWORD`), `DEEPSEEK_API_KEY`, `RAG_KEY_DEV`. The executor never prints or copies these values.

**Files:**
- Create: `evidence/phase1-baseline.md`
- Modify: `README.md` (results table row, setup notes), `ROADMAP.md` (tick Phase 1 in Progress)

- [ ] **Step 1: Full test suite.** `./gradlew build` — Expected: BUILD SUCCESSFUL. `bash scripts/tests/test-check-compose-limits.sh && bash scripts/tests/test-verify-corpus.sh && scripts/verify-corpus.sh` — all pass.
- [ ] **Step 2: Ingest into `rag_dev`.** `set -a; . ./.env; set +a; ./gradlew :app:bootRun --args="ingest --step=embed"` then `--args="ingest --step=index"`. Expected: 16 documents; record chunk count, total tokens, embed-step and index-step wall times from the log.
- [ ] **Step 3: Start the app** (`./gradlew :app:bootRun`, background) and check `GET /actuator/info` with the key shows `chunkerVersion: fixed-512-64-cl100k`, `embeddingModel: ollama/nomic-embed-text`.
- [ ] **Step 4: Run the eval.** `./gradlew :eval-runner:run --args="--base-url=http://127.0.0.1:8080 --split=all"`. Expected: `eval/runs/<ts>/report.{json,md}` written, git dirty = false.
- [ ] **Step 5: Ask smoke test.** Three `POST /v1/ask`: g002 and g004 questions (Example Bank) and one SPM question (g-id of an `exact-term` SPM item). Record status, cited docIds, usage tokens, latency; answer text only for the two Example Bank questions.
- [ ] **Step 6: Write `evidence/phase1-baseline.md`** per spec §8: config, ingest numbers, the generated `report.md` content verbatim, latency, ask smoke results, observations drawn only from these numbers (state regressions or surprises as they are). Check: `grep -i hkma.gov evidence/ README.md` returns nothing; no SPM sentences in the file.
- [ ] **Step 7: Update README** results table with a Phase 1 row (Recall@5 / MRR / nDCG@10 from the `test` split, note "VECTOR, fixed-512-64, nomic") and replace the "planned" setup steps with the real commands (shared stacks note + `compose.yaml` alternative).
- [ ] **Step 8: Commit** `Record Phase 1 naive baseline` (evidence + README + Phase 1 Progress tick).

---

## Self-review notes

- Spec coverage: §1 rows 1–8 → Tasks 1, 2, 3–6+10, 11, 12, 8, 7+13, 14. §2 env prefix → Task 8; compose → Task 2. §3.1–3.5 → Tasks 3–6, 9, 10. §4 → Tasks 8, 11, 12. §5 → Tasks 7, 13. §6 → tests in each task. §7 → Task 8. §8 → Task 14. §9 → Task 1 versions.
- Deferred by the spec and not planned: `rag-sections`, BM25/HYBRID, audit, tracing, fixture embedding mode, CI workflow.
