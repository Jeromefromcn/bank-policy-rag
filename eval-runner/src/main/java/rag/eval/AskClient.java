package rag.eval;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Calls {@code POST /v1/ask} with a real API key. */
public class AskClient {

    public record AskCitation(int ref, String chunkId, String docId, List<String> sectionPath) {}

    public record AskResult(String status, String refusalReason, String answer, List<AskCitation> citations,
            Integer promptTokens, Integer completionTokens, long generationMs) {}

    /** One turn of a conversation: the answer plus the conversation fields (spec 04 §6). */
    public record TurnResult(AskResult result, String conversationId, String rewrittenQuery, long rewriteMs) {}

    private final ApiHttp http;

    public AskClient(URI base, String apiKey) {
        this.http = new ApiHttp(base, apiKey);
    }

    public AskResult ask(String question, int topK, String mode) {
        return parse(http.post("/v1/ask", Map.of("question", question, "topK", topK, "mode", mode)));
    }

    /** Asks within a conversation; {@code conversationId} null starts a new one. */
    public TurnResult askTurn(String question, int topK, String mode, boolean rerank, String conversationId) {
        Map<String, Object> body = new java.util.LinkedHashMap<>(Map.of("question", question, "topK", topK, "mode", mode,
                "rerank", rerank));
        if (conversationId != null) {
            body.put("conversationId", conversationId);
        }
        JsonNode r = http.post("/v1/ask", body);
        return new TurnResult(parse(r), text(r.path("conversationId")), text(r.path("rewrittenQuery")),
                r.path("latencyMs").path("rewrite").asLong());
    }

    private static AskResult parse(JsonNode r) {
        List<AskCitation> citations = new ArrayList<>();
        for (JsonNode c : r.path("citations")) {
            List<String> path = new ArrayList<>();
            c.path("sectionPath").forEach(n -> path.add(n.asString()));
            citations.add(new AskCitation(c.get("ref").asInt(), c.get("chunkId").asString(), c.get("docId").asString(), path));
        }
        JsonNode usage = r.path("usage");
        return new AskResult(r.get("status").asString(), text(r.path("refusalReason")), text(r.path("answer")), citations,
                integer(usage.path("promptTokens")), integer(usage.path("completionTokens")), r.path("latencyMs").path("generation").asLong());
    }

    private static String text(JsonNode n) {
        return n.isNull() || n.isMissingNode() ? null : n.asString();
    }

    private static Integer integer(JsonNode n) {
        return n.isNumber() ? n.asInt() : null;
    }
}
