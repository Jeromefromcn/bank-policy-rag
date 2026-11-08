package rag.eval;

import rag.core.eval.GoldenItem;
import rag.core.eval.ItemScores;

public record ItemResult(GoldenItem item, ItemScores scores, long latencyMs) {}
