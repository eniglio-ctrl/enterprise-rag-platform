package com.eniglio.ragplatform.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Replaces {@link ResourceServerSecurityConfig} for the free public demo deployment
 * (ADR 0020, {@code SPRING_PROFILES_ACTIVE=demo}): there's no auth-service running
 * for real JWT validation, and deliberately no login — the whole point is a
 * frictionless public URL. Every request is treated as one fixed demo tenant instead,
 * via a synthetic {@link Jwt} installed by {@link DemoTenantFilter} — this keeps
 * every controller's existing {@code @AuthenticationPrincipal Jwt jwt} +
 * {@link JwtClaims#tenantId(Jwt)} call sites completely unchanged; only how that
 * principal gets there differs.
 */
@Configuration
@EnableWebSecurity
@Profile("demo")
public class DemoSecurityConfig {

    public static final String DEMO_TENANT_ID = "demo";

    @Bean
    public SecurityFilterChain demoSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterAfter(new DemoTenantFilter(), SecurityContextHolderFilter.class);
        return http.build();
    }

    private static final class DemoTenantFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            Jwt jwt = Jwt.withTokenValue("demo")
                    .header("alg", "none")
                    .claim("tenantId", DEMO_TENANT_ID)
                    .subject(DEMO_TENANT_ID)
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build();
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
            chain.doFilter(request, response);
        }
    }
}
