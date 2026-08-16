package com.eniglio.ragplatform.common.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

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

    /**
     * docs/adr/0060-multi-department-membership-and-approval.md: a user can belong to
     * several departments now, so this returns a list, never {@code null} - empty when
     * the claim is absent (a token issued before this claim existed, or the demo
     * profile's synthetic JWT) or when the user simply has no approved department,
     * mirroring {@link #role}'s "never absent" precedent rather than the single-value
     * claim this replaces (which was deliberately omittable).
     */
    public static List<String> departments(Jwt jwt) {
        List<String> raw = jwt.getClaimAsStringList("departments");
        return raw == null ? List.of() : raw;
    }
}
