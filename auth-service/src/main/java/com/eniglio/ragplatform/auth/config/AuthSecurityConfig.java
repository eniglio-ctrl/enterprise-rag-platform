package com.eniglio.ragplatform.auth.config;

import com.eniglio.ragplatform.auth.security.JwtKeyProvider;
import com.eniglio.ragplatform.common.security.RateLimitFilter;
import com.nimbusds.jose.JOSEException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import java.security.interfaces.RSAPublicKey;

/**
 * auth-service both issues and (for its own {@code /api/v1/auth/invitations}
 * endpoint, Security Phase 4/ADR 0031) validates JWTs - unlike every other service,
 * which only validates (via {@link
 * com.eniglio.ragplatform.common.security.ResourceServerSecurityConfig}, excluded
 * here per ADR 0016's component scan). {@link #jwtDecoder} builds its decoder
 * in-process from the same {@link JwtKeyProvider} that signs tokens, rather than
 * fetching its own JWKS over HTTP - both come from the identical key material in the
 * same JVM, so there's no self-call round-trip to make.
 * <p>
 * {@code register}/{@code login}/JWKS/health stay explicitly unauthenticated
 * (Security Phase 4 replaces the previous blanket {@code permitAll()} with this
 * allowlist); everything else, including the new invitations endpoint, requires a
 * valid bearer token.
 */
@Configuration
@EnableWebSecurity
public class AuthSecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, RateLimitFilter rateLimitFilter,
            JwtDecoder jwtDecoder) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login",
                                "/.well-known/jwks.json", "/actuator/health")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoder)))
                // Security Phase 2: register/login are unauthenticated by design (ADR
                // 0016) - application.yml's rules key these by IP, the only signal
                // available before a JWT exists at all.
                .addFilterAfter(rateLimitFilter, AuthorizationFilter.class);
        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder(JwtKeyProvider jwtKeyProvider) {
        try {
            RSAPublicKey publicKey = jwtKeyProvider.signingKey().toRSAPublicKey();
            return NimbusJwtDecoder.withPublicKey(publicKey).build();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to derive the public key for local JWT validation", e);
        }
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
