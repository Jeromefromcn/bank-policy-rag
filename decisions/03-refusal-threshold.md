# 03 · Refusal threshold

- **Status**: Accepted
- **Date**: 2026-11-09
- **Provenance**: drafted by Claude from real runner output (`eval/runs/20261109T023629Z-sweep`, `…T121211Z-generation`,
  `…T121627Z-generation`, `…T132526Z-generation`). The author verified the choice of option A, the figures and the
  trade-off. The g036 ruling below was made by Claude at the author's request.

## Context

`/v1/ask` refuses without calling the model when the best raw cosine similarity of the vector leg is below
`rag.ask.min-similarity` (`docs/specs/03-generation.md` §3). RRF scores cannot be used because they only carry rank.
The default is `0.0`, which turns the gate off, until this decision exists.

The sweep ran retrieval only (no LLM tokens) on the `dev` split, `HYBRID`, structural chunks
(`structural-v1-400-cl100k`), `nomic-embed-text`, corpus `9dd88979729b`, commit `20253d3`, clean tree. Each answerable
item ran with the key at its `minClearance`. Unanswerable items ran with the `restricted` key. Restricted items also
ran with a key one level below `minClearance` (they must be refused).

`dev` sample sizes: **26 answerable** (exact-term 6, factual 10, multi-hop 5, restricted 5), **4 unanswerable**,
**5 restricted-below-clearance**. With 4 unanswerable items one item moves a rate by 25 points; with 5 restricted
items by 20 points. `test` was not used.

## Sweep results (selected rows)

Rates are the share refused at that threshold. "False refusal" is the share of answerable items refused.

| Threshold | Unanswerable refused (n=4) | Restricted refused (n=5) | False refusal (n=26) | False refusal, exact-term (n=6) | multi-hop (n=5) | factual (n=10) |
|---|---|---|---|---|---|---|
| ≤ 0.65 | 0% | 0% | 0% | 0% | 0% | 0% |
| 0.68 | 25% | 0% | 7.7% | 17% | 20% | 0% |
| 0.70 | 50% | 20% | 15.4% | 50% | 20% | 0% |
| 0.72 | 75% | 40% | 23.1% | 67% | 40% | 0% |
| 0.74 | 100% | 60% | 34.6% | 83% | 60% | 0% |
| 0.76 | 100% | 60% | 50.0% | 100% | 80% | 10% |
| 0.80 | 100% | 80% | 65.4% | 100% | 100% | 20% |
| ≥ 0.85 | 100% | 100% | 100% | 100% | 100% | 100% |

## What the numbers say

- Similarities sit in a narrow band, roughly 0.65 to 0.85. Answerable and unanswerable questions overlap in it,
  so no threshold separates them cleanly.
- The first threshold that refuses every `dev` unanswerable item is **0.74**. It also refuses about a third of
  answerable items, and 5 of 6 `exact-term` items.
- `exact-term` and `multi-hop` are hit first. A vector-only gate penalises exactly the questions BM25 answers well
  (module codes, paragraph numbers), because the gate ignores the lexical leg (spec §3.1).
- At low thresholds the gate does almost nothing. Refusals of `unanswerable` and `restricted` items would then rely on
  the model's `NO_ANSWER` reply, whose accuracy this sweep does not measure.

## Measured end to end (dev, two runs)

Two generation runs on `dev` (commit `5f98366`, clean tree, same index, `deepseek-flash` generator, `deepseek-v4-pro`
judge, `answer.v2`): the gate off, then `min-similarity = 0.70`. Runner output:
`eval/runs/20261109T121211Z-generation` (gate off) and `eval/runs/20261109T121627Z-generation` (0.70).

| Metric (dev) | Gate off | Gate 0.70 |
|---|---|---|
| False refusal, all answerable (n=26) | 19.2% (5) | 30.8% (8) |
| False refusal, exact-term (n=6) | 0% | 50% |
| False refusal, multi-hop (n=5) | 60% | 60% |
| Unanswerable correctly refused (n=4) | 4/4 | 4/4 |
| Restricted below clearance refused (n=5) | 4/5 | 4/5 |
| Faithfulness (supported/points) | 0.886 (31/35) | 0.867 (26/30) |
| Citation support (supported/sentences) | 0.846 (33/39) | 0.903 (28/31) |

