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

    private final ApiHttp http;

    public AskClient(URI base, String apiKey) {
        this.http = new ApiHttp(base, apiKey);
    }

    public AskResult ask(String question, int topK, String mode) {
        JsonNode r = http.post("/v1/ask", Map.of("question", question, "topK", topK, "mode", mode));
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
