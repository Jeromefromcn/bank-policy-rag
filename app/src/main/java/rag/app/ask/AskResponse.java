package rag.app.ask;

import java.util.List;

/** ROADMAP §3.3, {@code ANSWERED} shape. Usage comes from the model response, never estimated. */
public record AskResponse(String answerId, String status, String answer, List<Citation> citations, Usage usage, Latency latencyMs) {

    public record Citation(int ref, String chunkId, String docId, List<String> sectionPath, List<Integer> pages, String quote) {}

    public record Usage(Integer promptTokens, Integer completionTokens) {}

    public record Latency(long retrieval, long rerank, long generation) {}
}
