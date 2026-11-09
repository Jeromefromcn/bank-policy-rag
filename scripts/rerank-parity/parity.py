"""Reranker parity check (docs/specs/04-rerank-rewrite.md §2).

Scores the self-made pairs with the reference sentence-transformers CrossEncoder and with the running
reranker sidecar, then prints per-query Spearman correlation, top-1 agreement and the largest absolute score difference.
Usage: python parity.py PAIRS_YAML HF_MODEL_ID RERANKER_URL
Exit code 0 only if every query has the same top-1 and Spearman >= 0.99.
"""
import json
import sys
import urllib.request

import yaml
from scipy.stats import spearmanr
from sentence_transformers import CrossEncoder


def sidecar_scores(url, query, passages):
    body = json.dumps({"query": query, "documents": passages, "top_n": len(passages)}).encode()
    req = urllib.request.Request(url + "/v1/rerank", data=body, headers={"content-type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        results = json.load(resp)["results"]
    scores = [None] * len(passages)
    for r in results:
        scores[r["index"]] = r["relevance_score"]
    return scores


def main():
    pairs_file, model_id, url = sys.argv[1:4]
    pairs = yaml.safe_load(open(pairs_file))
    # activation_fn=Identity: compare raw logits, which is what the sidecar returns.
    import torch
    ref = CrossEncoder(model_id, device="cpu", activation_fn=torch.nn.Identity())
    ok = True
    max_diff = 0.0
    for i, item in enumerate(pairs):
        q, ps = item["query"], item["passages"]
        r = [float(x) for x in ref.predict([(q, p) for p in ps])]
        l = sidecar_scores(url, q, ps)
        rho = spearmanr(r, l).statistic
        top_same = max(range(len(ps)), key=lambda j: r[j]) == max(range(len(ps)), key=lambda j: l[j])
        diff = max(abs(a - b) for a, b in zip(r, l))
        max_diff = max(max_diff, diff)
        ok = ok and top_same and rho >= 0.99
        print(f"q{i + 1}: spearman={rho:.3f} top1_same={top_same} max_abs_diff={diff:.4f} "
              f"ref={[round(x, 3) for x in r]} sidecar={[round(x, 3) for x in l]}")
    print(f"RESULT {'PASS' if ok else 'FAIL'} max_abs_diff={max_diff:.4f}")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
