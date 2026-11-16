package rag.app.audit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import rag.app.web.ApiException;

/** Admin-only audit read (spec 05 §4.4); access is enforced in SecurityConfig. */
@RestController
public class AuditController {

    private final AuditReader reader;

    public AuditController(AuditReader reader) {
        this.reader = reader;
    }

    @GetMapping("/v1/audit/{recordId}")
    AuditRecord get(@PathVariable String recordId) {
        return reader.get(recordId).orElseThrow(ApiException::notFound);
    }
}
