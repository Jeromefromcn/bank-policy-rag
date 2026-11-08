package rag.app.ask;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import rag.app.search.SearchRequest;
import rag.app.security.Principal;
import rag.app.web.ApiException;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AskController {

    private final AskService service;

    public AskController(AskService service) {
        this.service = service;
    }

    @PostMapping("/v1/ask")
    AskResponse ask(@RequestBody AskRequest request, @AuthenticationPrincipal Principal principal) {
        if (request.question() == null || request.question().isBlank()) {
            throw ApiException.invalid("question must not be blank");
        }
        if (request.conversationId() != null) {
            throw ApiException.invalid("conversationId is not supported yet");
        }
        var options = SearchRequest.validateRetrievalOptions(request.topK(), request.mode(), request.rerank());
        return service.ask(request.question(), options.topK(), options.mode(), principal);
    }
}
