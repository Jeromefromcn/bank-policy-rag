package rag.app.security;

import java.util.Set;
import rag.core.model.Clearance;

/** The only identity type below the security layer (spec 00 §4). */
public record Principal(String name, Clearance clearance, Set<String> roles) {

    public Principal {
        roles = Set.copyOf(roles);
    }
}
