package rag.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import rag.app.support.Http;
import rag.app.support.IntegrationTest;

class ApiKeyAuthIT extends IntegrationTest {

    @Test
    void missingKeyIs401Problem() {
        var r = Http.post(port, "/v1/search", null, "{\"query\":\"x\"}");
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.contentType()).startsWith("application/problem+json");
        assertThat(r.body()).contains("urn:rag:problem:unauthorized");
    }

    @Test
    void wrongKeyIs401() {
        var r = Http.post(port, "/v1/search", "nope", "{\"query\":\"x\"}");
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.body()).contains("urn:rag:problem:unauthorized");
    }

    @Test
    void healthIsOpen() {
        assertThat(Http.get(port, "/actuator/health", null).status()).isEqualTo(200);
    }

    @Test
    void validKeyPassesAuth() {
        assertThat(Http.get(port, "/v1/does-not-exist", KEY).status()).isEqualTo(404);
        assertThat(Http.get(port, "/v1/does-not-exist", PUBLIC_KEY).status()).isEqualTo(404);
    }

    @Test
    void auditEndpointWithoutAdminRoleIs403Problem() {
        var r = Http.get(port, "/v1/audit/a_unknown", KEY);
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.contentType()).startsWith("application/problem+json");
        assertThat(r.body()).contains("urn:rag:problem:forbidden");
    }

    @Test
    void auditEndpointWithAdminKeyPassesTheRoleCheck() {
        // No audit controller yet: reaching the dispatcher means 404, not 403/401.
        assertThat(Http.get(port, "/v1/audit/a_unknown", ADMIN_KEY).status()).isEqualTo(404);
    }
}
