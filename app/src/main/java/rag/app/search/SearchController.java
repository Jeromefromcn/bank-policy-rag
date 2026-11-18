package rag.app.search;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import rag.app.security.Principal;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
public class SearchController {

    private final SearchService service;

    public SearchController(SearchService service) {
        this.service = service;
    }

    @PostMapping("/v1/search")
    SearchResponse search(@RequestBody SearchRequest request, @AuthenticationPrincipal Principal principal) {
        var options = request.validated();
        return service.search(request.query(), options, principal, "search");
    }
}
