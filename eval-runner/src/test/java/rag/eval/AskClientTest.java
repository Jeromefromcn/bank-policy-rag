package rag.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AskClientTest {

    @Test
    void parsesAnswered() throws Exception {
        try (var stub = new StubServer()) {
            var r = new AskClient(stub.base(), "k-1").ask("Why?", 5, "HYBRID");
            assertThat(r.status()).isEqualTo("ANSWERED");
            assertThat(r.refusalReason()).isNull();
            assertThat(r.answer()).isEqualTo("An answer [1].");
            assertThat(r.citations()).hasSize(1);
            assertThat(r.citations().getFirst().chunkId()).isEqualTo("c1");
            assertThat(r.citations().getFirst().ref()).isEqualTo(1);
            assertThat(r.promptTokens()).isEqualTo(100);
            assertThat(r.completionTokens()).isEqualTo(10);
            assertThat(r.generationMs()).isEqualTo(42);
            var req = stub.requests.getFirst();
            assertThat(req.apiKey()).isEqualTo("k-1");
            assertThat(JsonMapper.builder().build().readTree(req.body()).get("question").asString()).isEqualTo("Why?");
        }
    }

    @Test
    void parsesRefused() throws Exception {
        try (var stub = new StubServer()) {
            stub.askBody = """
                    {"answerId":"a_2","status":"REFUSED","refusalReason":"LOW_RETRIEVAL_SCORE","answer":null,"citations":[],
                     "usage":{"promptTokens":null,"completionTokens":null},"latencyMs":{"retrieval":5,"rerank":0,"generation":0}}""";
            var r = new AskClient(stub.base(), "k").ask("Why?", 5, "HYBRID");
            assertThat(r.status()).isEqualTo("REFUSED");
            assertThat(r.refusalReason()).isEqualTo("LOW_RETRIEVAL_SCORE");
            assertThat(r.answer()).isNull();
            assertThat(r.promptTokens()).isNull();
        }
    }

    @Test
    void problemResponseRaises() throws Exception {
        try (var stub = new StubServer()) {
            stub.askStatus = 503;
            stub.askBody = "{\"type\":\"urn:rag:problem:generation-unavailable\"}";
            assertThatThrownBy(() -> new AskClient(stub.base(), "k").ask("q", 5, "HYBRID"))
                    .hasMessageContaining("503").hasMessageContaining("generation-unavailable");
        }
    }
}
