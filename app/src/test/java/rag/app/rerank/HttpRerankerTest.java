package rag.app.rerank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import rag.app.RagProperties;

class HttpRerankerTest {

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastContentLength = new AtomicReference<>();

    private HttpReranker start(int status, String body, long delayMs, Duration timeout) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/rerank", ex -> {
            lastContentLength.set(ex.getRequestHeaders().getFirst("Content-Length"));
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("content-type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        return new HttpReranker(new RagProperties.Rerank(url, 20, 256, timeout));
    }

    private HttpReranker start(String body) throws IOException {
        return start(200, body, 0, Duration.ofSeconds(2));
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void mapsResultsByIndex() throws IOException {
        var r = start("""
                {"model":"m","results":[{"index":2,"relevance_score":5.0},{"index":0,"relevance_score":1.0},
                {"index":1,"relevance_score":-3.0}]}""");
        assertThat(r.score("q", List.of("a", "b", "c"))).containsExactly(1.0, -3.0, 5.0);
    }

    @Test
    void sendsTopNEqualToPassageCount() throws IOException {
        var r = start("""
                {"results":[{"index":0,"relevance_score":1.0},{"index":1,"relevance_score":0.5}]}""");
        r.score("the query", List.of("a", "b"));
        assertThat(lastBody.get()).contains("\"top_n\":2").contains("\"query\":\"the query\"").contains("\"documents\":[\"a\",\"b\"]");
    }

    @Test
    void sendsContentLengthNotChunked() throws IOException {
        var r = start("""
                {"results":[{"index":0,"relevance_score":1.0}]}""");
        r.score("q", List.of("a"));
        assertThat(lastContentLength.get()).isNotNull();
        assertThat(Integer.parseInt(lastContentLength.get())).isEqualTo(lastBody.get().getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void missingIndexIsUnavailable() throws IOException {
        var r = start("""
                {"results":[{"index":0,"relevance_score":1.0},{"index":1,"relevance_score":0.5}]}""");
        assertThatThrownBy(() -> r.score("q", List.of("a", "b", "c"))).isInstanceOf(RerankUnavailableException.class);
    }

    @Test
    void outOfRangeIndexIsUnavailable() throws IOException {
        var r = start("""
                {"results":[{"index":0,"relevance_score":1.0},{"index":5,"relevance_score":0.5}]}""");
        assertThatThrownBy(() -> r.score("q", List.of("a", "b"))).isInstanceOf(RerankUnavailableException.class);
    }

    @Test
    void duplicateIndexIsUnavailable() throws IOException {
        var r = start("""
                {"results":[{"index":0,"relevance_score":1.0},{"index":0,"relevance_score":0.5}]}""");
        assertThatThrownBy(() -> r.score("q", List.of("a", "b"))).isInstanceOf(RerankUnavailableException.class);
    }

    @Test
    void malformedBodyIsUnavailable() throws IOException {
        var r = start("not json");
        assertThatThrownBy(() -> r.score("q", List.of("a"))).isInstanceOf(RerankUnavailableException.class);
    }

    @Test
    void http500IsUnavailable() throws IOException {
        var r = start(500, "{\"error\":\"boom\"}", 0, Duration.ofSeconds(2));
        assertThatThrownBy(() -> r.score("q", List.of("a"))).isInstanceOf(RerankUnavailableException.class);
    }

    @Test
    void timeoutIsUnavailable() throws IOException {
        var r = start(200, "{\"results\":[]}", 1500, Duration.ofMillis(200));
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> r.score("q", List.of("a"))).isInstanceOf(RerankUnavailableException.class);
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(1200);
    }

    @Test
    void connectionRefusedIsUnavailable() {
        var r = new HttpReranker(new RagProperties.Rerank("http://127.0.0.1:1", 20, 256, Duration.ofMillis(500)));
        assertThatThrownBy(() -> r.score("q", List.of("a"))).isInstanceOf(RerankUnavailableException.class);
    }

    @Test
    void emptyPassagesMakeNoCall() throws IOException {
        var r = start("not json");
        assertThat(r.score("q", List.of())).isEmpty();
        assertThat(lastBody.get()).isNull();
    }
}
