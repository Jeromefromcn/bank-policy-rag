package rag.app.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import rag.app.RagProperties;

/**
 * Authenticates {@code X-Api-Key} against the configured keys with a constant-time comparison.
 * An unknown or missing key leaves the request unauthenticated, so the entry point answers 401.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Key";

    private final List<RagProperties.ApiKey> keys;

    public ApiKeyAuthFilter(List<RagProperties.ApiKey> configured) {
        this.keys = configured.stream().filter(k -> k.key() != null && !k.key().isBlank()).toList();
        if (keys.isEmpty()) {
            throw new IllegalStateException("rag.security.api-keys has no non-blank key; set RAG_KEY_DEV");
        }
    }

    Optional<Principal> resolve(String presented) {
        if (presented == null || presented.isEmpty()) {
            return Optional.empty();
        }
        byte[] given = presented.getBytes(StandardCharsets.UTF_8);
        Principal match = null;
        for (RagProperties.ApiKey k : keys) {
            // Compare against every key so timing does not reveal which one matched.
            if (MessageDigest.isEqual(given, k.key().getBytes(StandardCharsets.UTF_8))) {
                match = new Principal(k.principal(), k.clearance(), k.roles());
            }
        }
        return Optional.ofNullable(match);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        resolve(request.getHeader(HEADER)).ifPresent(p -> {
            var authorities = p.roles().stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
            var auth = new PreAuthenticatedAuthenticationToken(p, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
        });
        chain.doFilter(request, response);
    }
}
