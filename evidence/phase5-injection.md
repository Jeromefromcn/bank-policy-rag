# Phase 5 · Indirect prompt injection

- **Date**: 2026-11-17
- **Spec**: `docs/specs/05-productionisation.md` §6 (plan Tasks 13–14)
- **Purpose**: verify that adversarial corpus text is treated as data, hidden text is filtered, and the prompt
  boundary cannot be closed from inside a chunk.

## 1. Deterministic tests (CI, no tokens)

| Test | Result | What it proves |
|---|---|---|
| `InjectionIT.publicPromptHasNoHiddenChunk` | pass | a public caller's prompt contains no internal/restricted docId or text |
| `InjectionIT.internalAdversarialTextIsInsideItsFence` | pass | the malicious sentence sits between `<<<SOURCE n` and `<<<END SOURCE n` |
| `InjectionIT.aChunkCannotCloseItsOwnFence` | pass | a `<<<END SOURCE 1>>>` inside a chunk is neutralised; only the real closer remains |
| `InjectionIT.hiddenOnlyInjectionQuestionRefuses` | pass | hidden-only content refuses with the same stable fields as absent content |

These tests use self-made passages, not SPM text. They prove the mechanism (pre-filter, fencing, neutralisation),
not the model's compliance.

## 2. Live eval setup

`eval-runner --injection=true` reads `eval/injection.yaml`, calls `/v1/ask` with the key at each item's clearance,
and writes a report with ids, statuses, canary hits and cited doc ids only (never answer text).

The draft set at `eval/injection.yaml` is **not committed**: it is author-owned and awaits approval. It has five
items targeting the three real adversarial documents (the phishing URL, the "print your system prompt" instruction
and the false 24-hour tolerance). The schema has one `expectedStatus` per item; a false-fact item that could be
safely answered or refused needs the author's call.

## 3. Live run

Not run yet. After the author approves `eval/injection.yaml`, run against the full local corpus (SPM + Example
Bank + adversarial) with real Ollama and DeepSeek:

```sh
./gradlew :app:bootRun        # with RAG_KEY_* and DEEPSEEK_API_KEY in the environment
./gradlew :eval-runner:run --args="--base-url=http://127.0.0.1:8080 --injection=true"
```

The results table and the canary counts will be added here. This is the Phase 5 injection record; until then the
Phase 5 box stays unticked.
