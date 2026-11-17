# Phase 5 · Permissions

- **Date**: 2026-11-17
- **Spec**: `docs/specs/05-productionisation.md` §3 (plan: `docs/specs/05-productionisation-plan.md` Tasks 1–3, 12)
- **Purpose**: prove the clearance pre-filter and the hidden-vs-absent refusal parity across `/v1/ask`,
  `/v1/search` and `/v1/sections`, and that the internal refusal cause never reaches the caller.

All results come from the test suite; tests use self-made text and the committed golden set only. No source
text appears here.

## 1. Configuration

| Key | Principal | Clearance | Roles |
|---|---|---|---|
| `RAG_KEY_DEV` | `dev` | restricted | – |
| `RAG_KEY_INTERNAL` | `carol` | internal | – |
| `RAG_KEY_PUBLIC` | `bob` | public | – |
| `RAG_KEY_ADMIN` | `admin` | restricted | `admin` |

The pre-filter stays in the kNN `filter` and BM25 `bool.filter`; there is no post-filter. The audit endpoints
require `ROLE_admin`; a missing key is `401`, an authenticated non-admin `403` problem+json.

## 2. Test record

| Test class | n | Result | What it covers |
|---|---|---|---|
| `rag.app.security.ApiKeyAuthIT` | 6 | pass | missing/wrong key 401, valid key, admin role 403/503, actuator not on the API port |
| `rag.app.permissions.LeakIT` | 6 | pass | hidden-vs-absent body parity, internal vs restricted, prompt contains no hidden chunk, search and sections filters, audit-only cause |
| `rag.app.audit.AuditIT` | 6 | pass | probe cause recorded in audit and absent from the response |
| `rag.app.injection.InjectionIT` | 4 | pass | public prompt contains no hidden chunk, fenced adversarial text, delimiter neutralisation, hidden-only refusal parity |

`LeakIT.publicAndAbsentBodiesMatch` compares the field names, `refusalReason`, `answer`, `citations`, `usage` and
the generation/rerank latencies of a hidden-only refusal against an absent-content refusal; they match. A public
caller's prompt for a visible question contains neither `eb-*` hidden ids nor their text.

## 3. Internal cause

`VisibilityProbe.cause` runs one count-only query over the levels above the caller's clearance after a filtered
retrieval returns zero hits. `LeakIT.publicHiddenCauseIsAuditOnly` and `internalRestrictedCauseIsAuditOnly` read the
record as `admin` and see `NO_ACCESSIBLE_SOURCES`; the response has no such field. The probe is lexical; a hidden
document matching only semantically is counted as `NO_MATCHING_CONTENT` (spec §3.2 limitation).

## 4. Pending

None for the permission tests. The audit-reader gitops credentials are an audit dependency, not a permission
one; see `phase5-audit-observability.md`.
