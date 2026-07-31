package com.eniglio.ragplatform.auth.config;

import com.eniglio.ragplatform.common.security.RateLimitFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * auth-service's own filter chain: everything it exposes is meant to be reachable
 * without a token (you need this service precisely because you don't have one yet).
 * Deliberately permissive on every path this service owns — there is no
 * "authenticated" tier here, unlike every other service's
 * {@link com.eniglio.ragplatform.common.security.ResourceServerSecurityConfig}.
 */
@Configuration
@EnableWebSecurity
public class AuthSecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, RateLimitFilter rateLimitFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                // Security Phase 2: register/login are unauthenticated by design (ADR
                // 0016) - application.yml's rules key these by IP, the only signal
                // available before a JWT exists at all.
                .addFilterAfter(rateLimitFilter, AuthorizationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
