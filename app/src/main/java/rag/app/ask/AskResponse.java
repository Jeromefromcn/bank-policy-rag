package rag.app.ask;

import java.util.List;

/**
 * ROADMAP §3.3. {@code ANSWERED} carries only the sources the answer cites (numbers as in the text); {@code REFUSED}
 * carries a {@code refusalReason} and no answer. Usage comes from the model responses (rewrite plus answer), never
 * estimated. {@code rewrittenQuery} is the standalone query a follow-up was retrieved and answered with; null on a
 * first turn (spec 04 §6.2).
 */
public record AskResponse(String answerId, String status, String refusalReason, String answer, List<Citation> citations,
        Usage usage, Latency latencyMs, String conversationId, String rewrittenQuery) {

    public record Citation(int ref, String chunkId, String docId, List<String> sectionPath, List<Integer> pages, String quote) {}

    public record Usage(Integer promptTokens, Integer completionTokens) {

        /** Component-wise sum; a component stays null only when it is null on both sides. */
        Usage plus(Integer prompt, Integer completion) {
            return new Usage(add(promptTokens, prompt), add(completionTokens, completion));
        }

        private static Integer add(Integer a, Integer b) {
            return a == null ? b : b == null ? a : Integer.valueOf(a + b);
        }
    }

    public record Latency(long retrieval, long rerank, long generation, long rewrite) {}
}
