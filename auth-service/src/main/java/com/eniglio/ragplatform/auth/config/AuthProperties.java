package com.eniglio.ragplatform.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "auth")
public record AuthProperties(Duration tokenTtl, Duration invitationTtl, SigningKey signingKey) {

    /**
     * Exactly one of {@code path}/{@code value} should be set in any real deployment
     * (Security Phase 4, ADR 0031) - a mounted secret file (Kubernetes) or a
     * Base64-encoded env var (docker-compose), same two options already used for
     * every other secret in this project. Both blank means {@link
     * com.eniglio.ragplatform.auth.security.JwtKeyProvider} falls back to an
     * ephemeral in-memory key, which is only acceptable for tests.
     */
    public record SigningKey(String path, String value) {
    }
}
