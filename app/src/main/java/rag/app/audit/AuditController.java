package rag.app.audit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.RestController;
import rag.app.web.ApiException;

/** Admin-only audit read and replay (spec 05 §4.4); access is enforced in SecurityConfig. */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
public class AuditController {

    private final AuditReader reader;
    private final ReplayService replay;

    public AuditController(AuditReader reader, ReplayService replay) {
        this.reader = reader;
        this.replay = replay;
    }

    @GetMapping("/v1/audit/{recordId}")
    AuditRecord get(@PathVariable String recordId) {
        return reader.get(recordId).orElseThrow(ApiException::notFound);
    }

    @PostMapping("/v1/audit/{recordId}/replay")
    ReplayService.ReplayResult replay(@PathVariable String recordId) {
        return replay.replay(reader.get(recordId).orElseThrow(ApiException::notFound));
    }
}
