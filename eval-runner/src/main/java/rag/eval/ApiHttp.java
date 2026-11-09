package rag.eval;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Shared HTTP plumbing for the app clients: API key header, JSON, problem+json errors. */
final class ApiHttp {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private final URI base;
    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    ApiHttp(URI base, String apiKey) {
        this.base = base;
        this.apiKey = apiKey;
    }

    JsonNode get(String path) {
        return send(HttpRequest.newBuilder(base.resolve(path)).GET());
    }

    JsonNode post(String path, Object body) {
        return send(HttpRequest.newBuilder(base.resolve(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
    }

    private JsonNode send(HttpRequest.Builder request) {
        HttpResponse<String> res;
        try {
            res = http.send(request.header("X-Api-Key", apiKey).timeout(Duration.ofSeconds(120)).build(),
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
            throw new IllegalStateException("HTTP " + res.statusCode() + " " + type + " from " + res.uri());
        }
        return JSON.readTree(res.body());
    }
}
