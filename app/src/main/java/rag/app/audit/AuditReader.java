package rag.app.audit;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import rag.app.RagProperties;
import rag.app.web.ApiException;
import rag.app.web.ProblemTypes;

/**
 * Reads audit records with the separate read-only user (spec 05 §4.4). Without credentials it refuses
 * every read with 503 rather than falling back to the write-only application user.
 */
@Component
public class AuditReader implements AutoCloseable {

    private final RagProperties props;
    private final Rest5Client rest;
    private final ElasticsearchClient client;

    public AuditReader(RagProperties props, Environment env) {
        this.props = props;
        String username = props.audit().readerUsername();
        String password = props.audit().readerPassword();
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            this.rest = null;
            this.client = null;
            return;
        }
        String uris = env.getProperty("spring.elasticsearch.uris", "http://127.0.0.1:9200");
        String uri = uris.split(",")[0].trim();
        var credentials = new BasicCredentialsProvider();
        credentials.setCredentials(new AuthScope(null, -1),
                new UsernamePasswordCredentials(username, password.toCharArray()));
        this.rest = Rest5Client.builder(URI.create(uri))
                .setHttpClientConfigCallback(hc -> hc.setDefaultCredentialsProvider(credentials))
                .build();
        this.client = new ElasticsearchClient(new Rest5ClientTransport(rest, new JacksonJsonpMapper()));
    }

    public Optional<AuditRecord> get(String recordId) {
        if (client == null) {
            throw unavailable();
        }
        try {
            var response = client.get(g -> g.index(props.auditIndex()).id(recordId), AuditRecord.class);
            return response.found() ? Optional.ofNullable(response.source()) : Optional.empty();
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            throw unavailable();
        } catch (IOException e) {
            throw unavailable();
        }
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ProblemTypes.AUDIT_READER_UNAVAILABLE,
                "audit reader is unavailable");
    }

    @Override
    @PreDestroy
    public void close() {
        if (rest != null) {
            try {
                rest.close();
            } catch (IOException ignored) {
                // closing the reader client at shutdown; nothing useful to do with the error
            }
        }
    }
}
