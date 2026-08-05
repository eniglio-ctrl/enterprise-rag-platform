package com.eniglio.ragplatform.common.security;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Names the claims auth-service puts on every token (ADR 0016, plus {@code "role"}
 * from ADR 0047) in one place, instead of the literal string {@code "tenantId"} being
 * retyped in every controller that reads it.
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

    /**
     * ADR 0047: defaults to {@link Role#MEMBER} when the claim is absent or blank —
     * tokens issued before this claim existed (or the demo profile's synthetic JWT,
     * which never sets it) must keep authenticating, just without admin privilege,
     * rather than fail to parse.
     */
    public static Role role(Jwt jwt) {
        String raw = jwt.getClaimAsString("role");
        return raw == null || raw.isBlank() ? Role.MEMBER : Role.valueOf(raw);
    }
}
