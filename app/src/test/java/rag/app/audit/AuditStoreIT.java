package rag.app.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import rag.app.support.EsAdminHttp;
import rag.app.support.IntegrationTest;
import rag.app.web.ApiException;

@TestPropertySource(properties = {"rag.env-prefix=t_audit", "rag.audit.index=t_audit-audit-v1"})
class AuditStoreIT extends IntegrationTest {

    static final String INDEX = "t_audit-audit-v1";

    @Autowired AuditStore store;
    @Autowired AuditIndexAdmin admin;
    @Autowired ElasticsearchClient client;

    @BeforeEach
    void reset() throws IOException {
        try {
            client.indices().delete(d -> d.index(INDEX));
        } catch (ElasticsearchException e) {
            if (e.status() != 404) {
                throw e;
            }
        }
        admin.ensure();
    }

    static AuditRecord sample() {
        return new AuditRecord("a_1", null, "2026-11-16T00:00:00Z", "trace-1", "tester", "restricted", "ask",
                "question", "rewritten", new AuditRecord.Retrieval("HYBRID", 5, false, 0.8, null,
                        List.of(new AuditRecord.Hit("c1", "h1", 1, 0.9, "eb-a", List.of("1"), "public"))),
                "answer.v2", "[system]\ns\n[user]\nu",
                new AuditRecord.Model("deepseek", "deepseek-flash", 0.0, 1024),
                "Answer [1].", List.of(new AuditRecord.Citation(1, "c1", "eb-a", List.of("1"), List.of(), "quote")),
                "ANSWERED", null, null, new AuditRecord.Usage(100, 10),
                new AuditRecord.Latency(5, 0, 42, 0, 3), "corpus", "structural-v1-400-cl100k", "fake/hash");
    }

    @Test
    void writesAndReadsBackEveryField() throws Exception {
        var record = sample();
        store.write(record);
        var got = client.get(g -> g.index(INDEX).id(record.id()), AuditRecord.class).source();
        assertThat(got).isEqualTo(record);
    }

    @Test
    void mappingIsStrict() throws IOException {
        assertThat(EsAdminHttp.get("/" + INDEX + "/_mapping")).contains("\"dynamic\":\"strict\"");
    }

    @Test
    void duplicateIdIsRejected() {
        store.write(sample());
        assertThatThrownBy(() -> store.write(sample()))
                .isInstanceOf(ApiException.class).hasMessageContaining("audit");
    }

    @Test
    void unwritableIndexFailsClosed() throws Exception {
        store.write(sample());
        client.indices().close(c -> c.index(INDEX));
        try {
            assertThatThrownBy(() -> store.write(sample())).isInstanceOf(ApiException.class);
        } finally {
            client.indices().open(o -> o.index(INDEX));
        }
    }
}
