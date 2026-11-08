package rag.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SearchClientTest {

    @Test
    void sendsKeyModeAndTopK() throws Exception {
        try (var stub = new StubServer()) {
            new SearchClient(stub.base(), "k-123", "VECTOR").search("What is X?", 10);
            var req = stub.requests.getFirst();
            assertThat(req.apiKey()).isEqualTo("k-123");
            var body = JsonMapper.builder().build().readTree(req.body());
            assertThat(body.get("mode").asString()).isEqualTo("VECTOR");
            assertThat(body.get("topK").asInt()).isEqualTo(10);
            assertThat(body.get("query").asString()).isEqualTo("What is X?");
        }
    }

    @Test
    void mapsHitsToRankedHits() throws Exception {
        try (var stub = new StubServer()) {
            var result = new SearchClient(stub.base(), "k", "HYBRID").search("q", 10);
            assertThat(result.hits()).hasSize(2);
            assertThat(result.hits().getFirst().docId()).isEqualTo("eb-a");
            assertThat(result.hits().getFirst().sectionPath()).containsExactly("1");
            assertThat(result.hits().get(1).coveredSections()).containsExactly("2");
            assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void problemResponseRaises() throws Exception {
        try (var stub = new StubServer()) {
            stub.searchStatus = 401;
            stub.searchBody = "{\"type\":\"urn:rag:problem:unauthorized\",\"status\":401}";
            assertThatThrownBy(() -> new SearchClient(stub.base(), "k", "HYBRID").search("q", 10))
                    .hasMessageContaining("401").hasMessageContaining("urn:rag:problem:unauthorized");
        }
    }

    @Test
    void readsInfo() throws Exception {
        try (var stub = new StubServer()) {
            assertThat(new SearchClient(stub.base(), "k", "HYBRID").info()).containsEntry("chunkerVersion", "fixed-512-64-cl100k");
        }
    }

    @Test
    void sendsTheConfiguredMode() throws Exception {
        try (var stub = new StubServer()) {
            new SearchClient(stub.base(), "k", "BM25").search("q", 10);
            assertThat(JsonMapper.builder().build().readTree(stub.requests.getFirst().body()).get("mode").asString()).isEqualTo("BM25");
        }
    }
}
