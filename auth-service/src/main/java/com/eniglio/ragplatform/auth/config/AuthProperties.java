package com.eniglio.ragplatform.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The {@code auth.signing-key.*} properties (path/value) aren't bound here -
 * {@link com.eniglio.ragplatform.auth.security.JwtKeyProvider} (Production Readiness
 * Phase 2, ADR 0048) reads them straight from {@code Environment} instead, so it can
 * react to a Vault-driven refresh without racing this record's own rebinding.
 */
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(Duration tokenTtl, Duration invitationTtl) {
}
