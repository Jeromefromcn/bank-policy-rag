package rag.app.rerank;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rag.app.RagProperties;

/** Runs against the real reranker sidecar ({@code docker compose --profile rerank up -d reranker}): {@code ./gradlew :app:localTest}. */
@Tag("local")
class HttpRerankerLocalTest {

    @Test
    void realSidecarRanksTheRelevantPassageFirst() {
        String url = System.getenv().getOrDefault("RERANKER_URL", "http://127.0.0.1:18090");
        var reranker = new HttpReranker(new RagProperties.Rerank(url, 20, 256, Duration.ofSeconds(30)));
        var scores = reranker.score("How soon must the CIO be notified of a critical incident?", List.of(
                "The staff canteen serves lunch from noon.",
                "The CIO must be notified within 30 minutes after a critical incident is declared.",
                "Change requests are reviewed every Tuesday."));
        assertThat(scores).hasSize(3);
        assertThat(scores.get(1)).isGreaterThan(scores.get(0)).isGreaterThan(scores.get(2));
    }
}
