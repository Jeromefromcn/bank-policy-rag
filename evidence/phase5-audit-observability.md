# Phase 5 · Audit and observability

- **Date**: 2026-11-17
- **Spec**: `docs/specs/05-productionisation.md` §4–§5 (plan Tasks 4–11)
- **Purpose**: append-only audit with replay, and the tracing/metrics surface.

All results come from Testcontainers ES and the unit/integration suites. Record contents are never quoted; only
field names, counts and outcomes appear here.

## 1. Audit

| Item | Value |
|---|---|
| Index | `audit-<envPrefix>-v1` (default `audit-rag_dev-v1`) |
| Mapping | explicit, `dynamic: strict`, one shard, replicas 0 |
| Write | `op_type=create`, `_id` = `a_…` (ask) or `q_…` (search) |
| Fields | §4.3 plus `internalRefusalCause`; text never searched (`index: false`) |
| Reader | separate user from `rag.audit.reader-username` / `reader-password`; blank → 503 |

| Test class | n | Result | What it covers |
|---|---|---|---|
| `rag.app.audit.AuditStoreIT` | 4 | pass | every field round-trips, strict mapping, duplicate id rejected, closed index fails closed |
| `rag.app.audit.AuditIT` | 6 | pass | ask/search records, full prompt, null prompt on pre-generation refusal, probe cause, `503` + no conversation turn, `latencyMs.audit` |
| `rag.app.audit.AuditReadIT` | 3 | pass | admin reads ask and search records, non-admin 403, unknown 404 |
| `rag.app.audit.AuditReaderUnavailableIT` | 2 | pass | GET and replay answer `503 audit-reader-unavailable` without reader credentials |
| `rag.app.audit.AuditReplayIT` | 5 | pass | answered replay, no-prompt refusal replay without a model call, search record 409, missing and changed chunk 409 |

Fail-closed: with the audit index closed, a `/v1/ask` with a `conversationId` returns `503`; the conversation
history still has one turn, so no turn the caller did not receive is stored. Replay verifies every recorded
`contentHash` with the application client (the reader is granted only `audit-*`) and reports a count-only 409 on a
mismatch. Replay does not write a second audit record.

## 2. Observability

| Item | Value |
|---|---|
| Tracing | Micrometer Tracing 1.7.2 bridge, OpenTelemetry 1.62.0 exporter; OTLP/HTTP to `OTLP_ENDPOINT` |
| Spans | `rag.retrieval` (`mode`, `rerank`, `topK`, `hits`), `rag.rerank` (`candidates`, `returned`), `rag.generation` (`model`), `rag.audit.write` (`endpoint`) |
| Metrics | Micrometer 1.17.2, Prometheus registry: `rag.requests`, `rag.refusals`, `rag.retrieval.empty`, `rag.tokens`, `rag.stage.latency`, `rag.audit.write.failures` |
| Management | `MANAGEMENT_PORT` (8081) and `MANAGEMENT_ADDRESS` (default loopback); `health`, `info`, `prometheus`; the API port serves no actuator |
| Versions endpoint | authenticated `GET /v1/info` (the eval runner's version map) |

| Test class | n | Result | What it covers |
|---|---|---|---|
| `rag.app.metrics.RequestMetricsTest` | 3 | pass | bounded tags, timer reuse, null token counts skipped |
| `rag.app.metrics.TracingTest` | 2 | pass | trace id with and without a current span |
| `rag.app.metrics.ObservationIT` | 2 | pass | `rag.retrieval` tags, `rag.generation`, request/stage/token meters |
| `rag.app.web.ManagementIT` | 3 | pass | Prometheus and health on the management port without a key; API port 404 |

## 3. Pending

The live trace and Prometheus scrape evidence depend on the gitops changes: `rag_audit_reader` /
`rag_audit_reader_dev`, `create_index` on `audit-<prefix>-*`, the host firewall allow for `172.19.0.0/16 → 8081`
and the shared Prometheus scrape target `172.19.0.1:8081`. Until they are granted, the record is the test suite
above; the spec §2 rows remain pending. The app starts and audits with the write user; only read/replay and the
shared scrape are blocked.
