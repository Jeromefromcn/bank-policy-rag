package rag.app.permissions;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import rag.app.es.ChunkIndexAdmin;
import rag.app.es.ChunkWriter;
import rag.app.es.SectionDocument;
import rag.app.es.SectionWriter;
import rag.app.support.Http;
import rag.app.support.IntegrationTest;
import rag.app.support.StubChatModel;
import rag.app.support.TestIndex;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Hidden vs absent parity and pre-filter isolation across public, internal and restricted callers. */
@TestPropertySource(properties = {"rag.env-prefix=t_leak", "rag.ask.min-similarity=0.95",
        "rag.audit.index=t_leak-audit-v1", "rag.audit.reader-username=elastic",
        "rag.audit.reader-password=test-password"})
class LeakIT extends IntegrationTest {

    static final String QUERY = "tolerance for disruption";
    static final String PUBLIC_QUERY = "public tolerance guidance";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String SECTIONS_INDEX = "t_leak-sections-struct400-v1";

    @Autowired ChunkIndexAdmin admin;
    @Autowired ChunkWriter writer;
    @Autowired SectionWriter sections;
    @Autowired StubChatModel stub;

    @BeforeEach
    void setUp() {
        stub.reset();
        // Always rebuild: the hidden-only tests below replace this index and JUnit does not order tests.
        TestIndex.rebuild(admin, writer, "t_leak", TestIndex.threeLevels(QUERY));
        if (!admin.exists(SECTIONS_INDEX)) {
            admin.createSections(SECTIONS_INDEX, Map.of("corpusVersion", "abcdef123456",
                    "chunkerVersion", "structural-v1-400-cl100k"));
            sections.bulkIndex(SECTIONS_INDEX, List.of(
                    new SectionDocument("sec-public", "spm-x", "Title spm-x", "1", "regulatory", List.of("1"),
                            List.of(), "public", "public section text", "abcdef123456"),
                    new SectionDocument("sec-restricted", "eb-secret", "Title eb-secret", "1", "internal", List.of("9"),
                            List.of(), "restricted", "restricted section text", "abcdef123456")));
            admin.pointAlias("t_leak-sections", SECTIONS_INDEX);
        }
    }

    JsonNode ask(String key, String question) {
        var r = Http.post(port, "/v1/ask", key, JSON.writeValueAsString(Map.of("question", question)));
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    JsonNode audit(String answerId) {
        var r = Http.get(port, "/v1/audit/" + answerId, ADMIN_KEY);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    @Test
    void publicAndAbsentBodiesMatch() {
        JsonNode hidden = ask(PUBLIC_KEY, QUERY);
        JsonNode absent = ask(PUBLIC_KEY, "zebra quantum harvest");
        assertThat(hidden.get("status").asString()).isEqualTo("REFUSED");
        assertThat(fieldNames(hidden)).isEqualTo(fieldNames(absent));
        assertThat(hidden.get("refusalReason")).isEqualTo(absent.get("refusalReason"));
        assertThat(hidden.get("answer")).isEqualTo(absent.get("answer"));
        assertThat(hidden.get("citations")).isEqualTo(absent.get("citations"));
        assertThat(hidden.get("usage")).isEqualTo(absent.get("usage"));
        assertThat(hidden.get("latencyMs").get("generation")).isEqualTo(absent.get("latencyMs").get("generation"));
        assertThat(hidden.get("latencyMs").get("rerank")).isEqualTo(absent.get("latencyMs").get("rerank"));
    }

    @Test
    void internalCallerCannotSeeRestrictedAndRestrictedCan() {
        assertThat(ask(INTERNAL_KEY, QUERY).get("status").asString()).isEqualTo("REFUSED");
        assertThat(ask(KEY, QUERY).get("status").asString()).isEqualTo("ANSWERED");
    }

    @Test
    void publicPromptHasNoHiddenDocIdOrText() {
        assertThat(ask(PUBLIC_KEY, PUBLIC_QUERY).get("status").asString()).isEqualTo("ANSWERED");
        String prompt = stub.lastPrompt().getInstructions().stream()
                .filter(m -> m.getMessageType() == org.springframework.ai.chat.messages.MessageType.USER)
                .findFirst().orElseThrow().getText();
        assertThat(prompt).doesNotContain("eb-secret", "eb-a", "restricted");
    }

    @Test
    void publicSearchAndSectionsAreFiltered() {
        JsonNode hits = JSON.readTree(Http.post(port, "/v1/search", PUBLIC_KEY,
                JSON.writeValueAsString(Map.of("query", QUERY, "topK", 10))).body()).get("hits");
        hits.forEach(h -> assertThat(h.get("classification").asString()).isEqualTo("public"));

        var hiddenSection = Http.get(port, "/v1/sections/sec-restricted", PUBLIC_KEY);
        var unknownSection = Http.get(port, "/v1/sections/no-such-section", PUBLIC_KEY);
        assertThat(hiddenSection.status()).isEqualTo(404);
        JsonNode a = JSON.readTree(hiddenSection.body());
        JsonNode b = JSON.readTree(unknownSection.body());
        assertThat(a.get("type")).isEqualTo(b.get("type"));
        assertThat(a.get("title")).isEqualTo(b.get("title"));
        assertThat(a.get("detail")).isEqualTo(b.get("detail"));
        assertThat(a.get("status")).isEqualTo(b.get("status"));
    }

    @Test
    void publicHiddenCauseIsAuditOnly() {
        TestIndex.rebuild(admin, writer, "t_leak", TestIndex.hiddenOnly(QUERY));
        JsonNode r = ask(PUBLIC_KEY, QUERY);
        assertThat(r.toString()).doesNotContain("eb-a", "eb-secret", "internalRefusalCause");
        JsonNode record = audit(r.get("answerId").asString());
        assertThat(record.get("internalRefusalCause").asString()).isEqualTo("NO_ACCESSIBLE_SOURCES");
        assertThat(record.get("renderedPrompt").isNull()).isTrue();
    }

    @Test
    void internalRestrictedCauseIsAuditOnly() {
        TestIndex.rebuild(admin, writer, "t_leak", TestIndex.restrictedOnly(QUERY));
        JsonNode r = ask(INTERNAL_KEY, QUERY);
        assertThat(r.get("status").asString()).isEqualTo("REFUSED");
        assertThat(r.toString()).doesNotContain("eb-secret", "internalRefusalCause");
        JsonNode record = audit(r.get("answerId").asString());
        assertThat(record.get("internalRefusalCause").asString()).isEqualTo("NO_ACCESSIBLE_SOURCES");
    }

    private static TreeSet<String> fieldNames(JsonNode n) {
        TreeSet<String> out = new TreeSet<>();
        n.propertyNames().forEach(out::add);
        return out;
    }
}
