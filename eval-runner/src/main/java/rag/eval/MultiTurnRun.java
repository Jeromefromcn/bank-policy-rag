package rag.eval;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import rag.core.eval.GoldenItem;
import rag.core.eval.ItemScores;
import rag.core.eval.RetrievalMetrics;

/**
 * Multi-turn retrieval eval (spec 04 §7.2). Per item, the follow-up retrieval is scored three ways with the same
 * {@code /v1/search} call: the raw follow-up (lower bound), the query {@code /v1/ask} rewrote it to inside the
 * conversation, and the golden standalone question (upper bound). Every call uses the key at the item's
 * {@code minClearance}.
 */
public final class MultiTurnRun {

    public record Config(URI base, String mode, boolean rerank, int topK) {}

    /**
     * @param rewrite      null when the conversation failed ({@code error} says why)
     * @param rewriteMs    {@code latencyMs.rewrite} of the follow-up answer
     * @param promptTokens usage of the follow-up {@code /v1/ask} (rewrite plus answer)
     */
    public record Outcome(MultiTurnItem item, ItemScores raw, ItemScores rewrite, ItemScores standalone, String rewrittenQuery,
            String error, long rewriteMs, Integer promptTokens, Integer completionTokens) {}

    private MultiTurnRun() {}

    public static List<Outcome> run(List<MultiTurnItem> items, String split, Map<String, GoldenItem> golden, KeySet keys,
            Config cfg) {
        List<Outcome> out = new ArrayList<>();
        for (MultiTurnItem item : items) {
            if (!split.equals("all") && !item.split().equals(split)) {
                continue;
            }
            String key = keys.forClearance(item.minClearance());
            var search = new SearchClient(cfg.base(), key, cfg.mode(), cfg.rerank());
            var ask = new AskClient(cfg.base(), key);

            ItemScores raw = score(item, search, item.followUp(), cfg);
            ItemScores standalone = score(item, search, golden.get(item.standaloneGoldenId()).question(), cfg);
            try {
                String conversationId = null;
                for (String turn : item.turns()) {
                    conversationId = ask.askTurn(golden.get(turn).question(), EvalRunner.ASK_TOP_K, cfg.mode(), cfg.rerank(),
                            conversationId).conversationId();
                }
                var followUp = ask.askTurn(item.followUp(), EvalRunner.ASK_TOP_K, cfg.mode(), cfg.rerank(), conversationId);
                String query = followUp.rewrittenQuery() == null ? item.followUp() : followUp.rewrittenQuery();
                out.add(new Outcome(item, raw, score(item, search, query, cfg), standalone, followUp.rewrittenQuery(), null,
                        followUp.rewriteMs(), followUp.result().promptTokens(), followUp.result().completionTokens()));
            } catch (RuntimeException e) {
                out.add(new Outcome(item, raw, null, standalone, null, e.getMessage(), 0, null, null));
            }
        }
        return out;
    }

    private static ItemScores score(MultiTurnItem item, SearchClient search, String query, Config cfg) {
        return RetrievalMetrics.score(item.expectedSources(), search.search(query, cfg.topK()).hits());
    }
}
