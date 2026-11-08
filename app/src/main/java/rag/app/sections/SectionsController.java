package rag.app.sections;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import rag.app.security.Principal;
import rag.app.web.ApiException;

@RestController
public class SectionsController {

    private final SectionLookup lookup;

    public SectionsController(SectionLookup lookup) {
        this.lookup = lookup;
    }

    @GetMapping("/v1/sections/{sectionId}")
    SectionResponse section(@PathVariable String sectionId, @AuthenticationPrincipal Principal principal) {
        // Unknown and inaccessible ids get the identical response (spec 00 §4: no existence leak).
        return lookup.find(sectionId, principal).orElseThrow(ApiException::notFound);
    }
}
