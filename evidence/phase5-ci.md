# Phase 5 · CI gate

- **Date**: 2026-11-17
- **Spec**: `docs/specs/05-productionisation.md` §7 (plan Tasks 15–19)
- **Purpose**: a PR gate over the Example Bank subset with fixture embeddings, no Ollama and no model tokens.

## 1. Configuration

| Item | Value |
|---|---|
| Scope | Example Bank only: 30 included, 24 scored, 6 unanswerable, 12 excluded (SPM) |
| Retrieval | HYBRID, top 10, no rerank |
| Fixture | `eval/fixtures/9dd88979729b-ollama_nomic-embed-text-structural-v1-400-cl100k` (11 documents, 46 chunks, 24 scored questions) |
| Fixture content | vector-only JSONL: `chunkId`, `contentHash`, `textHash`, `embedding`; no text |
| Baseline | `eval/baseline.json` |
| Gate | overall Recall@5 or MRR drop > 0.02 fails |
| Runner | `ubuntu-24.04-arm`, no secrets, Testcontainers ES |

## 2. Baseline (real gate run)

`./gradlew :app:ciGate -PciWriteBaseline=true` produced:

| Group | n | Recall@5 | MRR | nDCG@10 |
|---|---|---|---|---|
| all | 24 | 1.000 | 0.882 | 0.903 |
| factual | 10 | 1.000 | 0.950 | 0.963 |
| restricted | 7 | 1.000 | 0.929 | 0.947 |
| exact-term | 5 | 1.000 | 0.633 | 0.726 |
| multi-hop | 2 | 1.000 | 1.000 | 0.885 |

`./gradlew :app:ciGate` then passed against this baseline. The excluded ids are carried in `Result.excludedIds`; the gate output prints counts only. The 12 are SPM-only or
SPM-spanning items. The full-corpus metrics remain local (evidence phases 1–4).

## 3. Local checks

| Check | Result |
|---|---|
| `:core:test :app:test :eval-runner:test` (app excludes `local` and `ci`) | pass |
| `:app:ciGate` | pass against the baseline |
| `scripts/check-compose-limits.sh` | `ok: 5 services limited` |
| `scripts/check-no-spm.sh` | `ok` (no HKMA URL, no tracked PDF) |

`ci.yml` pins checkout v4.2.2 (`11bd719…`), setup-java v6.3.0 (`d1374f8…`) and setup-gradle v6.4.1
(`8da46b5…`); the optional `live-eval.yml` was skipped (generation evidence comes from local runs).

## 4. Pending

The GitHub Actions run is pending: the branch has not been pushed. The first run's URL and the required status
check will be added here; Phase 5 stays unticked until it is green.
