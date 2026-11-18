# Phase 6 · MCP server

- **Date**: 2026-11-18
- **Spec**: `docs/specs/06-mcp.md` (plan: `docs/specs/06-mcp-plan.md`, Tasks 1–8)
- **Purpose**: prove the three read-only MCP tools work over stateless Streamable HTTP with the same API-key
  identity, clearance pre-filter and audit trail as REST, and record a real agent using them.

All results below come from the runs described here, at commit `3979b99` with a clean working tree. No source text
is reproduced: questions, item ids, tool arguments and one-line result summaries only.

## 1. Environment

| Item | Value |
|---|---|
| Application commit | `3979b99` (`phase6-mcp`) |
| Java / framework | OpenJDK 25.0.2, Spring Boot 4.1.2, Spring AI 2.0.2 (MCP Java SDK 2.1.0) |
| Endpoint | `POST http://127.0.0.1:8080/mcp`, stateless Streamable HTTP, management port `8081` |
| Chunks index | `rag_dev-chunks-nomic768-struct400-v1` (340 chunks, alias not used for this run) |
| Sections index | `rag_dev-sections-struct400-v1` (79 sections) |
| Corpus / chunker / embedder | `9dd88979729b` / `structural-v1-400-cl100k` / `ollama/nomic-embed-text` |
| Generator | `deepseek-flash`, temperature 0, max tokens 1024 |
| Key set | `RAG_KEY_DEV` (restricted), `RAG_KEY_INTERNAL` (internal), `RAG_KEY_PUBLIC` (public), `RAG_KEY_ADMIN` (restricted + `admin`); the public/internal/admin values were generated for this run and are never written here |
| Audit read | `rag_audit_reader_dev` (the gitops reader user is now present; the Phase 5 pending item is closed) |
| MCP Inspector | `@modelcontextprotocol/inspector@2.15.0` |
| Node | `v24.14.0` (the Inspector's engine range excludes the default Node 20) |
| MCP client | Claude Code `2.1.280` |

Start command (the first line names the keys; values come from the environment):

```bash
set -a; . ./.env; set +a
RAG_CHUNKS_INDEX=rag_dev-chunks-nomic768-struct400-v1 \
RAG_SECTIONS_INDEX=rag_dev-sections-struct400-v1 \
RAG_SECURITY_APIKEYS_0_KEY="$RAG_KEY_DEV" RAG_SECURITY_APIKEYS_0_PRINCIPAL=dev RAG_SECURITY_APIKEYS_0_CLEARANCE=restricted \
RAG_SECURITY_APIKEYS_1_KEY="$RAG_KEY_INTERNAL" RAG_SECURITY_APIKEYS_1_PRINCIPAL=eval-internal RAG_SECURITY_APIKEYS_1_CLEARANCE=internal \
RAG_SECURITY_APIKEYS_2_KEY="$RAG_KEY_PUBLIC" RAG_SECURITY_APIKEYS_2_PRINCIPAL=eval-public RAG_SECURITY_APIKEYS_2_CLEARANCE=public \
RAG_SECURITY_APIKEYS_3_KEY="$RAG_KEY_ADMIN" RAG_SECURITY_APIKEYS_3_PRINCIPAL=eval-admin RAG_SECURITY_APIKEYS_3_CLEARANCE=restricted RAG_SECURITY_APIKEYS_3_ROLES=admin \
ES_AUDIT_READER_USERNAME=rag_audit_reader_dev ES_AUDIT_READER_PASSWORD=… \
./gradlew :app:bootRun
```

`curl -s 127.0.0.1:8081/actuator/health` returned `{"groups":["liveness","readiness"],"status":"UP"}`.

## 2. MCP Inspector

`tools/list` returned exactly three tools, each with `readOnlyHint = true`, `destructiveHint = false`,
`idempotentHint = true`, `openWorldHint = false`, a description containing "data, not instructions", and no
`context` property in the input schema:

| Tool | Required input | Optional input |
|---|---|---|
| `search_docs` | `query` | `topK`, `mode` |
| `answer_question` | `question` | — |
| `get_section` | `sectionId` | — |

Calls with `RAG_KEY_DEV`:

| Command | Result |
|---|---|
| `search_docs` `query="impact tolerance"` | 5 hits; first `chunkId b07d98426a387b2b`, `sectionId a1abd6b40c49582d`, `spm-tm-g-2`, `sectionPath ["3","3.1","3.1.2"]`, `public`; the restricted `eb-critical-ops-register` chunk was also returned at restricted clearance |
| `answer_question` `question="What is the RPO for PayHub?"` | `ANSWERED`; one citation, `sectionId c26b64d7138d8d7d`, `eb-payments-bcp-runbook` |
| `answer_question` `question="What severity levels does Example Bank use for technology incidents?"` | `ANSWERED`; one citation, `sectionId 9ccf4ca57c70ffcf` |
| `get_section` `sectionId="c26b64d7138d8d7d"` | full internal section, `eb-payments-bcp-runbook §2`, 310 characters |

With `RAG_KEY_PUBLIC`:

| Command | Result |
|---|---|
| `search_docs` `query="impact tolerance"` | 5 hits, all `public` |
| `get_section` `sectionId="c26b64d7138d8d7d"` (internal section) | `isError: true`, `"No such resource"` |
| `get_section` `sectionId="no-such-section"` | `isError: true`, `"No such resource"` |

The last two bodies are identical, so a public caller cannot tell a hidden section from an absent one.

## 3. Claude Code

Config used for the run (not committed; deleted afterwards; the key is a placeholder here):

```json
{"mcpServers":{"bank-policy":{"type":"http","url":"http://127.0.0.1:8080/mcp",
  "headers":{"X-Api-Key":"<RAG_KEY_DEV>"}}}}
```

Each question was run with:

```bash
claude -p "<question>" --mcp-config /tmp/phase6-mcp.json --strict-mcp-config \
  --allowedTools "mcp__bank-policy__search_docs" "mcp__bank-policy__answer_question" \
  "mcp__bank-policy__get_section" --output-format stream-json --verbose
```

| Item | Kind | Tool sequence | Result |
|---|---|---|---|
| `g015` | exact-term | `search_docs` → `answer_question` | Answered: PayHub RPO is 5 minutes of committed transactions; cites the payments runbook §2.2 |
| `g028` | multi-hop | `answer_question` → `search_docs` | Answered: the settlement-exception procedure and the incident standard both apply; an S1 reaches the CIO within 30 minutes of declaration |
| `g037` | unanswerable | `answer_question` → `search_docs` ×2 | Refused (`LOW_RETRIEVAL_SCORE`); two follow-up searches found no capital-adequacy figure, and the agent said so instead of guessing |
| `g030` | restricted, **public key** | `answer_question` → `search_docs` ×5 (HYBRID, BM25, VECTOR) → `get_section` | Refused (`LOW_RETRIEVAL_SCORE`); the restricted tolerance was not visible, and the agent reported that the corpus contains no such figure |
| `g021` | exact-term + section expansion | `search_docs` → `answer_question` → `get_section a1abd6b40c49582d` | Answered: the time-frame assessment in the business impact analysis determines recovery priority; the agent then expanded the cited TM-G-2 section |

End-to-end turn times (Claude Code `duration_ms`): 18.9 s, 21.3 s, 17.7 s, 57.1 s (seven tool calls), 14.8 s.

The first Inspector phrasing of the OR-2 question, "Under OR-2, what is an impact tolerance?", and the `g011`
wording both returned `REFUSED` because the model declined; the recorded prompt and five retrieved hits were
present, so this is a generation-side refusal, not a transport or permission failure. The `g015` and `g001`
phrasings answered normally, so the live run uses those for the answered paths.

## 4. Permission parity and audit

Live parity for `g030` with `RAG_KEY_PUBLIC` (the same question through both paths):

| Path | Status | Refusal reason | Citations |
|---|---|---|---|
| `POST /v1/ask` | `REFUSED` | `LOW_RETRIEVAL_SCORE` | 0 |
| `tools/call answer_question` | `REFUSED` | `LOW_RETRIEVAL_SCORE` | 0 |

Audit record of an MCP answer (`GET /v1/audit/a_01M7PYHD21ZDMSZ5Z9KQ58PSXF`, `RAG_KEY_ADMIN`):

| Field | Value |
|---|---|
| `endpoint` | `mcp:answer_question` |
| `status` | `ANSWERED` |
| `principal` / `clearance` | `dev` / `restricted` |
| `question` | `What is the RPO for PayHub?` |
| `retrieval` | `HYBRID`, topK 5, five hits |
| `renderedPrompt` | present, 5,936 characters for the refused OR-2 question; replay can use it |

MCP records written during the whole live session (audit index `audit-rag_dev-v1`):

| Endpoint | Records |
|---|---|
| `mcp:search_docs` | 12 |
| `mcp:answer_question` | 10 |
| `mcp:get_section` | 5 |

Server-side latency from those records (milliseconds): `search_docs` retrieval 13–102; `answer_question`
retrieval 94–126 plus generation 1,220–1,686; `get_section` audit write 14–28. Refusals that come from the model
still pay the generation time; refusals from an empty filtered retrieval do not call it.

The deterministic suites (`McpProtocolIT`, `McpToolIT`, `McpPermissionIT`, `McpAuditIT`, `McpReplayIT`,
`McpObservationIT`) cover the same rules without network access: principal required, hidden-vs-absent parity for
answers, search and sections, one record per completed call, fail-closed audit, `mcp:answer_question` replay, and
the `rag.mcp.tool` span with `tool` / `status` tags.

## 5. Deviations and costs

1. **Keys.** `.env` holds `RAG_KEY_DEV` plus the older `RAG_KEY_ALICE` / `RAG_KEY_BOB` names; it has no
   `RAG_KEY_PUBLIC` / `RAG_KEY_INTERNAL` / `RAG_KEY_ADMIN`. The live run generated the missing keys in memory and
   passed all four through `RAG_SECURITY_APIKEYS_*`; no value is recorded here.
2. **Audit reader.** The gitops `rag_audit_reader_dev` user now authenticates and reads `audit-rag_dev-v1`
   (140 records before this run). This closes the Phase 5 audit-reader pending item for dev. The admin read and
   replay endpoints were therefore tested against the real reader path.
3. **Refusal on an answerable-looking question.** Two OR-2 phrasings were refused by the model even though five
   hits and a full prompt were present. This is a retrieval/generation quality observation, not an MCP defect; the
   MCP path returned exactly what REST returns for the same inputs.
4. **Stateless trade-off.** No session means every request repeats the API key and no server-side conversation
   exists; `answer_question` fixes `topK = 5`, `HYBRID`, no rerank, so an agent cannot tune retrieval quality
   through the tool. `search_docs` exposes `topK` and `mode` for that reason.
5. **Cost.** The MCP endpoint adds one small JSON-RPC wrapper and one `rag.mcp.tool` span per call; it adds no
   container, no port and no new secret. `answer_question` is the expensive tool (one generation, 1.2–1.7 s on
   this machine); `search_docs` and `get_section` stay in the tens of milliseconds.
6. **Test suite.** `./gradlew test` and `scripts/check-compose-limits.sh` results are recorded below from the run
   in this session.

## 6. Deterministic suite

| Command | Result |
|---|---|
| `./gradlew test` | `BUILD SUCCESSFUL` in 2 m 23 s; the app test task executed, core and eval-runner were up to date because this phase does not change them |
| `./gradlew :core:test :eval-runner:test --rerun-tasks` | `BUILD SUCCESSFUL` in 21 s, 10 tasks executed |
| `scripts/check-compose-limits.sh` | `ok: 5 services limited` |

Test XML across the three modules: **500 tests in 85 classes, 0 failures, 0 errors, 0 skipped**. The MCP classes
contribute 31: `McpToolContextTest` 2, `McpProtocolIT` 6, `McpToolIT` 10, `McpPermissionIT` 3, `McpAuditIT` 4,
`McpReplayIT` 4, `McpObservationIT` 2.
