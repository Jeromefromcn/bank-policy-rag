# Phase 3 · Citations, refusals and generation-side eval

- **Date**: 2026-11-09
- **Spec**: `docs/specs/03-generation.md` (plan: `docs/specs/03-generation-plan.md`)
- **Purpose**: measure grounded generation (faithfulness, citation support, refusal accuracy) and the refusal gate,
  on top of the Phase 2 retrieval setup.

All numbers come from eval-runner output (`eval/runs/…`, not committed) produced at commit `5f98366` with a clean working
tree. Item ids and counts only: no source text and no answer text appear here.

## 1. Configuration

| Item | Value |
|---|---|
| Index | `rag_dev-chunks-nomic768-struct400-v1` (structural chunks, `nomic-embed-text`), corpus `9dd88979729b` |
| Retrieval | `HYBRID`, 5 sources given to the model |
| Generator | `deepseek-flash`, temperature 0, prompt `answer.v2` |
| Judge | `deepseek-v4-pro`, prompts `judge-point.v1` and `judge-citation.v1` (same model family as the generator) |
| Citation overlap threshold | 0.5 (soft flag only) |
| Machine | aarch64, 4 CPUs, shared Elasticsearch and Ollama |
| Runs | dev, gate off: `20261109T121211Z-generation`. dev, gate 0.70: `20261109T121627Z-generation`. **test, gate off, run once**: `20261109T132526Z-generation`. Threshold sweep: `20261109T023629Z-sweep` |

Expectations stated before the runs: (1) refusing out-of-corpus questions is cheap, since the model can answer
`NO_ANSWER`; (2) `exact-term` is the likeliest casualty of a gate that looks only at vector similarity (spec 03 §3.1).

**Threshold.** The run for the headline numbers uses `rag.ask.min-similarity = 0.0` (gate off, option A in the draft
`decisions/03`), following the author's go-ahead to continue with the recommended option. **The author has not yet written
the Decision section of `decisions/03`**; this report does not stand in for it.

## 2. Threshold sweep (dev, retrieval only, no tokens)

dev sample sizes: 26 answerable, 4 unanswerable, 5 restricted-below-clearance. One item moves a rate by 20 to 25 points.

| Threshold | Unanswerable refused | Restricted refused | False refusal | False refusal, exact-term (n=6) |
|---|---|---|---|---|
| ≤ 0.65 | 0% | 0% | 0% | 0% |
| 0.70 | 50% | 20% | 15.4% | 50% |
| 0.74 | 100% | 60% | 34.6% | 83% |
| 0.80 | 100% | 80% | 65.4% | 100% |
| ≥ 0.85 | 100% | 100% | 100% | 100% |

Similarities sit in a narrow band (about 0.65 to 0.85) where answerable and unanswerable questions overlap, so no
threshold separates them cleanly.

## 3. End to end: gate off versus gate 0.70 (dev)

| Metric (dev) | Gate off | Gate 0.70 |
|---|---|---|
| Answerable calls answered / false refusal | 21 of 26 / 19.2% | 18 of 26 / 30.8% |
| False refusal, exact-term (n=6) | 0% | 50% |
| False refusal, multi-hop (n=5) | 60% | 60% |
| Unanswerable correctly refused | 4 of 4 | 4 of 4 |
| Restricted (below clearance) refused | 4 of 5 | 4 of 5 |
| Faithfulness (supported / points) | 0.886 (31/35) | 0.867 (26/30) |
| Citation support (supported / sentences) | 0.846 (33/39) | 0.903 (28/31) |
| Citation validation failures | 0 | 0 |

With the gate off every refusal came from the model's `NO_ANSWER`. The gate at 0.70 refused the same unanswerable and
restricted items the model already refused, and added three false refusals, all `exact-term` (g015, g017, g022). On this
sample it bought no refusal accuracy. This is the basis for choosing option A; it rests on 4 unanswerable and 5
restricted items.

## 4. Headline: test split, gate off, one run

| Group (test) | Answerable calls | Answered | False refusal | Faithfulness | Citation support |
|---|---|---|---|---|---|
| all | 10 | 9 | 10.0% (1/10) | **0.706 (12/17)** | 0.938 (15/16) |
| exact-term | 2 | 2 | 0% | 0.667 (2/3) | 0.667 (2/3) |
| factual | 4 | 4 | 0% | 1.000 (7/7) | 1.000 (8/8) |
| multi-hop | 2 | 1 | 50% (g024) | 0.500 (1/2) | 1.000 (3/3) |
| restricted | 2 | 2 | 0% | 0.400 (2/5) | 1.000 (2/2) |

