package rag.app.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import rag.app.RagProperties;
import rag.app.web.ProblemTypes;
import tools.jackson.databind.json.JsonMapper;

/** Web security only: the ingest CLI runs without a web server and needs no API keys. */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, RagProperties props, JsonMapper json) throws Exception {
        // Not a bean, so it is not also registered as a plain servlet filter.
        var apiKeys = new ApiKeyAuthFilter(props.security().apiKeys());
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/v1/audit/**").hasRole("admin")
                        .anyRequest().authenticated())
                .addFilterBefore(apiKeys, AnonymousAuthenticationFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthorized(json)).accessDeniedHandler(forbidden(json)));
        return http.build();
    }

    /** No username/password users exist; this also stops Boot from generating a default one. */
    @Bean
    UserDetailsService noUsers() {
        return new InMemoryUserDetailsManager();
    }

    private static AuthenticationEntryPoint unauthorized(JsonMapper json) {
        return (request, response, ex) -> {
            ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Missing or invalid X-Api-Key");
            p.setType(ProblemTypes.UNAUTHORIZED);
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write(json.writeValueAsString(p));
        };
    }

    private static AccessDeniedHandler forbidden(JsonMapper json) {
        return (request, response, ex) -> {
            ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                    "The API key does not grant access to this resource");
            p.setType(ProblemTypes.FORBIDDEN);
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write(json.writeValueAsString(p));
        };
    }
}
