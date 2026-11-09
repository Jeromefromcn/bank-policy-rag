package rag.app.ask;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import rag.app.es.ChunkIndexAdmin;
import rag.app.es.ChunkWriter;
import rag.app.support.Http;
import rag.app.support.IntegrationTest;
import rag.app.support.StubChatModel;
import rag.app.support.TestIndex;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The refusal gate (spec 03 §3): threshold 0.95 against the bag-of-words fake embedder. */
@TestPropertySource(properties = {"rag.env-prefix=t_askgate", "rag.ask.min-similarity=0.95"})
class AskGateIT extends IntegrationTest {

    static final String QUERY = "tolerance for disruption";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired ChunkIndexAdmin admin;
    @Autowired ChunkWriter writer;
    @Autowired StubChatModel stub;

    @BeforeEach
    void setUp() {
        stub.reset();
        if (admin.aliasInfo("t_askgate-chunks").isEmpty()) {
            TestIndex.rebuild(admin, writer, "t_askgate", TestIndex.mixed(QUERY));
        }
    }

    JsonNode ask(String key, Map<String, Object> body) {
        var r = Http.post(port, "/v1/ask", key, JSON.writeValueAsString(body));
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    @Test
    void gateRefusesWithoutCallingModel() {
        JsonNode r = ask(KEY, Map.of("question", "zebra quantum harvest"));
        assertThat(r.get("status").asString()).isEqualTo("REFUSED");
        assertThat(r.get("refusalReason").asString()).isEqualTo("LOW_RETRIEVAL_SCORE");
        assertThat(r.get("answer").isNull()).isTrue();
        assertThat(r.get("citations").size()).isZero();
        assertThat(r.get("usage").get("promptTokens").isNull()).isTrue();
        assertThat(r.get("latencyMs").get("generation").asLong()).isZero();
        assertThat(stub.calls()).isZero();
    }

    @Test
    void aboveThresholdReachesTheModel() {
        assertThat(ask(KEY, Map.of("question", QUERY)).get("status").asString()).isEqualTo("ANSWERED");
        assertThat(stub.calls()).isEqualTo(1);
    }

    @Test
    void publicKeyRefusalMatchesOutOfCorpusRefusal() {
        // The only exact match is internal, so the public caller's best similarity is below the threshold.
        JsonNode restricted = ask(PUBLIC_KEY, Map.of("question", QUERY));
        JsonNode outOfCorpus = ask(KEY, Map.of("question", "zebra quantum harvest"));
        assertThat(restricted.get("status").asString()).isEqualTo("REFUSED");
        assertThat(fieldNames(restricted)).isEqualTo(fieldNames(outOfCorpus));
        assertThat(restricted.get("refusalReason")).isEqualTo(outOfCorpus.get("refusalReason"));
        assertThat(restricted.get("answer")).isEqualTo(outOfCorpus.get("answer"));
        assertThat(restricted.get("citations")).isEqualTo(outOfCorpus.get("citations"));
        assertThat(stub.calls()).isZero();
    }

    @Test
    void bm25ModeSkipsGate() {
        assertThat(ask(KEY, Map.of("question", "zebra quantum harvest tolerance", "mode", "BM25")).get("status").asString())
                .isEqualTo("ANSWERED");
        assertThat(stub.calls()).isEqualTo(1);
    }

    private static TreeSet<String> fieldNames(JsonNode n) {
        TreeSet<String> out = new TreeSet<>();
        n.propertyNames().forEach(out::add);
        return out;
    }
}
