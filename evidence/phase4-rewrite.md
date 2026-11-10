# Phase 4 · Multi-turn query rewriting

- **Date**: 2026-11-10
- **Spec**: `docs/specs/04-rerank-rewrite.md` §6, §7.2 (plan: `docs/specs/04-rerank-rewrite-plan.md`)
- **Purpose**: measure whether rewriting a follow-up into a standalone query (`rewrite.v1`) recovers the retrieval a
  context-dependent follow-up loses.

All numbers come from eval-runner output (`eval/runs/…`, not committed) produced at commit `48f3161` with a clean working
tree. Item ids and counts only: no follow-up text, rewritten query or answer appears here (they stay in the run
directory's `rewrites.jsonl`).

## 1. Configuration

| Item | Value |
|---|---|
| Set | `eval/multi-turn.yaml`: 12 two-turn items (8 dev, 4 test), each built from a pair of golden items |
| Index | `rag_dev-chunks-nomic768-struct400-v1` (structural chunks, `nomic-embed-text`), corpus `9dd88979729b` |
| Retrieval | `HYBRID`, no rerank; follow-up retrieval scored at top 10 with the item's expected sources |
| Generator and rewriter | `deepseek-flash`; answer prompt `answer.v2`, rewrite prompt `rewrite.v1` (max 128 tokens) |
| Caller | the key at each item's `minClearance` for every call |
| Runs | dev `20261110T073058Z-multi-turn`, test `20261110T073135Z-multi-turn` (test run once) |

Variants of the follow-up retrieval, all scored with the same `/v1/search` call:
**raw** = the follow-up as typed, without context (lower bound); **rewrite** = the query `/v1/ask` rewrote it to
inside the conversation; **standalone** = the question of the golden item the follow-up stands for (upper bound).

An earlier pair of runs at `18d2844` (`20261110T072649Z`, `…T072713Z`) failed on all 12 items: every live rewrite
returned `503`, because the rewriter passed generic chat options that `DeepSeekChatModel` cannot cast. Fixed in
`48f3161` (with a test); those runs are discarded.

## 2. Follow-up retrieval

| Split | Variant | n | Recall@5 | MRR |
|---|---|---|---|---|
| dev | raw | 8 | 0.813 | 0.875 |
| dev | rewrite | 8 | 1.000 | 0.754 |
| dev | standalone | 8 | 0.938 | 0.792 |
| test | raw | 4 | 0.625 | 0.625 |
| test | rewrite | 4 | 0.875 | 0.667 |
| test | standalone | 4 | 0.875 | 0.646 |

Failures: none on either split.

## 3. Cost of the rewrite

| | dev (n=8) | test (n=4) |
|---|---|---|
| Rewrite latency, mean (`latencyMs.rewrite`) | 1582 ms | 1574 ms |
| Rewrite latency, range | 1045 to 2007 ms | 1093 to 1951 ms |

The rewrite is one extra model call per follow-up; a first turn makes none. `usage` on a follow-up sums the rewrite
and the answer call, so the rewrite's own tokens cannot be separated from runner output. Follow-up totals ranged from
975 to 1666 prompt tokens and 86 to 1004 completion tokens.

## 4. Fallbacks

Two items returned a `rewrittenQuery` identical to the follow-up: m02 (dev) and m11 (test). For m11 the app log records
a fallback (an unusable model reply, so the original question was used). The dev run's app log was overwritten by the
test run, so for m02 a fallback cannot be told apart from a rewrite that left the text unchanged.

## 5. Headline

On test (n=4), rewriting lifted follow-up Recall@5 from **0.625 (raw) to 0.875**, equal to the standalone question
(0.875), for about 1.6 s of rewrite latency per follow-up. On dev (n=8): 0.813 to 1.000 (standalone 0.938).

## 6. Observations (from the numbers above)

- The gain comes from the follow-ups whose raw form retrieves nothing relevant: m01 (dev) and m10 (test) go from
  Recall@5 0.0 raw to 1.0 rewritten; m04 (dev) goes from 0.5 to 1.0.
- Rewrite matches or beats standalone on Recall@5 on both splits, but its MRR is lower on dev (0.754 against 0.792):
  m03 and m06 find the source at ranks 3 and 5 rather than 1 and 3.
- On every item where the raw follow-up already reached Recall@5 1.0 (dev m02, m03, m05, m06, m07, m08; test m09,
  m11), the rewrite kept it at 1.0, sometimes at a lower rank (m03, m06, m09). On m12 (test) all three variants reach
  0.5, and the rewrite ranks lower than raw (MRR 0.333 against 0.5).
- Rewrite and fallback both landed on m11 with the same scores as raw (1.0 / 1.0), so the fallback cost nothing there.
- The samples are tiny (8 and 4 items); one test item moves Recall@5 by 0.25. These figures show the mechanism works,
  not its size.
- The rewritten query is logged at `INFO` with the `answerId`; writing it to the audit index is Phase 5 (spec §6.2).
