package rag.app.ask;

import java.util.List;

/**
 * ROADMAP §3.3. {@code ANSWERED} carries only the sources the answer cites (numbers as in the text); {@code REFUSED}
 * carries a {@code refusalReason} and no answer. Usage comes from the model response, never estimated.
 */
public record AskResponse(String answerId, String status, String refusalReason, String answer, List<Citation> citations,
        Usage usage, Latency latencyMs) {

    public record Citation(int ref, String chunkId, String docId, List<String> sectionPath, List<Integer> pages, String quote) {}

    public record Usage(Integer promptTokens, Integer completionTokens) {}

    public record Latency(long retrieval, long rerank, long generation) {}
}