- With the gate off, every refusal came from the model's `NO_ANSWER`; there were no citation validation failures.
- The gate at 0.70 refused the same unanswerable and restricted items that the model already refused, and added three
  false refusals, all `exact-term` (g015, g017, g022). On this sample it bought no refusal accuracy.
- The five false refusals with the gate off are g011, g012 (factual) and g023, g025, g028 (multi-hop). Multi-hop is
  the weakest category in both runs.
- The one restricted-below-clearance call that was answered is g036. It cited only `eb-payments-bcp-runbook`
  (classified `internal`), which the internal-clearance caller may see; the restricted register was not cited. The
  golden item expects both documents, so the metric counts it as not refused. The author should decide whether that
  item should count as a refusal case for a caller with `internal` clearance.
- Sample sizes are small (4 unanswerable, 5 restricted, 26 answerable). One item moves a rate by 20 to 25 points.

## Options

**A. No gate (`min-similarity = 0.0`).** Refusals come only from the model (`NO_ANSWER`) and citation validation.
No false refusals from the gate. Unanswerable questions rely on the prompt. Cheapest to explain; spends a model call
on every question.

**B. Conservative gate (about 0.68 to 0.70).** Removes the clearly irrelevant queries and catches up to half of the
`dev` unanswerable items. False refusal 8% to 15%, concentrated in `exact-term`.

**C. Strict gate (about 0.74).** Refuses all `dev` unanswerable items. False refusal 35%, 83% on `exact-term`.
The cost on questions that should be answered is high.

**D. Gate on a different signal.** Not available in Phase 3. Phase 4 may re-sweep with the rerank score
(ROADMAP §5 Phase 4), which usually separates better than a bi-encoder cosine.

## Claude's recommendation (the author decides)

On `dev` the gate at 0.70 added false refusals (19.2% to 30.8%, all of the increase in `exact-term`) and no refusal
accuracy, because the model's `NO_ANSWER` already handles the unanswerable items. The data favours **option A (gate
off)**, with the gate kept in the code as a protection that costs nothing when it is off. Confirm the choice once on
`test`, and revisit when Phase 4 can gate on a rerank score instead of the bi-encoder cosine (option D).

Whichever value is chosen, the evidence report must state that `dev` has only 4 unanswerable and 5
restricted-below-clearance items, and that the model's refusal behaviour was measured on those few items.

## Decision

Option A. Set `rag.ask.min-similarity = 0.0`, which turns the similarity gate off. The gate stays in the code.

- **Reason.** On `dev` the gate at 0.70 added false refusals (19.2% to 30.8%, all of the increase in `exact-term`) and
  no refusal accuracy, because the model's `NO_ANSWER` already refused every `dev` unanswerable item (4 of 4). On `test`,
  run once with the gate off, the model refused 2 of 2 unanswerable and 2 of 2 restricted-below-clearance items
  (`evidence/phase3-generation.md` §3 and §4).
- **Accepted trade-off.** Without the gate, an out-of-corpus question costs one model call before it is refused. A
  refusal now depends on the model following the `NO_ANSWER` rule in `answer.v2`, not on a retrieval signal. The evidence
  for that rests on 6 unanswerable items in total (4 `dev`, 2 `test`).
- **What the gate would cost if enabled later.** `exact-term` questions are hit first (0% to 50% false refusal at 0.70,
  83% at 0.74), because a vector-only gate ignores the lexical leg that answers those questions well.
- **g036 ruling.** g036 (restricted, expects `eb-payments-bcp-runbook` and `eb-critical-ops-register`) was answered for
  an internal-clearance caller using only the internal runbook; the restricted register was not cited and none of its
  content appeared. This is **not a leak**. The headline restricted refusal accuracy stays as the runner computes it
  (dev 4 of 5), because changing the metric after seeing the result would look like cherry-picking. The item is an
  ambiguity in the golden set: a caller one level below `minClearance` may legitimately answer half of it. The golden
  set is the author's; the fix belongs there (for example a question whose expected sources are all above the lower
  clearance), not in the metric. The `test` split has no such item, so the `test` figures are unaffected.
- **Revisit when.** Phase 4 can gate on a rerank score instead of the bi-encoder cosine, or the golden set grows
  enough for the unanswerable and restricted counts to support a threshold.
