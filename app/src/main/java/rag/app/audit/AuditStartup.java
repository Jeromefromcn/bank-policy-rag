package rag.app.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/** Ensures the audit index exists at startup; a failure is logged, and writes then fail closed with 503. */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuditStartup implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(AuditStartup.class);
    private final AuditIndexAdmin admin;

    public AuditStartup(AuditIndexAdmin admin) {
        this.admin = admin;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            admin.ensure();
        } catch (RuntimeException e) {
            LOG.warn("audit index is not ready; audit writes will fail closed: {}", e.getMessage());
        }
    }
}
