# 01 · Corpus and stack

- **Status**: Accepted
- **Date**: 2026-11-08
- **Provenance**: drafted by Claude from decisions the author made in a working session;
  reviewed and approved by the author.

## Context

Verified on 2026-11-08 (details in `docs/specs/00-overall-design.md` §10):
- Spring Boot 4.1.2 is the latest GA. Spring AI 2.0.x is compatible: Initializr pairs 2.0.1,
  and 2.0.2 is the latest GA on Maven Central.
- DeepSeek's API model ids are now `deepseek-flash` and `deepseek-v4-pro`. Reasoning is a
  request parameter, not a model id. ROADMAP §2 still names `deepseek-chat` /
  `deepseek-reasoner`, which are no longer documented.
- The HKMA terms of use (last revised 2019-08-26) are unchanged: non-commercial download and
  reproduction in original format with attribution; linking requires written consent.
- SPM versions, from the PDF covers: TM-G-1 V.1 (2003), TM-G-2 V.2 (2022), OR-1 V.2 (2022),
  OR-2 V.1 (2022), SA-2 V.1 (2001). Superseded V.1 files of TM-G-2 and OR-1 are still online.
- The SPM says "tolerance for disruption" (never "impact tolerance") and has no "RTO".

## Decision 1: SPM modules in scope

Use the five candidate modules: TM-G-1, TM-G-2, OR-1, OR-2, SA-2.

They cover technology risk, business continuity, operational risk, operational resilience
and outsourcing, which are exactly the topics the Example Bank documents implement, so they
support multi-hop questions. TM-G-1 (2003) and SA-2 (2001) are old but are the current
versions. The `sha256` in `corpus/manifest.yaml` pins the exact files.

## Decision 2: generation and judge models

- Generation: `deepseek-flash`.
- Judge: `deepseek-v4-pro` with thinking enabled.

Both are configuration properties. The judge is from the same model family as the
generator, which may bias it; Phase 3 measures judge–human agreement as the safeguard
(ROADMAP §2).

## Decision 3: Spring AI version

Spring AI **2.0.2** (latest GA patch of the line Initializr pairs with Boot 4.1.2). Fall back
to 2.0.1 only if 2.0.2 proves incompatible, and record it here.

## Decision 4: CI download URLs (ROADMAP §8)

**Deferred.** Neither option (a) repository variable nor (b) HKMA written consent is taken
now. Until decided, CI runs only the Example Bank part of the corpus; the SPM part is
evaluated locally. Revisit before the Phase 5 CI gate.

## Decision 5: shared middleware on the development server

| Component | Where it runs | Notes |
|---|---|---|
| Elasticsearch | Shared compose stack in the server's gitops repo | Isolated prod/dev pools: index patterns `rag-*` / `rag_dev-*`, users `rag` / `rag_dev` |
| Ollama | Shared compose stack in the server's gitops repo | Models are stateless and shared; no prod/dev split |
| Jaeger | Existing k3s `mesh-observability` instance | OTLP/HTTP `localhost:30117`, OTLP/gRPC `localhost:30116`; not reachable from the public IP (tested 2026-11-08) |
| Prometheus / Grafana | Existing shared compose `monitoring` stack | Scrape job added in Phase 5 |

This repo's `compose.yaml` keeps optional services for people running the project
elsewhere, but this server uses the shared stacks. Integration tests still use
Testcontainers, never the shared Elasticsearch.

## Consequences

- The ROADMAP §1.1 exact-term examples (`RTO`, `impact tolerance`) are not SPM terms. The
  golden set's exact-term items use SPM wording and Example Bank terms.
- Elasticsearch index names gain an environment prefix, which amends the index naming in
  spec 00 §5. The Phase 1 spec makes this concrete.
- Shared Elasticsearch adds noise from other tenants to latency numbers. Eval reports record
  the machine, so this is acceptable.
- The k3s Jaeger keeps in-memory storage with tight limits; traces are for debugging, not
  evidence.
