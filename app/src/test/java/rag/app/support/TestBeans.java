package rag.app.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import rag.app.embed.Embedder;

/** Network-free stand-ins for the AI backends, shared by every integration test. */
@TestConfiguration(proxyBeanMethods = false)
public class TestBeans {

    public static final int DIMS = 8;

    @Bean
    Embedder fakeEmbedder() {
        return new FakeEmbedder(DIMS);
    }

    @Bean
    StubChatModel stubChatModel() {
        return new StubChatModel();
    }
}
