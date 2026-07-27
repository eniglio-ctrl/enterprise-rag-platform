package com.eniglio.ragplatform.common.security;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Names the two claims auth-service puts on every token (ADR 0016) in one place,
 * instead of the literal string {@code "tenantId"} being retyped in every controller
 * that reads it.
 */
public final class JwtClaims {

    private JwtClaims() {
    }

    public static String tenantId(Jwt jwt) {
        return jwt.getClaimAsString("tenantId");
    }

    public static String userId(Jwt jwt) {
        return jwt.getSubject();
    }
}
