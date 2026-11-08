package rag.app.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Base for full-application tests against the Testcontainers Elasticsearch. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTest {

    public static final String KEY = "test-key";
    public static final String PUBLIC_KEY = "public-key";

    @LocalServerPort protected int port;

    @DynamicPropertySource
    static void elasticsearch(DynamicPropertyRegistry registry) {
        EsTestContainer.register(registry);
    }
}
