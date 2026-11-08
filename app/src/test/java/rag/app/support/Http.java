package rag.app.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Minimal HTTP client for integration tests. */
public final class Http {

    public record Response(int status, String contentType, String body) {}

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private Http() {}

    public static Response post(int port, String path, String apiKey, String json) {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        return send(apiKey == null ? b : b.header("X-Api-Key", apiKey));
    }

    public static Response get(int port, String path, String apiKey) {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        return send(apiKey == null ? b : b.header("X-Api-Key", apiKey));
    }

    private static Response send(HttpRequest.Builder b) {
        try {
            var r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.headers().firstValue("Content-Type").orElse(""), r.body());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
