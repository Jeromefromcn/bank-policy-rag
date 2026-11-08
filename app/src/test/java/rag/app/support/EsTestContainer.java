package rag.app.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

/** One Elasticsearch container for the whole test JVM, with CPU and memory limits (ROADMAP §7). */
public final class EsTestContainer {

    public static final String IMAGE =
            "elasticsearch:9.5.5@sha256:a418cab5c89773ab827d1d146e6c95a3658226216d8d934d404c33e7a09a147d";
    public static final String PASSWORD = "test-password";

    private static ElasticsearchContainer container;

    private EsTestContainer() {}

    @SuppressWarnings("resource")
    public static synchronized ElasticsearchContainer get() {
        if (container == null) {
            container = new ElasticsearchContainer(
                    DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch"))
                    .withPassword(PASSWORD)
                    .withEnv("xpack.security.http.ssl.enabled", "false")
                    .withEnv("xpack.ml.enabled", "false")
                    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                    .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                            .withMemory(1536L * 1024 * 1024)
                            .withNanoCPUs(2_000_000_000L));
            container.start();
        }
        return container;
    }

    public static void register(DynamicPropertyRegistry registry) {
        var es = get();
        registry.add("spring.elasticsearch.uris", () -> "http://" + es.getHttpHostAddress());
        registry.add("spring.elasticsearch.username", () -> "elastic");
        registry.add("spring.elasticsearch.password", () -> PASSWORD);
    }
}
