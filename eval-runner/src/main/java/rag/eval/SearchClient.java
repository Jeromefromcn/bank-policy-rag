package rag.eval;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import rag.core.eval.RankedHit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Calls the app over HTTP with a real API key, so evals exercise authentication and filtering. */
public class SearchClient {

    public record SearchResult(List<RankedHit> hits, long latencyMs) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final URI base;
    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public SearchClient(URI base, String apiKey) {
        this.base = base;
        this.apiKey = apiKey;
    }

    public SearchResult search(String query, int topK) {
        String body = JSON.writeValueAsString(Map.of("query", query, "topK", topK, "mode", "VECTOR"));
        long t0 = System.nanoTime();
        JsonNode res = send(HttpRequest.newBuilder(base.resolve("/v1/search"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        long latency = (System.nanoTime() - t0) / 1_000_000;
        List<RankedHit> hits = new ArrayList<>();
        for (JsonNode h : res.get("hits")) {
            hits.add(new RankedHit(h.get("docId").asString(), strings(h.get("sectionPath")), strings(h.get("coveredSections"))));
        }
        return new SearchResult(hits, latency);
    }

    /** The {@code rag} section of {@code /actuator/info}: corpus, chunker and embedding versions. */
    public Map<String, String> info() {
        JsonNode rag = send(HttpRequest.newBuilder(base.resolve("/actuator/info")).GET()).get("rag");
        Map<String, String> out = new LinkedHashMap<>();
        if (rag != null) {
            rag.properties().forEach(e -> out.put(e.getKey(), e.getValue().asString()));
        }
        return out;
    }

    private JsonNode send(HttpRequest.Builder request) {
        HttpResponse<String> res;
        try {
            res = http.send(request.header("X-Api-Key", apiKey).timeout(Duration.ofSeconds(60)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("request to " + base + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (res.statusCode() / 100 != 2) {
            String type = "";
            try {
                JsonNode problem = JSON.readTree(res.body());
                type = problem.has("type") ? problem.get("type").asString() : "";
            } catch (RuntimeException ignored) {
                // body was not JSON; report the status alone
            }
            throw new IllegalStateException("HTTP " + res.statusCode() + " " + type + " from " + request.build().uri());
        }
        return JSON.readTree(res.body());
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null) {
            array.forEach(n -> out.add(n.asString()));
        }
        return out;
    }
}
