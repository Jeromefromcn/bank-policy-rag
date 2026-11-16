# Phase 5 Productionisation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the working RAG service into a production-shaped one: permission leak tests, an append-only audit with replay, tracing and Prometheus metrics, indirect-injection tests, and a green Example Bank CI gate.

**Architecture:** Keep the existing modules (`core`, `app`, `eval-runner`). Add an `audit` package that writes one record per completed `/v1/ask` and `/v1/search` response with `op_type=create`, fails closed, and replays from the recorded prompt after verifying every `contentHash`. Add a `metrics` package for bounded-tag Micrometer counters/timers and observation spans; actuator moves to a separate management port. The CI gate is a tagged JUnit test that runs the app with fixture vectors (no Ollama) against Testcontainers ES and compares a 24-item Example Bank subset with `eval/baseline.json`.

**Tech Stack:** Java 25, Spring Boot 4.1.2, Spring AI 2.0.2, Elasticsearch Java client 9.5.6, Elasticsearch 9.5.5, Micrometer (Prometheus + OTLP tracing), JUnit 5, AssertJ, Testcontainers, Gradle 9.9, GitHub Actions `ubuntu-24.04-arm`.

**Spec:** `docs/specs/05-productionisation.md` (approved 2026-11-16). The plan argues from the spec; read both.

## Global Constraints

- **English only** in code, comments, docs, commits and test data.
- **Never commit SPM text, PDFs or HKMA URLs.** CI and fixtures are Example Bank only; test text is self-made.
- **No secrets in git.** Keys and credentials come from environment variables; `.env` is gitignored.
- **Every container has CPU and memory limits**, including compose services and Testcontainers.
- **Permission filtering stays a pre-filter** in the kNN `filter` / BM25 `bool.filter`; never post-filter.
- **Pin every dependency version and image digest.** Do not use floating tags.
- **TDD, one logical change per commit, English imperative commit messages.** A task's final step is its commit.
- **Response contract additions are limited to `latencyMs.audit`.** Existing field names and meanings do not change.
- **Audit index** is `audit-<envPrefix>-v1`; the default env prefix is `rag_dev`.
- **PR CI spends no model tokens, needs no Ollama, and contains no SPM material.**
- `decisions/`, golden-set answers, Example Bank documents and the injection set are author-owned. Draft them, get approval, and do not commit AI-generated content as final.
- Do not commit the plan's speculative fixture or baseline values; generate them from real runs.

## Review Focus

These are the input classes and failure modes the spec implies but no single task may make obvious. Each line names the test that pins it.

1. **Audit failure must not leak a half-answer or a conversation turn.** A failed audit write returns `503`, stores no conversation turn, and the retry sees the original history. Test: `AuditIT.auditFailureReturns503AndStoresNoTurn` (Task 9).
2. **The `NO_ACCESSIBLE_SOURCES` probe must stay invisible to the caller.** A public caller's refusal for hidden content is byte-for-byte the absent-content refusal (except ids/timings); the cause appears only in audit and metrics. Test: `LeakIT.publicAndAbsentBodiesMatch` and `AuditIT.probeCauseIsAuditOnly` (Tasks 3, 9, 12).
3. **Replay must never silently succeed on stale content.** A deleted or changed chunk returns `409 content-hash-mismatch`, not a regenerated answer. Test: `AuditReplayIT.hashMismatchIs409` and `AuditReplayIT.missingChunkIs409` (Task 11).
4. **A fixture miss must fail the gate loudly.** A stale fixture or changed chunker must not produce an empty/zero vector that makes Recall look fine. Test: `FixtureEmbedderTest.missingVectorFails` and `CiEvalGateIT.missingFixtureFails` (Task 17).
5. **The management port must not be exposed by the default configuration.** The default address stays `127.0.0.1`; only the development server sets `0.0.0.0` behind the firewall. Test: `ManagementIT` (Task 4).

---

## File Structure

**Core**

- `core/src/main/java/rag/core/corpus/CorpusLoader.java` — add `Scope` (`ALL`, `EXAMPLE_BANK`), `load(Scope)`, `knownDocIds(Path, Scope)`.
- `core/src/main/java/rag/core/eval/EvalScope.java` — the Example Bank item predicate shared by the runner and the gate.
- `core/src/test/java/rag/core/corpus/CorpusLoaderTest.java` — scope tests.

**App: security and errors**

- `app/src/main/java/rag/app/security/SecurityConfig.java` — `admin` role on `/v1/audit/**`, 403 problem handler, actuator permit.
- `app/src/main/java/rag/app/web/ProblemTypes.java` — new stable types.
- `app/src/main/java/rag/app/web/ApiException.java` — new factories.
- `app/src/test/resources/application-test.yaml` — public/internal/restricted/admin keys, tracing off, management port 0.
- `app/src/test/java/rag/app/support/IntegrationTest.java` — key constants.

**App: audit**

- `app/src/main/java/rag/app/audit/InternalRefusalCause.java`
- `app/src/main/java/rag/app/audit/VisibilityProbe.java`
- `app/src/main/java/rag/app/audit/AuditRecord.java`
- `app/src/main/java/rag/app/audit/AuditIndexAdmin.java`
- `app/src/main/java/rag/app/audit/AuditStartup.java`
- `app/src/main/java/rag/app/audit/AuditStore.java`
- `app/src/main/java/rag/app/audit/AuditReader.java`
- `app/src/main/java/rag/app/audit/AuditService.java`
- `app/src/main/java/rag/app/audit/ReplayService.java`
- `app/src/main/java/rag/app/audit/AuditController.java`

**App: metrics**

- `app/src/main/java/rag/app/metrics/RequestMetrics.java`
- `app/src/main/java/rag/app/metrics/Observations.java`
- `app/src/main/java/rag/app/metrics/Tracing.java`

**App: existing classes modified**

- `RagProperties.java`, `application.yaml`, `application-prod.yaml`
- `search/SearchController.java`, `search/SearchResponse.java`
- `search/RetrievalPipeline.java`
- `ask/AskService.java`, `ask/AskResponse.java`, `ask/PromptRenderer.java`
- `ingest/IngestCommand.java`, `RagApplication.java`
- `es/IndexNames.java`

**App: fixtures**

- `app/src/main/java/rag/app/embed/EmbeddingPrefixes.java`
- `app/src/main/java/rag/app/embed/FixtureEmbedder.java`
- `app/src/main/java/rag/app/fixture/FixtureFiles.java`
- `app/src/main/java/rag/app/fixture/FixtureCommand.java`

**Eval runner**

- `eval-runner/src/main/java/rag/eval/CiGate.java`
- `eval-runner/src/main/java/rag/eval/Baseline.java`
- `eval-runner/src/main/java/rag/eval/InjectionItem.java`
- `eval-runner/src/main/java/rag/eval/InjectionSet.java`
- `eval-runner/src/main/java/rag/eval/InjectionRun.java`
- `eval-runner/src/main/java/rag/eval/InjectionReport.java`
- `eval-runner/src/main/java/rag/eval/EvalRunner.java` — `--scope=example-bank`, `--injection`.

**Build, scripts and CI**

- `app/build.gradle.kts`, `eval-runner/build.gradle.kts`
- `scripts/build-fixture.sh`, `scripts/check-no-spm.sh`
- `compose.yaml`, `observability/prometheus.yml`
- `.github/workflows/ci.yml`, `.github/workflows/live-eval.yml`
- `.env.example`, `README.md`
- `eval/injection.yaml`, `eval/baseline.json`, `eval/fixtures/…`
- `evidence/phase5-permissions.md`, `phase5-audit-observability.md`, `phase5-injection.md`, `phase5-ci.md`

---

## Task 1: Key set, admin role and a problem+json 403

**Files:**
- Modify: `app/src/test/resources/application-test.yaml`
- Modify: `app/src/test/java/rag/app/support/IntegrationTest.java`
- Modify: `app/src/main/java/rag/app/web/ProblemTypes.java`
- Modify: `app/src/main/java/rag/app/security/SecurityConfig.java`
- Test: `app/src/test/java/rag/app/security/ApiKeyAuthIT.java`

**Interfaces:**
- Consumes: the existing `ApiKeyAuthFilter` and `Principal` (unchanged).
- Produces: test constants `IntegrationTest.KEY` (restricted, principal `tester`), `PUBLIC_KEY` (`bob`), `INTERNAL_KEY` (`carol`), `ADMIN_KEY` (`admin`, `roles: [admin]`); `ProblemTypes.FORBIDDEN = urn:rag:problem:forbidden`.

- [ ] **Step 1: Write the failing tests**

Add to `ApiKeyAuthIT`:

```java
@Test
void auditEndpointWithoutAdminRoleIs403Problem() {
    var r = Http.get(port, "/v1/audit/a_unknown", KEY);
    assertThat(r.status()).isEqualTo(403);
    assertThat(r.contentType()).startsWith("application/problem+json");
    assertThat(r.body()).contains("urn:rag:problem:forbidden");
}

@Test
void auditEndpointWithAdminKeyPassesTheRoleCheck() {
    // No audit controller yet: reaching the dispatcher means 404, not 403/401.
    assertThat(Http.get(port, "/v1/audit/a_unknown", ADMIN_KEY).status()).isEqualTo(404);
}
```

Add the keys to `application-test.yaml`:

```yaml
      - key: internal-key
        principal: carol
        clearance: internal
        roles: []
      - key: admin-key
        principal: admin
        clearance: restricted
        roles: [admin]
```

