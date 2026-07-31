package com.eniglio.ragplatform.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Shared JWT resource-server config for every service except auth-service itself
 * (ADR 0016) — identical across ingestion-service/rag-service/chat-service, so it
 * lives here once instead of being copy-pasted three times (same rationale as
 * {@link com.eniglio.ragplatform.common.web.CorsConfig}, ADR 0010). Each service only
 * needs to set {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri} pointing
 * at auth-service's JWKS endpoint.
 * <p>
 * {@code @Profile("!demo")}: the public demo deployment (ADR 0020) has no
 * auth-service to validate against and deliberately has no login, so it replaces
 * this bean with {@link DemoSecurityConfig} instead — real JWT validation stays the
 * only option for every other profile.
 */
@Configuration
@EnableWebSecurity
@Profile("!demo")
public class ResourceServerSecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, RateLimitFilter rateLimitFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/**", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                // After authentication AND authorization both run (Security Phase 2):
                // a request keyed by tenant always sees the real JwtAuthenticationToken
                // already resolved, never races it.
                .addFilterAfter(rateLimitFilter, AuthorizationFilter.class);
        return http.build();
    }
}
