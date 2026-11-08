package rag.core.eval;

/** Per-item retrieval scores; {@code firstMatchRank} is 1-based within the top 10, or null. */
public record ItemScores(double recall5, double recall10, double reciprocalRank, double ndcg10, Integer firstMatchRank) {}