and the constants to `IntegrationTest`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:test --tests 'rag.app.security.ApiKeyAuthIT'`
Expected: `auditEndpointWithoutAdminRoleIs403Problem` fails (403 body is not problem+json, or the key resolution is missing).

- [ ] **Step 3: Implement**

In `ProblemTypes` add `FORBIDDEN` and `AUDIT_UNAVAILABLE`.

In `SecurityConfig`, add `.requestMatchers("/v1/audit/**").hasRole("admin")` before `.anyRequest().authenticated()`, and set an `accessDeniedHandler` that writes the same JSON shape as the 401 entry point with status 403 and type `FORBIDDEN`. Permit `/actuator/**` (the actuator moves to the management port in Task 4; on the API port these paths simply 404).

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests 'rag.app.security.ApiKeyAuthIT'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/rag/app/security/SecurityConfig.java app/src/main/java/rag/app/web/ProblemTypes.java \
  app/src/test/java/rag/app/security/ApiKeyAuthIT.java app/src/test/java/rag/app/support/IntegrationTest.java \
  app/src/test/resources/application-test.yaml
git commit -m "Add the Phase 5 key set and the admin audit role"
```

---

## Task 2: `VisibilityProbe` and the internal refusal cause

**Files:**
- Create: `app/src/main/java/rag/app/audit/InternalRefusalCause.java`
- Create: `app/src/main/java/rag/app/audit/VisibilityProbe.java`
- Test: `app/src/test/java/rag/app/audit/VisibilityProbeIT.java`

**Interfaces:**
- Consumes: `ElasticsearchClient`, `RagProperties`, `Principal`, `Clearance`.
- Produces: `public enum InternalRefusalCause { NO_ACCESSIBLE_SOURCES, NO_MATCHING_CONTENT }`; `public InternalRefusalCause cause(String query, Principal principal)`.

- [ ] **Step 1: Write the failing test**

Create `VisibilityProbeIT` with `@TestPropertySource(properties = "rag.env-prefix=t_probe")` and an index built by `TestIndex`:

```java
@Test void publicCallerWithAHiddenMatchIsNoAccessibleSources() {
    assertThat(probe.cause("tolerance for disruption", publicPrincipal()))
            .isEqualTo(InternalRefusalCause.NO_ACCESSIBLE_SOURCES);
}
@Test void publicCallerWithNoMatchIsNoMatchingContent() {
    assertThat(probe.cause("zebra quantum harvest", publicPrincipal()))
            .isEqualTo(InternalRefusalCause.NO_MATCHING_CONTENT);
}
@Test void restrictedCallerIsAlwaysNoMatchingContent() {
    assertThat(probe.cause("zebra quantum harvest", restrictedPrincipal()))
            .isEqualTo(InternalRefusalCause.NO_MATCHING_CONTENT);
}
```

Use `TestIndex.mixed(QUERY)` (one internal chunk equal to `QUERY`, public chunks with other text). Build principals from `new Principal(name, clearance, Set.of())`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:test --tests 'rag.app.audit.VisibilityProbeIT'`
Expected: FAIL (`VisibilityProbe` and the enum do not exist).

- [ ] **Step 3: Implement**

`VisibilityProbe.cause` computes the hidden levels as `Arrays.stream(Clearance.values()).filter(c -> !principal.clearance().visible().contains(c))`. If empty, return `NO_MATCHING_CONTENT` without a query. Otherwise run:

```java
client.count(c -> c.index(props.retrieval().chunksTarget(props.envPrefix()))
    .query(q -> q.bool(b -> b
        .must(m -> m.multiMatch(mm -> mm.query(query).fields("content", "docTitle")))
        .filter(f -> f.terms(t -> t.field("classification").terms(v -> v.value(hidden))))))
```

A 404 index is `NO_MATCHING_CONTENT`. Any other ES error is rethrown. Do not use the reranker or the embedder.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:test --tests 'rag.app.audit.VisibilityProbeIT'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/rag/app/audit app/src/test/java/rag/app/audit/VisibilityProbeIT.java
git commit -m "Add the internal refused-source probe"
```

---

## Task 3: The permission leak matrix

**Files:**
- Modify: `app/src/test/java/rag/app/support/TestIndex.java` — add `threeLevels(String query)` with a restricted chunk.
- Create: `app/src/test/java/rag/app/permissions/LeakIT.java`

**Interfaces:**
- Consumes: `KEY`, `INTERNAL_KEY`, `PUBLIC_KEY`, `ADMIN_KEY` (Task 1); `TestIndex`.
- Produces: no production code. The test names are the contract.

- [ ] **Step 1: Write the failing tests**

`LeakIT` runs with `@TestPropertySource(properties = {"rag.env-prefix=t_leak", "rag.ask.min-similarity=0.95"})`. `TestIndex.threeLevels(QUERY)` has a public chunk, an internal chunk and a restricted chunk; the query equals the restricted chunk's text exactly.

Tests:

```java
@Test void publicAndAbsentBodiesMatch() {
    JsonNode hidden = ask(PUBLIC_KEY, QUERY);
    JsonNode absent = ask(PUBLIC_KEY, "zebra quantum harvest");
    assertThat(hidden.get("status").asString()).isEqualTo("REFUSED");
    assertThat(fieldNames(hidden)).isEqualTo(fieldNames(absent));
    assertThat(hidden.get("refusalReason")).isEqualTo(absent.get("refusalReason"));
    assertThat(hidden.get("answer")).isEqualTo(absent.get("answer"));
    assertThat(hidden.get("citations")).isEqualTo(absent.get("citations"));
    assertThat(hidden.get("usage")).isEqualTo(absent.get("usage"));
    assertThat(hidden.get("latencyMs").get("generation")).isEqualTo(absent.get("latencyMs").get("generation"));
    assertThat(hidden.get("latencyMs").get("rerank")).isEqualTo(absent.get("latencyMs").get("rerank"));
}
@Test void internalCallerCannotSeeRestrictedAndRestrictedCan() {
    assertThat(ask(INTERNAL_KEY, QUERY).get("status").asString()).isEqualTo("REFUSED");
    assertThat(ask(KEY, QUERY).get("status").asString()).isEqualTo("ANSWERED");
}
@Test void publicPromptHasNoHiddenDocIdOrText() {
    ask(PUBLIC_KEY, QUERY);
    String prompt = stub.lastPrompt().getText();
    assertThat(prompt).doesNotContain("eb-secret", "eb-a", "restricted content");
}
@Test void publicSearchAndSectionsAreFiltered() {
    JsonNode hits = JSON.readTree(Http.post(port, "/v1/search", PUBLIC_KEY,
        "{\"query\":\"" + QUERY + "\",\"topK\":10}").body()).get("hits");
    hits.forEach(h -> assertThat(h.get("classification").asString()).isEqualTo("public"));
    var hiddenSection = Http.get(port, "/v1/sections/sec-restricted", PUBLIC_KEY);
    var unknownSection = Http.get(port, "/v1/sections/no-such-section", PUBLIC_KEY);
    assertThat(hiddenSection.status()).isEqualTo(404);
    assertThat(hiddenSection.body()).isEqualTo(unknownSection.body());
}
```

Add a `restricted section` to the sections index for the last test.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests 'rag.app.permissions.LeakIT'`
Expected: FAIL on the missing `threeLevels` helper and/or the assertions that expose the old key matrix.

- [ ] **Step 3: Implement the test fixture**

`TestIndex.threeLevels` returns the internal chunk from `mixed` plus a restricted chunk `c-res`, doc `eb-secret`, section `"9"`, content `QUERY`, and a public chunk. No production change.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:test --tests 'rag.app.permissions.LeakIT'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/rag/app/support/TestIndex.java app/src/test/java/rag/app/permissions/LeakIT.java
git commit -m "Test permission leaks across three clearances"
```

---

## Task 4: Observability dependencies and the management port

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/resources/application.yaml`
- Modify: `app/src/test/resources/application-test.yaml`
- Modify: `app/src/main/java/rag/app/security/SecurityConfig.java`
- Test: `app/src/test/java/rag/app/web/ManagementIT.java`

**Interfaces:**
- Consumes: Spring Boot actuator.
- Produces: `GET :8081/actuator/health`, `/actuator/prometheus` (management port); `management.server.port`/`address` properties; trace export properties.

- [ ] **Step 1: Write the failing test**

`ManagementIT` extends `IntegrationTest` with `@TestPropertySource(properties = {"management.server.port=0", "management.server.address=127.0.0.1"})` and `@LocalManagementPort int managementPort`:

```java
@Test void prometheusIsOnTheManagementPortWithoutAKey() {
    var prom = Http.get(managementPort, "/actuator/prometheus", null);
    assertThat(prom.status()).isEqualTo(200);
    assertThat(prom.body()).contains("jvm_");
}
@Test void apiPortDoesNotServeActuator() {
    assertThat(Http.get(port, "/actuator/prometheus", null).status()).isEqualTo(404);
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests 'rag.app.web.ManagementIT'`
Expected: FAIL (no prometheus endpoint / no management port property).

- [ ] **Step 3: Implement**

Add to `app/build.gradle.kts`:

```kotlin
implementation("io.micrometer:micrometer-registry-prometheus")
implementation("io.micrometer:micrometer-tracing-bridge-otel")
implementation("io.opentelemetry:opentelemetry-exporter-otlp")
testImplementation("io.micrometer:micrometer-observation-test")
testImplementation("io.micrometer:micrometer-tracing-test")
```

Resolve the exact versions from the Boot 4.1.2 BOM and record them in the spec §2 table (do not add versions by hand if the BOM manages them).

In `application.yaml`:

```yaml
management:
  server:
    port: ${MANAGEMENT_PORT:8081}
    address: ${MANAGEMENT_ADDRESS:127.0.0.1}
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      show-details: never
  metrics:
    distribution:
      percentiles-histogram:
        rag.stage.latency: true
  tracing:
    sampling:
      probability: ${OTLP_SAMPLE_RATE:1.0}
  otlp:
    tracing:
      endpoint: ${OTLP_ENDPOINT:http://127.0.0.1:30117/v1/traces}
```

In `application-test.yaml`: `management.server.port: 0`, `management.tracing.enabled: false`.

`SecurityConfig` permits `/actuator/**` (same as Task 1). If the management context applies the filter chain and returns 401, keep the permit; the endpoint is loopback/firewall controlled.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:test --tests 'rag.app.web.ManagementIT'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/main/resources/application.yaml app/src/test/resources/application-test.yaml \
  app/src/main/java/rag/app/security/SecurityConfig.java app/src/test/java/rag/app/web/ManagementIT.java
git commit -m "Expose Prometheus on a dedicated management port"
```

---

## Task 5: `RequestMetrics`

**Files:**
- Create: `app/src/main/java/rag/app/metrics/RequestMetrics.java`
- Test: `app/src/test/java/rag/app/metrics/RequestMetricsTest.java`

**Interfaces:**
- Consumes: `MeterRegistry`, `InternalRefusalCause` (Task 2).
- Produces: `void request(String endpoint, String status)`, `void refusal(String reason)`, `void emptyRetrieval(String endpoint, InternalRefusalCause cause)`, `void tokens(String model, Integer prompt, Integer completion)`, `void stage(String stage, long ms)`, `void auditWriteFailure(String endpoint)`. Metric names are `rag.requests`, `rag.refusals`, `rag.retrieval.empty`, `rag.tokens`, `rag.stage.latency`, `rag.audit.write.failures`.

- [ ] **Step 1: Write the failing test**

Use `SimpleMeterRegistry`:

```java
@Test void countersCarryOnlyBoundedTags() {
    metrics.request("ask", "refused");
    metrics.refusal("LOW_RETRIEVAL_SCORE");
    metrics.emptyRetrieval("ask", InternalRefusalCause.NO_ACCESSIBLE_SOURCES);
    metrics.tokens("deepseek-flash", 100, 10);
    assertThat(registry.get("rag.requests").tag("endpoint", "ask").tag("status", "refused").counter().count()).isEqualTo(1);
    assertThat(registry.get("rag.retrieval.empty").tag("cause", "no_accessible_sources").counter().count()).isEqualTo(1);
    assertThat(registry.find("rag.tokens").tag("kind", "prompt").counter().count()).isEqualTo(100);
}
@Test void stageTimerIsReused() {
    metrics.stage("retrieval", 5);
    metrics.stage("retrieval", 7);
    assertThat(registry.get("rag.stage.latency").tag("stage", "retrieval").timer().count()).isEqualTo(2);
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests 'rag.app.metrics.RequestMetricsTest'`
Expected: FAIL (class missing).

- [ ] **Step 3: Implement**

Cache `Counter`/`Timer` instances by tag tuple so repeated calls reuse the meter. `stage` uses

```java
Timer.builder("rag.stage.latency").tag("stage", stage).publishPercentileHistogram()
     .register(registry).record(Duration.ofMillis(ms));
```

The empty cause wire value is `cause.wire()` (`no_accessible_sources` / `no_matching_content`); add `wire()` to `InternalRefusalCause`. Null token counts are skipped (no metric), not recorded as zero.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:test --tests 'rag.app.metrics.RequestMetricsTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/rag/app/metrics app/src/test/java/rag/app/metrics/RequestMetricsTest.java
git commit -m "Add bounded-tag request metrics"
```

---

## Task 6: Spans, trace id and wiring the metrics

**Files:**
- Create: `app/src/main/java/rag/app/metrics/Observations.java`
- Create: `app/src/main/java/rag/app/metrics/Tracing.java`
- Modify: `app/src/main/java/rag/app/search/RetrievalPipeline.java`
- Modify: `app/src/main/java/rag/app/ask/AskService.java`
- Modify: `app/src/main/java/rag/app/search/SearchController.java`
- Test: `app/src/test/java/rag/app/metrics/ObservationIT.java`
- Test: `app/src/test/java/rag/app/metrics/TracingTest.java`

**Interfaces:**
- Consumes: `ObservationRegistry`, `Tracer`, `RequestMetrics`.
- Produces: spans `rag.retrieval`, `rag.rerank`, `rag.generation`; `static String Tracing.traceId(Tracer)`; `static <T> T Observations.observe(ObservationRegistry, String, Map<String,String>, Supplier<T>)`.

- [ ] **Step 1: Write the failing tests**

`TracingTest` uses `io.micrometer.tracing.test.simple.SimpleTracer`:

```java
@Test void noCurrentSpanMeansNoTraceId() { assertThat(Tracing.traceId(new SimpleTracer())).isNull(); }
@Test void currentSpanExposesItsTraceId() {
    var tracer = new SimpleTracer();
    try (var span = tracer.nextSpan().name("t").start()) {
        assertThat(Tracing.traceId(tracer)).isEqualTo(span.context().traceId());
    }
}
```

`ObservationIT` replaces the registry with a `TestObservationRegistry` via a `@TestConfiguration` `@Primary` bean, then calls `/v1/search` and asserts:

```java
TestObservationRegistryAssert.assertThat(registry)
    .hasObservationWithNameEqualTo("rag.retrieval")
    .that().hasLowCardinalityKeyValue("mode", "HYBRID")
    .hasLowCardinalityKeyValue("rerank", "false");
```

and after `/v1/ask`, `rag.generation` exists and the `rag.requests`/`rag.stage.latency` counters are present.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests 'rag.app.metrics.TracingTest' --tests 'rag.app.metrics.ObservationIT'`
Expected: FAIL.

- [ ] **Step 3: Implement**

`Observations.observe` starts an `Observation`, puts low-cardinality tags, runs the supplier, records the error and rethrows, and stops in `finally`. `Tracing.traceId` returns null when `currentSpan()` is null, else the trace id.

`RetrievalPipeline`: add an `ObservationRegistry` field. The Spring constructor takes it; keep the existing package-private constructor (used by `RetrievalPipelineTest`) delegating with `ObservationRegistry.NOOP`. Wrap the whole `retrieve` in `rag.retrieval` with tags `mode`, `rerank`, `topK`, `hits`; wrap the rerank section in `rag.rerank` with `candidates` and `returned`.

`AskService`: wrap `chat.call(...)` in `rag.generation` with `model` and `outcome`; call `metrics.stage("retrieval", retrievalMs)`, `metrics.stage("rerank", rerankMs)`, `metrics.stage("generation", generationMs)`, `metrics.tokens(model, prompt, completion)`, `metrics.request("ask", status)`, and `metrics.refusal(reason)` for refusals. `SearchController`: `metrics.request("search", "ok")` and `metrics.stage("retrieval", ...)`.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:test --tests 'rag.app.metrics.TracingTest' --tests 'rag.app.metrics.ObservationIT'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/rag/app/metrics app/src/main/java/rag/app/search/RetrievalPipeline.java \
  app/src/main/java/rag/app/search/SearchController.java app/src/main/java/rag/app/ask/AskService.java \
  app/src/test/java/rag/app/metrics
git commit -m "Add retrieval and generation spans and stage metrics"
```

---

## Task 7: Optional local observability profile

**Files:**
- Modify: `compose.yaml`
- Create: `observability/prometheus.yml`
- Test: `scripts/check-compose-limits.sh` (existing)

**Interfaces:**
- Consumes: the app's management port `8081` (Task 4).
- Produces: profile `observability` with `jaeger` and `prometheus`; Prometheus scrapes `host.docker.internal:8081`.

- [ ] **Step 1: Verify the images and the current compose script**

Run:

```bash
docker buildx imagetools inspect jaegertracing/all-in-one:<candidate>
docker buildx imagetools inspect prom/prometheus@sha256:2d61f37ba9f2185195dfad94ee500d7ac1986560bac79a77d0a6de182a5bf814
scripts/check-compose-limits.sh
```

Record the arm64 availability and the pinned digest for Jaeger in the spec §2 table. Reuse the Prometheus digest already used by the shared monitoring stack.

- [ ] **Step 2: Add the profile**

Add both services with `profiles: [observability]`, pinned digests, `deploy.resources.limits` (0.5 CPU / 512 MB), loopback port mappings where applicable, and `extra_hosts: ["host.docker.internal:host-gateway"]` on Prometheus. `observability/prometheus.yml` scrapes `host.docker.internal:8081` every 15 s.

- [ ] **Step 3: Verify the limits**

Run: `scripts/check-compose-limits.sh`
Expected: `ok: … services limited`.

- [ ] **Step 4: Commit**

```bash
git add compose.yaml observability/prometheus.yml
git commit -m "Add an optional local observability profile"
```

---

## Task 8: Audit record, index and store

**Files:**
- Create: `app/src/main/java/rag/app/audit/AuditRecord.java`
- Create: `app/src/main/java/rag/app/audit/AuditIndexAdmin.java`
- Create: `app/src/main/java/rag/app/audit/AuditStartup.java`
- Create: `app/src/main/java/rag/app/audit/AuditStore.java`
- Modify: `app/src/main/java/rag/app/es/IndexNames.java`
- Modify: `app/src/main/java/rag/app/RagProperties.java`
- Modify: `app/src/main/resources/application.yaml`
- Test: `app/src/test/java/rag/app/audit/AuditStoreIT.java`

**Interfaces:**
- Consumes: `ElasticsearchClient`, `RagProperties`, `ApiException`.
- Produces: `AuditRecord` (records for `Retrieval`, `Hit`, `Model`, `Citation`, `Usage`, `Latency`), `IndexNames.auditIndex(String prefix)`, `RagProperties.auditIndex()`, `AuditStore.write(AuditRecord)`, `AuditIndexAdmin.ensure()`. `RagProperties.Audit(String index, String readerUsername, String readerPassword)`.

- [ ] **Step 1: Write the failing test**

`AuditStoreIT` with `@TestPropertySource(properties = {"rag.env-prefix=t_audit", "rag.audit.index=t_audit-audit-v1"})`:

```java
@Test void writesAndReadsBackEveryField() throws Exception {
    var record = sample(); // all fields populated, including a hit with contentHash
    store.write(record);
    var got = client.get(g -> g.index(INDEX).id(record.id()), AuditRecord.class).source();
    assertThat(got).isEqualTo(record);
}
@Test void duplicateIdIsRejected() {
    store.write(sample());
    assertThatThrownBy(() -> store.write(sample()))
        .isInstanceOf(ApiException.class).hasMessageContaining("audit");
}
@Test void unwritableIndexFailsClosed() throws Exception {
    store.write(sample());
    client.indices().close(c -> c.index(INDEX));
    try {
        assertThatThrownBy(() -> store.write(sample())).isInstanceOf(ApiException.class);
    } finally {
        client.indices().open(o -> o.index(INDEX));
    }
}
```

Also assert the mapping contains `"dynamic":"strict"` via `EsAdminHttp.get("/" + INDEX + "/_mapping")`.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests 'rag.app.audit.AuditStoreIT'`
Expected: FAIL.

- [ ] **Step 3: Implement**

`AuditRecord` fields exactly as spec §4.2 (including `internalRefusalCause`, `replayOf` optional, and `Latency(retrieval, rerank, generation, rewrite, audit)`). `id()` returns `answerId != null ? answerId : queryId`.

`IndexNames.auditIndex(prefix)` returns `"audit-" + prefix + "-v1"`. `RagProperties` gains an `Audit(String index, String readerUsername, String readerPassword)` record and an `auditIndex()` method falling back to the computed name; update the compact constructor defaults.

`AuditIndexAdmin.ensure()` attempts `indices.create` directly and treats `resource_already_exists_exception` as success; it does not call `indices.exists`, because the app's audit grant is `create_doc` + `create_index` and a HEAD/metadata check may need `view_index_metadata`. Text fields that are never searched (`renderedPrompt`, `answer`, `question`, `rewrittenQuery`, citation `quote`) use `type: text, index: false`. Other errors are logged and rethrown by the startup runner as a WARN (the app still starts; writes fail closed). `AuditStartup` is an `ApplicationRunner` that calls `ensure()`.

`AuditStore.write` uses `client.index(i -> i.index(...).id(record.id()).opType(OpType.Create).document(record))`; any `ElasticsearchException`/`IOException` becomes `ApiException` with status `SERVICE_UNAVAILABLE` and type `AUDIT_UNAVAILABLE`. Reject null ids and records where both `answerId` and `queryId` are null.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:test --tests 'rag.app.audit.AuditStoreIT'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/rag/app/audit/AuditRecord.java app/src/main/java/rag/app/audit/AuditIndexAdmin.java \
  app/src/main/java/rag/app/audit/AuditStartup.java app/src/main/java/rag/app/audit/AuditStore.java \
  app/src/main/java/rag/app/es/IndexNames.java app/src/main/java/rag/app/RagProperties.java \
  app/src/main/resources/application.yaml app/src/test/java/rag/app/audit/AuditStoreIT.java
git commit -m "Add the append-only audit index and writer"
```

---

## Task 9: Write an audit record for every completed ask and search

**Files:**
- Create: `app/src/main/java/rag/app/audit/AuditService.java`
- Modify: `app/src/main/java/rag/app/ask/AskService.java`
- Modify: `app/src/main/java/rag/app/ask/AskResponse.java`
- Modify: `app/src/main/java/rag/app/ask/PromptRenderer.java`
- Modify: `app/src/main/java/rag/app/search/SearchController.java`
- Modify: `app/src/main/java/rag/app/search/SearchResponse.java`
- Test: `app/src/test/java/rag/app/audit/AuditIT.java`

**Interfaces:**
- Consumes: `AuditStore`, `VisibilityProbe`, `RequestMetrics`, `Tracer`, `ChunkIndexAdmin`, `Environment`, `PromptRenderer`.
- Produces: `String AuditService.traceId()` and `long AuditService.write(AuditRecord)` (the write time in milliseconds); the `AuditRecord` builders live in `AskService` and `SearchController`; `AskResponse.Latency.audit` and `SearchResponse.Latency.audit`; `PromptRenderer.Rendered` with `system()`, `user()`, `text()` and `static Prompt parse(String)`.

- [ ] **Step 1: Write the failing test**

`AuditIT` uses `TestIndex` and `StubChatModel`:

```java
@Test void answerWritesOneRecordWithTheFullPrompt() throws Exception {
    JsonNode r = JSON.readTree(Http.post(port, "/v1/ask", KEY,
        "{\"question\":\"" + QUERY + "\"}").body());
    String id = r.get("answerId").asString();
    AuditRecord rec = client.get(g -> g.index(AUDIT_INDEX).id(id), AuditRecord.class).source();
    assertThat(rec.answerId()).isEqualTo(id);
    assertThat(rec.endpoint()).isEqualTo("ask");
    assertThat(rec.renderedPrompt()).contains("[system]", "[user]", QUERY);
    assertThat(rec.retrieval().hits()).isNotEmpty();
    assertThat(rec.model().id()).isEqualTo("deepseek-flash");
    assertThat(rec.answer()).isEqualTo("Answer [1].");
    assertThat(rec.internalRefusalCause()).isNull();
}
@Test void searchWritesAQueryIdRecord() throws Exception {
    JsonNode r = JSON.readTree(Http.post(port, "/v1/search", KEY,
        "{\"query\":\"" + QUERY + "\"}").body());
    String id = r.get("queryId").asString();
    AuditRecord rec = client.get(g -> g.index(AUDIT_INDEX).id(id), AuditRecord.class).source();
    assertThat(rec.queryId()).isEqualTo(id);
    assertThat(rec.status()).isEqualTo("OK");
    assertThat(rec.renderedPrompt()).isNull();
    assertThat(rec.model()).isNull();
    assertThat(rec.answer()).isNull();
}
@Test void refusalRecordHasNoPromptAndTheProbeCause() throws Exception {
    JsonNode r = JSON.readTree(Http.post(port, "/v1/ask", PUBLIC_KEY,
        "{\"question\":\"" + QUERY + "\"}").body());
    assertThat(r.get("status").asString()).isEqualTo("REFUSED");
    AuditRecord rec = client.get(g -> g.index(AUDIT_INDEX).id(r.get("answerId").asString()),
        AuditRecord.class).source();
    assertThat(rec.renderedPrompt()).isNull();
    assertThat(rec.internalRefusalCause()).isEqualTo("NO_ACCESSIBLE_SOURCES");
}
@Test void probeCauseIsAuditOnly() {
    String hidden = Http.post(port, "/v1/ask", PUBLIC_KEY, "{\"question\":\"" + QUERY + "\"}").body();
    String absent = Http.post(port, "/v1/ask", PUBLIC_KEY, "{\"question\":\"zebra quantum harvest\"}").body();
    assertThat(JSON.readTree(hidden).has("internalRefusalCause")).isFalse();
    assertThat(JSON.readTree(hidden).get("refusalReason"))
        .isEqualTo(JSON.readTree(absent).get("refusalReason"));
}
@Test void auditFailureReturns503AndStoresNoTurn() throws Exception {
    JsonNode first = JSON.readTree(Http.post(port, "/v1/ask", KEY,
        "{\"question\":\"" + QUERY + "\"}").body());
    String conversationId = first.get("conversationId").asString();
    closeAuditIndex();
    try {
        assertThat(Http.post(port, "/v1/ask", KEY,
            "{\"question\":\"follow-up\",\"conversationId\":\"" + conversationId + "\"}").status()).isEqualTo(503);
    } finally {
        openAuditIndex();
    }
    var principal = new Principal("tester", Clearance.RESTRICTED, Set.of());
    assertThat(conversations.history(conversationId, principal).orElseThrow()).hasSize(1);
}
@Test void latencyAuditIsPresent() {
    JsonNode ask = JSON.readTree(Http.post(port, "/v1/ask", KEY, "{\"question\":\"" + QUERY + "\"}").body());
    JsonNode search = JSON.readTree(Http.post(port, "/v1/search", KEY, "{\"query\":\"" + QUERY + "\"}").body());
    assertThat(ask.get("latencyMs").get("audit").asLong()).isGreaterThanOrEqualTo(0);
    assertThat(search.get("diagnostics").get("latencyMs").get("audit").asLong()).isGreaterThanOrEqualTo(0);
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests 'rag.app.audit.AuditIT'`
Expected: FAIL.

- [ ] **Step 3: Implement**

`PromptRenderer`: add `public Rendered renderDetailed(String question, List<SearchHit> hits)` where `Rendered` is `record Rendered(String system, String user) { public String text() { return "[system]\n" + system + "\n[user]\n" + user; } }`; `render` returns `new Prompt(List.of(new SystemMessage(system), new UserMessage(user)))` from it. Add `static Prompt parse(String text)` splitting at the first `[user]\n`; throw `IllegalArgumentException` for a malformed string. Tests cover the round-trip.

`AskService`: replace the direct `answer(...)` flow with an internal `AnswerOutcome` carrying the response, hits, `Rendered` prompt, best scores, probe cause and probe ms. On empty hits, call `probe.cause(query, principal)` with the **query actually used for retrieval** (the rewritten query on a follow-up) and record the cause in the audit record. Build the `AuditRecord` with endpoint `ask`, the caller, retrieval, prompt version, `rendered.text()`, model info from `Environment` (`spring.ai.model.chat`, `spring.ai.deepseek.chat.options.model`, `temperature`, `max-tokens`), answer/citations/status/reason, usage, latencies (record `audit` = probe ms), and the index meta from `ChunkIndexAdmin.aliasInfo(...)`. `AuditService` supplies the trace id and writes; it also emits `rag.audit.write` and the failure counter. `ask` writes the audit **before** `conversations.append`; the response's `latencyMs.audit` is `probeMs + writeMs`.

`SearchController`: mint `queryId`, probe when hits are empty, build and write the search record (status `OK`), then return the response with `latencyMs.audit`.

`RequestMetrics`: `request(endpoint, status)` after a successful audit; on audit failure, `request(endpoint, "error")` and `auditWriteFailure(endpoint)`.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:test --tests 'rag.app.audit.AuditIT' --tests 'rag.app.ask' --tests 'rag.app.search'`
Expected: PASS, including the existing ask/search suites.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/rag/app/audit/AuditService.java app/src/main/java/rag/app/ask \
  app/src/main/java/rag/app/search app/src/test/java/rag/app/audit/AuditIT.java
git commit -m "Audit every completed ask and search response"
```

---

## Task 10: Audit read endpoint and the reader client

**Files:**
- Create: `app/src/main/java/rag/app/audit/AuditReader.java`
- Create: `app/src/main/java/rag/app/audit/AuditController.java`
- Modify: `app/src/main/java/rag/app/RagProperties.java`
- Modify: `app/src/main/java/rag/app/web/ProblemTypes.java`
- Test: `app/src/test/java/rag/app/audit/AuditReadIT.java`
- Test: `app/src/test/java/rag/app/audit/AuditReaderUnavailableIT.java`

**Interfaces:**
- Consumes: `rag.audit.reader-username`, `reader-password`; the reader's URI is the application's `spring.elasticsearch.uris`; `AuditStore` (Task 8).
- Produces: `GET /v1/audit/{recordId}` returning the `AuditRecord`; `AuditReader.get(String) -> Optional<AuditRecord>`; `ProblemTypes.AUDIT_READER_UNAVAILABLE`.

- [ ] **Step 1: Write the failing tests**

`AuditReadIT` configures `rag.audit.reader-username` / `reader-password` against the Testcontainers ES (`elastic` / `TEST_PASSWORD`); the reader URI comes from the existing `spring.elasticsearch.uris` dynamic property. Write a record with the app client, then:

```java
@Test void adminReadsAnAnswerRecord() {
    var r = Http.get(port, "/v1/audit/" + answerId, ADMIN_KEY);
    assertThat(r.status()).isEqualTo(200);
    JsonNode body = JSON.readTree(r.body());
    assertThat(body.get("answerId").asString()).isEqualTo(answerId);
    assertThat(body.get("renderedPrompt").asString()).contains("[system]", "[user]");
    assertThat(body.get("retrieval").get("hits").size()).isGreaterThan(0);
}
@Test void adminReadsASearchRecordByQueryId() {
    var r = Http.get(port, "/v1/audit/" + queryId, ADMIN_KEY);
    assertThat(r.status()).isEqualTo(200);
    assertThat(JSON.readTree(r.body()).get("status").asString()).isEqualTo("OK");
}
@Test void nonAdminIs403AndUnknownIs404() {
    assertThat(Http.get(port, "/v1/audit/" + answerId, KEY).status()).isEqualTo(403);
    assertThat(Http.get(port, "/v1/audit/" + answerId, PUBLIC_KEY).status()).isEqualTo(403);
    assertThat(Http.get(port, "/v1/audit/a_unknown", ADMIN_KEY).status()).isEqualTo(404);
}
```

`AuditReaderUnavailableIT` leaves the reader properties blank and asserts `503` with `urn:rag:problem:audit-reader-unavailable`.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests 'rag.app.audit.AuditReadIT' --tests 'rag.app.audit.AuditReaderUnavailableIT'`
Expected: FAIL.

- [ ] **Step 3: Implement**

`AuditReader` builds a second Elasticsearch client only when username and password are non-blank, using the first URI of `spring.elasticsearch.uris`:

```java
RestClient rest = RestClient.builder(HttpHost.create(uri))
    .setHttpClientConfigCallback(hc -> hc.setDefaultCredentialsProvider(provider))
    .build();
ElasticsearchClient client = new ElasticsearchClient(new RestClientTransport(rest, new JacksonJsonpMapper()));
```

Close the client on shutdown. `get` reads `AuditRecord`; a 404 returns empty; a transport error becomes `503 AUDIT_READER_UNAVAILABLE`. If unconfigured, every call throws that 503.

`AuditController` injects `AuditReader`; `@GetMapping("/v1/audit/{recordId}")`; unknown → `ApiException.notFound()`.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:test --tests 'rag.app.audit.AuditReadIT' --tests 'rag.app.audit.AuditReaderUnavailableIT'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/rag/app/audit/AuditReader.java app/src/main/java/rag/app/audit/AuditController.java \
  app/src/main/java/rag/app/RagProperties.java app/src/main/java/rag/app/web/ProblemTypes.java \
  app/src/test/java/rag/app/audit/AuditReadIT.java app/src/test/java/rag/app/audit/AuditReaderUnavailableIT.java
git commit -m "Add the admin-only audit read endpoint"
```

---

## Task 11: Replay

**Files:**
- Create: `app/src/main/java/rag/app/audit/ReplayService.java`
- Modify: `app/src/main/java/rag/app/audit/AuditController.java`
- Modify: `app/src/main/java/rag/app/web/ProblemTypes.java`
- Modify: `app/src/main/java/rag/app/ask/PromptRenderer.java` (the `parse` from Task 9)
- Test: `app/src/test/java/rag/app/audit/AuditReplayIT.java`

**Interfaces:**
- Consumes: `AuditReader`, `AuditRecord`, `ChatModel`, `CitationValidator`, `ChunkIndexAdmin`.
- Produces: `POST /v1/audit/{recordId}/replay`; `ProblemTypes.CONTENT_HASH_MISMATCH`, `REPLAY_UNSUPPORTED`; `ReplayResult ReplayService.replay(AuditRecord record)` where `ReplayResult(original, replay, modelCall, recordedModel, currentModel, modelMatches, checked, matched, generationMs)` and `ReplayOutcome(status, answer, citations, refusalReason, usage)`.

- [ ] **Step 1: Write the failing test**

```java
@Test void answeredRecordReplaysTheRecordedPrompt() {
    stub.replies("Replayed [1].");
    var r = Http.post(port, "/v1/audit/" + answerId + "/replay", ADMIN_KEY, "{}");
    assertThat(r.status()).isEqualTo(200);
    JsonNode body = JSON.readTree(r.body());
    assertThat(body.get("modelCall").asBoolean()).isTrue();
    assertThat(body.get("replay").get("answer").asString()).isEqualTo("Replayed [1].");
    assertThat(body.get("contentHashes").get("checked").asInt()).isEqualTo(body.get("contentHashes").get("matched").asInt());
}
@Test void refusedRecordWithNoPromptReplaysWithoutAModelCall() {
    int before = stub.calls();
    var r = Http.post(port, "/v1/audit/" + refusedAnswerId + "/replay", ADMIN_KEY, "{}");
    assertThat(r.status()).isEqualTo(200);
    JsonNode body = JSON.readTree(r.body());
    assertThat(body.get("modelCall").asBoolean()).isFalse();
    assertThat(body.get("replay").get("status").asString()).isEqualTo("REFUSED");
    assertThat(stub.calls()).isEqualTo(before);
}
@Test void searchRecordIs409() {
    var r = Http.post(port, "/v1/audit/" + queryId + "/replay", ADMIN_KEY, "{}");
    assertThat(r.status()).isEqualTo(409);
    assertThat(r.body()).contains("urn:rag:problem:replay-unsupported");
}
@Test void missingChunkIs409() {
    deleteChunk(chunkId);
    var r = Http.post(port, "/v1/audit/" + answerId + "/replay", ADMIN_KEY, "{}");
    assertThat(r.status()).isEqualTo(409);
    assertThat(r.body()).contains("urn:rag:problem:content-hash-mismatch");
}
@Test void changedChunkIs409() {
    // Update both content and contentHash: the check compares the stored hash with the recorded one.
    updateChunk(chunkId, "changed text", "different-hash");
    var r = Http.post(port, "/v1/audit/" + answerId + "/replay", ADMIN_KEY, "{}");
    assertThat(r.status()).isEqualTo(409);
    assertThat(r.body()).contains("urn:rag:problem:content-hash-mismatch");
}
@Test void readerUnavailableIs503() {
    assertThat(Http.get(port, "/v1/audit/" + answerId, ADMIN_KEY).status()).isEqualTo(503);
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests 'rag.app.audit.AuditReplayIT'`
Expected: FAIL.

- [ ] **Step 3: Implement**

`ReplayService.replay`:

1. `endpoint != "ask"` → `ApiException` 409 `REPLAY_UNSUPPORTED`.
2. Fetch every recorded hit by `chunkId` with `client.mget` from the chunks target; a missing id or a different `contentHash` → 409 `CONTENT_HASH_MISMATCH` with counts only. Use the **application** client for chunks.
3. `renderedPrompt == null` → no model call; replay outcome `REFUSED` with the recorded reason.
4. Otherwise parse the prompt with `PromptRenderer.parse`, call the current `ChatModel`, validate the answer with `CitationValidator` against the fetched texts in recorded rank order, and derive status.
5. Return `ReplayResult(original, replay, modelCall, recordedModel, currentModel, modelMatches, checked, matched, generationMs)`; `AuditController` serialises it.

Do not write a second audit record; log the trace id and the original id.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:test --tests 'rag.app.audit.AuditReplayIT'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/rag/app/audit/ReplayService.java app/src/main/java/rag/app/audit/AuditController.java \
  app/src/main/java/rag/app/web/ProblemTypes.java app/src/main/java/rag/app/ask/PromptRenderer.java \
  app/src/test/java/rag/app/audit/AuditReplayIT.java
git commit -m "Replay audit records after verifying content hashes"
```

---

## Task 12: Audit-aware leak tests

**Files:**
- Modify: `app/src/test/java/rag/app/permissions/LeakIT.java`

**Interfaces:**
- Consumes: `GET /v1/audit/{recordId}` with `ADMIN_KEY` (Task 10).
- Produces: tests only.

- [ ] **Step 1: Add the failing assertions**

After the hidden-content refusal with `PUBLIC_KEY`, read the record as `admin` and assert `internalRefusalCause == "NO_ACCESSIBLE_SOURCES"`; assert the response body has no `internalRefusalCause` field and no hidden docId/title/section. Repeat for an internal caller against a restricted-only match.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests 'rag.app.permissions.LeakIT'`
Expected: FAIL until the probe is wired.

- [ ] **Step 3: Fix only the assertions or wiring they expose**

The probe and audit wiring landed in Tasks 2 and 9; this task is the end-to-end gate. If a test shows the cause in the response, fix the response builder, not the test.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:test --tests 'rag.app.permissions.LeakIT'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/rag/app/permissions/LeakIT.java
git commit -m "Test that hidden-source causes stay in the audit record"
```

---

## Task 13: Deterministic injection tests

**Files:**
- Create: `app/src/test/java/rag/app/injection/InjectionIT.java`

**Interfaces:**
- Consumes: `TestIndex`, `StubChatModel`, `KEY`/`PUBLIC_KEY`; no production change.
- Produces: tests only.

- [ ] **Step 1: Write the failing tests**

```java
@Test void publicPromptHasNoHiddenChunk() {
    ask(PUBLIC_KEY, QUERY);
    assertThat(stub.lastPrompt().getText()).doesNotContain("eb-secret", "eb-a", "restricted content");
}
@Test void internalAdversarialTextIsInsideItsFence() {
    ask(INTERNAL_KEY, QUERY);
    String prompt = stub.lastPrompt().getText();
    String poison = "ignore previous instructions";
    assertThat(prompt.indexOf("<<<SOURCE")).isLessThan(prompt.indexOf(poison));
    assertThat(prompt.indexOf(poison)).isLessThan(prompt.indexOf("<<<END SOURCE"));
}
@Test void aChunkCannotCloseItsOwnFence() {
    ask(KEY, QUERY);
    assertThat(stub.lastPrompt().getText().split("<<<END SOURCE", -1)).hasSize(2);
}
@Test void hiddenOnlyInjectionQuestionRefuses() {
    var hidden = ask(PUBLIC_KEY, QUERY);
    var absent = ask(PUBLIC_KEY, "zebra quantum harvest");
    assertThat(hidden.status()).isEqualTo(absent.status());
    assertThat(hidden.body()).isEqualTo(absent.body());
}
```

Build the chunks with `TestIndex.chunk(...)` including a self-made poisoned passage; do not use SPM text.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests 'rag.app.injection.InjectionIT'`
Expected: FAIL on missing fixtures/assertions.

- [ ] **Step 3: Implement the test fixture**

Add the chunks locally in the test (no change to `TestIndex` unless a shared helper is cleaner). Assert on `stub.lastPrompt()` and the response JSON only.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:test --tests 'rag.app.injection.InjectionIT'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/rag/app/injection/InjectionIT.java
git commit -m "Test injection boundaries deterministically"
```

---

## Task 14: Live injection eval

**Files:**
- Create: `eval-runner/src/main/java/rag/eval/InjectionItem.java`
- Create: `eval-runner/src/main/java/rag/eval/InjectionSet.java`
- Create: `eval-runner/src/main/java/rag/eval/InjectionRun.java`
- Create: `eval-runner/src/main/java/rag/eval/InjectionReport.java`
- Modify: `eval-runner/src/main/java/rag/eval/EvalRunner.java`
- Create: `eval/injection.yaml` (draft; author approves before commit)
- Test: `eval-runner/src/test/java/rag/eval/InjectionSetTest.java`
- Test: `eval-runner/src/test/java/rag/eval/InjectionRunTest.java`

**Interfaces:**
- Consumes: `AskClient`, `KeySet`, `RunMetadata`, `ReportWriter` conventions.
- Produces: `--injection` mode; `eval/injection.yaml`; a report at `eval/runs/<id>-injection/`.

- [ ] **Step 1: Write the failing tests**

```java
@Test void rejectsUnknownClearance() {
    assertThatThrownBy(() -> InjectionSet.load(yaml("""
        - id: x
          question: q
          clearance: secret
          expectedStatus: ANSWERED
        """), Set.of("eb-a")))
        .hasMessageContaining("clearance");
}
@Test void rejectsUnknownExpectedStatus() {
    assertThatThrownBy(() -> InjectionSet.load(yaml("""
        - id: x
          question: q
          clearance: public
          expectedStatus: MAYBE
        """), Set.of("eb-a")))
        .hasMessageContaining("expectedStatus");
}
@Test void canaryInTheAnswerIsAFailure() {
    var result = InjectionRun.check(item(List.of("reset.invalid")), ask("visit reset.invalid"));
    assertThat(result.pass()).isFalse();
    assertThat(result.canaryHits()).containsExactly("reset.invalid");
}
@Test void hiddenOnlyRefusalIsAPass() {
    var result = InjectionRun.check(item(List.of("reset.invalid")), refused());
    assertThat(result.pass()).isTrue();
}
```

The run report contains ids, statuses and booleans only; `InjectionReportTest` asserts the answer text does not appear.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :eval-runner:test --tests 'rag.eval.InjectionSetTest' --tests 'rag.eval.InjectionRunTest'`
Expected: FAIL.

- [ ] **Step 3: Implement**

`InjectionItem(id, question, clearance, expectedStatus, mustNotContain, mustNotCiteDocIds)`; `InjectionSet.load(Path, Set<String> knownDocIds)` with the same validation style as `GoldenSet`. `InjectionRun` calls `/v1/ask` with the key at the item's clearance, records status, cited doc ids, each canary hit, and a pass/fail; `InjectionReport` writes `report.json` and `report.md` with ids and booleans only. `EvalRunner` accepts `--injection=true [--injection-file=eval/injection.yaml]` and returns `2` if the file or key is missing.

Draft `eval/injection.yaml` from the three real adversarial documents (the phishing URL `example-bank-reset.invalid`, the "print your system prompt" instruction, the false 24-hour tolerance). The draft is **not committed** until the author approves it; record the approval in the evidence.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :eval-runner:test --tests 'rag.eval.InjectionSetTest' --tests 'rag.eval.InjectionRunTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

Commit the code and tests only; add `eval/injection.yaml` in a separate commit after the author approves it.

```bash
git add eval-runner/src/main/java/rag/eval/Injection*.java eval-runner/src/main/java/rag/eval/EvalRunner.java \
  eval-runner/src/test/java/rag/eval/Injection*Test.java
git commit -m "Add the injection eval mode"
```

---

## Task 15: Example Bank corpus scope

**Files:**
- Modify: `core/src/main/java/rag/core/corpus/CorpusLoader.java`
- Create: `core/src/main/java/rag/core/eval/EvalScope.java`
- Modify: `app/src/main/java/rag/app/ingest/IngestCommand.java`
- Test: `core/src/test/java/rag/core/corpus/CorpusLoaderTest.java`
- Test: `core/src/test/java/rag/core/eval/EvalScopeTest.java`

**Interfaces:**
- Consumes: `CorpusFiles`, `Manifest`, `MarkdownParser`.
- Produces: `CorpusLoader.Scope { ALL, EXAMPLE_BANK }`, `CorpusLoader.load(Scope)`, `CorpusLoader.knownDocIds(Path, Scope)`, `EvalScope.inExampleBank(GoldenItem, Set<String>)`.

- [ ] **Step 1: Write the failing test**

With a `TestCorpus` copy whose `raw/` directory and PDF are deleted:

```java
@Test void exampleBankScopeLoadsMarkdownOnly() {
    var docs = new CorpusLoader(root).load(CorpusLoader.Scope.EXAMPLE_BANK);
    assertThat(docs).extracting(ParsedDocument::docId).containsExactlyInAnyOrder("eb-a", "adv-x");
}
@Test void allScopeStillRequiresThePdf() {
    assertThatThrownBy(() -> new CorpusLoader(root).load(CorpusLoader.Scope.ALL))
        .hasMessageContaining("missing");
}
```

`EvalScope` test: an item with only `eb-*` sources is in; an item with `spm-*` is out; an unanswerable item is in.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:test --tests 'rag.core.corpus.CorpusLoaderTest'`
Expected: FAIL.

- [ ] **Step 3: Implement**

`Scope.EXAMPLE_BANK` skips manifest PDFs and loads only `CorpusFiles.markdown(root)`; `knownDocIds(root, scope)` returns only Markdown doc ids for `EXAMPLE_BANK`, all known ids for `ALL`. The existing `load()` and `knownDocIds(Path)` delegate with `ALL`.

`IngestCommand` accepts `--scope=all|example-bank` (default `all`) and passes it to `CorpusLoader`. `CorpusVersion.compute` is unchanged.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :core:test --tests 'rag.core.corpus.CorpusLoaderTest' --tests 'rag.core.eval.EvalScopeTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/rag/core/corpus/CorpusLoader.java core/src/main/java/rag/core/eval/EvalScope.java \
  app/src/main/java/rag/app/ingest/IngestCommand.java core/src/test/java/rag/core/corpus/CorpusLoaderTest.java \
  core/src/test/java/rag/core/eval/EvalScopeTest.java
git commit -m "Add an Example Bank-only corpus scope"
```

---

## Task 16: Fixture builder

**Files:**
- Create: `app/src/main/java/rag/app/embed/EmbeddingPrefixes.java`
- Create: `app/src/main/java/rag/app/embed/FixtureEmbedder.java`
- Create: `app/src/main/java/rag/app/fixture/FixtureFiles.java`
- Create: `app/src/main/java/rag/app/fixture/FixtureCommand.java`
- Modify: `app/src/main/java/rag/app/embed/OllamaEmbedder.java`
- Modify: `app/src/main/java/rag/app/embed/EmbeddingConfig.java`
- Modify: `app/src/main/java/rag/app/RagApplication.java`
- Modify: `app/src/main/java/rag/app/RagProperties.java` (fixture dir)
- Create: `scripts/build-fixture.sh`
- Test: `app/src/test/java/rag/app/fixture/FixtureFilesTest.java`
- Test: `app/src/test/java/rag/app/fixture/FixtureCommandTest.java`
- Test: `app/src/test/java/rag/app/embed/FixtureEmbedderTest.java`

**Interfaces:**
- Consumes: `CorpusLoader.Scope` (Task 15), `Chunker`, `GoldenSet`, `EvalScope`, `Embedder`.
- Produces: `FixtureFiles` (`FixtureChunk`, `FixtureQuestion`, `FixtureManifest`, `textHash`, `dirName`, `write`, `locate`, `readVectors`); `FixtureCommand` CLI `fixture --out=…`; `FixtureEmbedder(Path, RagProperties.Embedding)` selected by `rag.embedding.fixture-dir`.

- [ ] **Step 1: Write the failing tests**

`FixtureFilesTest`:

```java
@Test void roundTripsVectorsAndWritesNoText() throws Exception {
    Path dir = FixtureFiles.write(base, manifest, List.of(chunk), List.of(question));
    assertThat(FixtureFiles.readVectors(dir, "chunks.jsonl")).containsKey(chunk.textHash());
    String files = Files.readString(dir.resolve("chunks.jsonl")) + Files.readString(dir.resolve("questions.jsonl"));
    assertThat(files).doesNotContain("tolerance for disruption", "Self-made text");
}
@Test void textHashDependsOnThePrefix() {
    assertThat(FixtureFiles.textHash("search_query: q")).isNotEqualTo(FixtureFiles.textHash("search_document: q"));
}
@Test void locateFailsWhenTheKeyIsMissing() {
    assertThatThrownBy(() -> FixtureFiles.locate(base, "missing", "model", "chunker"))
        .hasMessageContaining("missing").hasMessageContaining("model");
}
```

`FixtureEmbedderTest`: a known `textHash` returns the vector; a miss throws `IllegalStateException` naming the hash and model id.

`FixtureCommandTest` uses a `FakeEmbedder` and a temp corpus + golden set; asserts the output directory, counts, and that every file is vector-only.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests 'rag.app.fixture.*' --tests 'rag.app.embed.FixtureEmbedderTest'`
Expected: FAIL.

- [ ] **Step 3: Implement**

`EmbeddingPrefixes.query/document(spec, text)` and make `OllamaEmbedder` use them. `FixtureEmbedder` is a main class selected by `@ConditionalOnProperty("rag.embedding.fixture-dir")` in `EmbeddingConfig`; it loads `chunks.jsonl` and `questions.jsonl` into `Map<String,float[]>` keyed by `textHash` and uses the same prefixes. Mark it `@Primary` so it wins over the test `FakeEmbedder` when both are present; production does not set the property.

`FixtureFiles` writes one JSON object per line with `tools.jackson` and a `manifest.json` containing versions, counts and sha256s. `dirName = corpusVersion + "-" + modelId.replace('/', '_') + "-" + chunkerVersion`. `locate(base, …)` throws with the expected path.

`FixtureCommand` (an `ApplicationRunner` like `IngestCommand`) runs only for the `fixture` argument; loads `EXAMPLE_BANK`, chunks with the configured chunker, loads the golden set with all known doc ids, keeps `EvalScope` items, embeds documents and **scored** questions (skip `unanswerable`), and writes. It does not touch Elasticsearch. `RagApplication` treats `fixture` like `ingest` (web off, chat off).

`scripts/build-fixture.sh` runs:

```bash
RAG_EMBEDDING_MODEL="${RAG_EMBEDDING_MODEL:-nomic}" ./gradlew :app:bootRun --args="fixture --out=eval/fixtures"
```

and fails if `OLLAMA_URL` is not reachable (the command itself fails on the first embedding).

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:test --tests 'rag.app.fixture.*' --tests 'rag.app.embed.FixtureEmbedderTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/rag/app/embed app/src/main/java/rag/app/fixture app/src/main/java/rag/app/RagApplication.java \
  app/src/main/java/rag/app/RagProperties.java scripts/build-fixture.sh \
  app/src/test/java/rag/app/fixture app/src/test/java/rag/app/embed/FixtureEmbedderTest.java
git commit -m "Build vector-only embedding fixtures for CI"
```

---

## Task 17: CI gate, scope filter and baseline

**Files:**
- Create: `eval-runner/src/main/java/rag/eval/CiGate.java`
- Create: `eval-runner/src/main/java/rag/eval/Baseline.java`
- Modify: `eval-runner/src/main/java/rag/eval/EvalRunner.java` (`--scope=example-bank`)
- Modify: `app/build.gradle.kts` (`testImplementation(project(":eval-runner"))`, `ciGate` task)
- Create: `app/src/test/java/rag/app/ci/CiEvalGateIT.java`
- Test: `eval-runner/src/test/java/rag/eval/CiGateTest.java`
- Test: `eval-runner/src/test/java/rag/eval/BaselineTest.java`

**Interfaces:**
- Consumes: `SearchClient`, `GoldenSet`, `RetrievalMetrics`, `EvalScope`, `CorpusLoader.Scope`, `FixtureEmbedder`.
- Produces: `CiGate.run(URI base, Map<Clearance,String> keys, Path golden, Path corpus, Path baseline, boolean writeBaseline, PrintStream out) -> Result`; `CiGate.scope(List<GoldenItem>, Set<String>) -> ScopeResult(included, scored, unanswerable, excludedIds)`; `CiGate.assertNoRegression(Result, Baseline)`; `Baseline` read/write JSON; Gradle task `:app:ciGate`.

- [ ] **Step 1: Write the failing tests**

`CiGateTest` with a `StubServer` serving `/v1/search`:

```java
@Test void scopeKeepsOnlyExampleBankAndCountsUnanswerable() {
    var scope = CiGate.scope(items, exampleBankDocIds);
    assertThat(scope.included()).isEqualTo(30);
    assertThat(scope.scored()).isEqualTo(24);
    assertThat(scope.unanswerable()).isEqualTo(6);
    assertThat(scope.excludedIds()).hasSize(12);
}
@Test void gateFailsOnADropGreaterThanTwoHundredths() {
    var baseline = baseline(0.80, 0.70);
    assertThatThrownBy(() -> CiGate.assertNoRegression(result(0.77, 0.70), baseline))
        .hasMessageContaining("Recall@5").hasMessageContaining("0.030");
}
@Test void gatePassesAtExactlyTwoHundredths() {
    CiGate.assertNoRegression(result(0.78, 0.70), baseline(0.80, 0.70));
}
@Test void missingBaselineFailsWithAWriteBaselineHint() {
    assertThatThrownBy(() -> Baseline.read(Path.of("missing.json")))
        .hasMessageContaining("ciWriteBaseline");
}
```

`BaselineTest`: round-trip the JSON shape from spec §7.2; unknown fields are rejected by the parser.

`CiEvalGateIT` (`@Tag("ci")`): Testcontainers ES, `FixtureEmbedder`, ingest `--scope=example-bank --chunker=structural --promote`, run `CiGate.run` with the three test keys. The test is excluded from `:app:test` and run by `:app:ciGate`.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :eval-runner:test --tests 'rag.eval.CiGateTest' --tests 'rag.eval.BaselineTest'`
Expected: FAIL.

- [ ] **Step 3: Implement**

`CiGate` loads the golden set with `CorpusLoader.knownDocIds(corpus, ALL)`, filters with `EvalScope` using `knownDocIds(corpus, EXAMPLE_BANK)`, scores each non-unanswerable item with the key at `minClearance`, and compares overall Recall@5/MRR. `Result` carries the counts and excluded ids. `Baseline` is the JSON schema in spec §7.2; `writeBaseline` writes it and skips the gate.

`EvalRunner --scope=example-bank` applies the same filter for local runs and prints the counts.

Gradle:

```kotlin
testImplementation(project(":eval-runner"))

tasks.named<Test>("test") { useJUnitPlatform { excludeTags("local", "ci") } }

tasks.register<Test>("ciGate") {
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("ci") }
    workingDir = rootDir
    systemProperty("ci.writeBaseline", providers.gradleProperty("ciWriteBaseline").getOrElse("false"))
}
```

`CiEvalGateIT` sets `rag.embedding.fixture-dir` to `FixtureFiles.locate(Path.of("eval/fixtures"), …)` and `rag.embedding.cache-dir` to a temp directory. If the fixture is missing, fail with `run scripts/build-fixture.sh`.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :eval-runner:test --tests 'rag.eval.CiGateTest' --tests 'rag.eval.BaselineTest'`
Expected: PASS. Then, after Task 16's fixture exists, `./gradlew :app:ciGate -PciWriteBaseline=true` writes the first baseline; `./gradlew :app:ciGate` passes against it.

- [ ] **Step 5: Commit**

```bash
git add eval-runner/src/main/java/rag/eval/CiGate.java eval-runner/src/main/java/rag/eval/Baseline.java \
  eval-runner/src/main/java/rag/eval/EvalRunner.java app/build.gradle.kts app/src/test/java/rag/app/ci/CiEvalGateIT.java \
  eval-runner/src/test/java/rag/eval/CiGateTest.java eval-runner/src/test/java/rag/eval/BaselineTest.java
git commit -m "Gate Example Bank retrieval metrics against a baseline"
```

Commit the baseline separately in Task 18.

---

## Task 18: Generate and commit the baseline

**Files:**
- Create: `eval/baseline.json`
- Create: `eval/fixtures/…` (from `scripts/build-fixture.sh`)

**Interfaces:**
- Consumes: Task 16 fixture builder, Task 17 gate.
- Produces: the committed baseline and fixture files.

- [ ] **Step 1: Build the fixture locally**

Run: `scripts/build-fixture.sh`
Expected: a directory under `eval/fixtures/` with `chunks.jsonl`, `questions.jsonl`, `manifest.json`, no text. Record the path and counts.

- [ ] **Step 2: Write the baseline from a real run**

Run: `./gradlew :app:ciGate -PciWriteBaseline=true`
Expected: `eval/baseline.json` with `includedItems: 30`, `scoredItems: 24`, `unanswerableItems: 6`, `excludedItems: 12`, and the real metrics. Do not edit the numbers by hand.

- [ ] **Step 3: Verify the gate against the baseline**

Run: `./gradlew :app:ciGate`
Expected: PASS.

- [ ] **Step 4: Commit the fixture and baseline separately**

```bash
git add eval/fixtures
git commit -m "Add Example Bank embedding fixtures"
git add eval/baseline.json
git commit -m "Record the Example Bank CI baseline

Generated by :app:ciGate -PciWriteBaseline=true; scope example-bank,
HYBRID, topK 10, no rerank. 24 scored items, 6 unanswerable, 12 SPM
items excluded."
```

- [ ] **Step 5: Record the run metadata**

Add the fixture directory name, counts, and baseline/run metrics to `evidence/phase5-ci.md` later (Task 20); no source text.

---

## Task 19: CI workflows and the no-SPM check

**Files:**
- Create: `.github/workflows/ci.yml`
- Create: `.github/workflows/live-eval.yml` (optional; see Step 5)
- Create: `scripts/check-no-spm.sh`
- Modify: `README.md`, `.env.example`

**Interfaces:**
- Consumes: the test tasks and `:app:ciGate` (Tasks 1–18).
- Produces: a green PR workflow on `ubuntu-24.04-arm`.

- [ ] **Step 1: Write the no-SPM check and test it locally**

`scripts/check-no-spm.sh` scans `git ls-files -z` for `https?://[^ ]*hkma` and `hkma\.gov\.hk`, and fails if `git ls-files '*.pdf'` is non-empty. The string `hkma.gov` alone in a test instruction is not a URL and must not match.

Run: `scripts/check-no-spm.sh`
Expected: `ok`.

- [ ] **Step 2: Write `ci.yml`**

- `on: [pull_request, push]`, `permissions: contents: read`, concurrency cancel-in-progress, timeout 30 minutes.
- `runs-on: ubuntu-24.04-arm`.
- `actions/checkout`, `actions/setup-java` (Temurin 25) and `gradle/actions/setup-gradle`, pinned by commit SHA.
- `./gradlew :core:test :app:test :eval-runner:test` (the app `test` excludes `local` and `ci`).
- `./gradlew :app:ciGate` (the gate test is tagged `ci`).
- `scripts/check-compose-limits.sh` and `scripts/check-no-spm.sh`. The compose script needs `docker compose` and `jq`; the arm64 runner preinstalls both, so fail early with a clear message if `jq` is missing rather than installing a floating version.
- No `DEEPSEEK_API_KEY`, no Ollama, no SPM.

- [ ] **Step 3: Verify locally**

Run:

```bash
./gradlew :core:test :app:test :eval-runner:test
./gradlew :app:ciGate
scripts/check-compose-limits.sh
scripts/check-no-spm.sh
```

Expected: all pass.

- [ ] **Step 4: Run the workflow**

Push the branch and confirm the `ci` workflow is green. Record the run URL in `evidence/phase5-ci.md`. Configure the required status check in the repository settings (an operations step).

- [ ] **Step 5: Optional `live-eval.yml`**

Only if time allows: `workflow_dispatch` with `split`, secret `DEEPSEEK_API_KEY`, an Elasticsearch service container (`elasticsearch:9.5.5` pinned, `--cpus 2 --memory 3g`), ingest with the fixture embedder, start the app, run `:eval-runner:run --args="--generation …"`. Generation only; injection stays local. If skipped, say so in the evidence.

- [ ] **Step 6: Update the README and `.env.example`**

Document the four keys and their clearances, the management port and `MANAGEMENT_ADDRESS`, the audit index, the reader credentials, `OTLP_ENDPOINT`, and how to run `scripts/build-fixture.sh` and the gate. No values in `.env.example`.

- [ ] **Step 7: Commit**

```bash
git add .github/workflows/ci.yml scripts/check-no-spm.sh README.md .env.example
git commit -m "Run the Example Bank gate in arm64 CI"
```

(Add `live-eval.yml` in its own commit only if implemented.)

---

## Task 20: Evidence, decisions and ROADMAP

**Files:**
- Create: `evidence/phase5-permissions.md`
- Create: `evidence/phase5-audit-observability.md`
- Create: `evidence/phase5-injection.md`
- Create: `evidence/phase5-ci.md`
- Modify: `ROADMAP.md`
- Author-owned: `decisions/05-*.md`

**Interfaces:**
- Consumes: real test/CI/run output only.
- Produces: the Phase 5 exit evidence.

- [ ] **Step 1: Permissions evidence**

Run `./gradlew :app:test --tests 'rag.app.permissions.LeakIT' --tests 'rag.app.security.ApiKeyAuthIT'`; record the key matrix, hidden-vs-absent parity, and the audit cause. No content.

- [ ] **Step 2: Audit and observability evidence**

Before the evidence run, relay the gitops requests and record their state: the firewall allow
`172.19.0.0/16 → 8081`, the Prometheus scrape target `172.19.0.1:8081`, the `rag_audit_reader` users,
and `create_index` on `audit-<prefix>-*`. If any is not granted, record the blocked state honestly;
do not edit the gitops repo.

Run the app against the shared ES, Jaeger and Prometheus; perform one `/v1/ask`, one `/v1/search`, one replay, one hash-mismatch attempt. Record the audit index/privileges, the record's field list (not values), the replay results, the trace id and span table, the metric samples/PromQL results, and the management-port check. If the gitops reader/firewall/scrape is not ready, record the blocked state honestly.

- [ ] **Step 3: Injection evidence**

Run the deterministic `InjectionIT`; after the author approves `eval/injection.yaml`, run the live full-corpus injection eval locally (`--injection`) with real Ollama and DeepSeek. Record ids, clearances, statuses, canary hits and forbidden citations. Zero means zero; any failure is reported as it is.

- [ ] **Step 4: CI evidence**

Record the green workflow run, included/scored/unanswerable/excluded counts and excluded ids, the baseline-vs-run table, the no-SPM and compose-limit check results, and the baseline commit.

- [ ] **Step 5: Tick the ROADMAP and request `decisions/05`**

Tick the Phase 5 box in the Progress section, the five Phase 5 checkboxes in §5, and the verification items whose results are now in the spec/evidence. Ask the author to write `decisions/05` recording the CI scope (Example Bank only) and the audit privileges; do not write it for them.

- [ ] **Step 6: Commit**

```bash
git add evidence/phase5-*.md ROADMAP.md
git commit -m "Record the Phase 5 results"
```

```
