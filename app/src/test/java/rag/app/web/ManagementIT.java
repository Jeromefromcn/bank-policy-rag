package rag.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.context.TestPropertySource;
import rag.app.support.Http;
import rag.app.support.IntegrationTest;

/** Actuator is on its own management port; the API port must not serve it. */
@TestPropertySource(properties = {"management.server.port=0", "management.server.address=127.0.0.1"})
class ManagementIT extends IntegrationTest {

    @LocalManagementPort int managementPort;

    @Test
    void prometheusIsOnTheManagementPortWithoutAKey() {
        var prom = Http.get(managementPort, "/actuator/prometheus", null);
        assertThat(prom.status()).isEqualTo(200);
        assertThat(prom.body()).contains("jvm_");
    }

    @Test
    void healthIsOnTheManagementPortWithoutAKey() {
        assertThat(Http.get(managementPort, "/actuator/health", null).status()).isEqualTo(200);
    }

    @Test
    void apiPortDoesNotServeActuator() {
        assertThat(Http.get(port, "/actuator/prometheus", null).status()).isEqualTo(404);
    }
}
