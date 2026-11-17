package rag.app.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import rag.app.es.ChunkIndexAdmin;
import rag.app.es.ChunkWriter;
import rag.app.security.Principal;
import rag.app.support.IntegrationTest;
import rag.app.support.TestIndex;
import rag.core.model.Clearance;

@TestPropertySource(properties = "rag.env-prefix=t_probe")
class VisibilityProbeIT extends IntegrationTest {

    static final String QUERY = "tolerance for disruption";

    @Autowired VisibilityProbe probe;
    @Autowired ChunkIndexAdmin admin;
    @Autowired ChunkWriter writer;

    @BeforeEach
    void index() {
        if (admin.aliasInfo("t_probe-chunks").isEmpty()) {
            TestIndex.rebuild(admin, writer, "t_probe", TestIndex.mixed(QUERY));
        }
    }

    static Principal publicPrincipal() {
        return new Principal("bob", Clearance.PUBLIC, Set.of());
    }

    static Principal restrictedPrincipal() {
        return new Principal("dev", Clearance.RESTRICTED, Set.of());
    }

    @Test
    void publicCallerWithAHiddenMatchIsNoAccessibleSources() {
        assertThat(probe.cause(QUERY, publicPrincipal())).isEqualTo(InternalRefusalCause.NO_ACCESSIBLE_SOURCES);
    }

    @Test
    void publicCallerWithNoMatchIsNoMatchingContent() {
        assertThat(probe.cause("zebra quantum harvest", publicPrincipal()))
                .isEqualTo(InternalRefusalCause.NO_MATCHING_CONTENT);
    }

    @Test
    void restrictedCallerIsAlwaysNoMatchingContent() {
        assertThat(probe.cause("zebra quantum harvest", restrictedPrincipal()))
                .isEqualTo(InternalRefusalCause.NO_MATCHING_CONTENT);
    }
}
