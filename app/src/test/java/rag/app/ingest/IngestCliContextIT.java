package rag.app.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import rag.app.embed.Embedder;
import rag.app.support.EsTestContainer;
import rag.app.support.FakeEmbedder;

/** The ingest CLI runs without a web server (RagApplication sets WebApplicationType.NONE). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(IngestCliContextIT.EmbedderOnly.class)
@TestPropertySource(properties = {"rag.security.api-keys[0].key=", "rag.security.api-keys[1].key="})
class IngestCliContextIT {

    /** No ChatModel: the CLI must not need a generation backend or its API key. */
    @TestConfiguration(proxyBeanMethods = false)
    static class EmbedderOnly {
        @Bean
        Embedder fakeEmbedder() {
            return new FakeEmbedder(8);
        }
    }

    @DynamicPropertySource
    static void elasticsearch(DynamicPropertyRegistry registry) {
        EsTestContainer.register(registry);
    }

    @Autowired IngestCommand ingest;

    @Test
    void contextStartsWithoutWebServerApiKeysOrChatModel() {
        assertThat(ingest).isNotNull();
    }
}