| Refusal check (test) | n | Correctly refused | Accuracy |
|---|---|---|---|
| unanswerable | 2 | 2 | **1.000** |
| restricted, below clearance | 2 | 2 | **1.000** |

Request errors: 0. Citation validation failures: 0. Mean tokens per answered call 1348 (prompt + completion, from the
provider `usage`). Generation latency p50 / p95: 1952 / 5837 ms (n=9, shared host).

For comparison, dev with the gate off: faithfulness 0.886 (31/35), unanswerable 4 of 4, restricted 4 of 5, generation
latency p50 / p95 1436 / 3049 ms.

## 5. Judge–human agreement

| n | Agree | Agreement | Cohen's κ |
|---|---|---|---|
| 20 | 20 | 1.000 | 1.000 |

**This is not an independent human check of the judge.** The 20 verdicts were drafted by Claude and adopted by the author
as they stand (`eval/human-labels.yaml`, provenance in its header). The draft was not blind: before labelling, Claude had
seen in its tool output that the judge returned UNSUPPORTED for g007 point 1, g019 point 1, g026 point 0 and g034 point 1,
and those are exactly the four points labelled `no`. The 20 points were sampled from the dev gate-off run. The figure shows
that the judge's verdicts are explicable from the sheet text (each `no` has a stated reason in
`eval/ai-labels-draft.yaml`); it does not show that an independent human labeller would agree. The exit criterion "judge
agreement" is therefore only weakly met. A human-labelled sample should replace this before the number is quoted publicly.

## 6. Citation validator and the overlap flag

- Hard failures (range, paragraph coverage): 0 in every run. In particular the list lead-in rule (a line ending in `:`
  joins the first list item) was in force for all runs.
- Soft overlap flag at 0.5: dev gate off 1 of 39 cited sentences flagged (2.6%), test 1 of 16 (6.3%).
- Overlap flag against the judge's citation verdicts (dev, gate off, 6 unsupported and 33 supported sentences):

| Overlap threshold | Flagged | Flagged and unsupported | Flagged and supported |
|---|---|---|---|
| 0.50 | 1 | 1 | 0 |
| 0.70 | 6 | 2 | 4 |
| 0.90 | 15 | 4 | 11 |

  At best (0.90) the flag finds 4 of the 6 unsupported sentences at the price of 11 false alarms. On test at 0.5 the one
  flagged sentence was supported, and the one unsupported sentence was not flagged. Lexical overlap is a weak detector
  here; the judge's citation check is what carries the citation-support number.

## 7. Observations

Written only from the numbers above. Samples are small (dev 26 answerable; test 10 answerable, 17 points); a difference of
one item is not evidence.

1. **Refusing out-of-corpus questions worked without the gate.** The model refused all 4 dev and 2 test unanswerable
   items, so the gate added nothing measurable and cost recall on `exact-term` (expectation 2 confirmed on dev: 0% to 50%).
2. **Multi-hop is the weakest category.** It had the highest false refusal in every run (dev 3 of 5, test 1 of 2). The
   evidence here does not say why. A likely candidate is that the top 5 sources do not cover both documents, which is a
   retrieval question for Phase 4.
3. **Faithfulness is lower on test than dev** (0.706 vs 0.886). The test points that were not supported are in g020
   (exact-term), g027 (multi-hop), g032 and g035 (restricted). With 17 points the gap is five points against four on dev;
   this report does not attribute it to a cause. Reading those four items is the next step before drawing a conclusion.
4. **Restricted below-clearance behaviour.** dev: 4 of 5 refused. The fifth, g036, was answered with a single cited
   document, `eb-payments-bcp-runbook`, classified `internal`, which the internal-clearance caller may see; the restricted
   register was not cited. No answered below-clearance call cited a document above the caller's clearance. If g036 counts
   as an answerable item for an internal caller, dev restricted accuracy is 4 of 4; the golden set currently lists both
   documents as expected sources, so the runner counts it as not refused. The author decides how g036 should be treated.
5. **Cost.** About 1100 tokens per answered dev call (1348 on test). Generation latency p50 was 1.4 s on dev and 2.0 s on
   test, p95 up to 5.8 s, on the shared host.

## 8. Not done, and caveats

- **Parent-context experiment (plan Task 9)** was not run; the default stays `chunk` (spec 03 §10 item 4).
- Judge and generator are both DeepSeek models, so self-evaluation bias is possible; section 5 does not rule it out.
- Single run per configuration; no repeat to estimate generation variance.
- `mode=BM25` on `/v1/ask` skips the gate by design (spec 03 §3.1).
- Latency and token figures come from a shared host and a provider that reports reasoning tokens inside completion tokens.
