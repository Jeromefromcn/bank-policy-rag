package rag.app.embed;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import rag.app.support.EsTestContainer;
import rag.app.support.StubChatModel;

/**
 * A stalled Ollama (accepts the connection, never answers) must fail within the configured
 * {@code spring.http.clients.read-timeout}, using the real Spring AI auto-configuration.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class OllamaTimeoutIT {

    static final ServerSocket STALL = stallServer();
    static final List<Socket> HELD = new ArrayList<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class ChatOnly {
        @Bean
        StubChatModel stubChatModel() {
            return new StubChatModel();
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        EsTestContainer.register(r);
        r.add("spring.ai.model.embedding", () -> "ollama");
        r.add("spring.ai.ollama.base-url", () -> "http://127.0.0.1:" + STALL.getLocalPort());
        r.add("spring.http.clients.read-timeout", () -> "1s");
    }

    @Autowired Embedder embedder;

    @Test
    void stalledOllamaFailsWithinReadTimeout() {
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> embedder.embedQuery("x")).isInstanceOf(EmbeddingUnavailableException.class);
        org.assertj.core.api.Assertions.assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(10));
    }

    @AfterAll
    static void close() throws IOException {
        STALL.close();
        for (Socket s : HELD) {
            s.close();
        }
    }

    private static ServerSocket stallServer() {
        try {
            ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread t = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        HELD.add(server.accept());   // read nothing, answer nothing
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            t.setDaemon(true);
            t.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
