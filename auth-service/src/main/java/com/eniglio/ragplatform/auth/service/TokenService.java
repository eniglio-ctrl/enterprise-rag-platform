package com.eniglio.ragplatform.auth.service;

import com.eniglio.ragplatform.auth.config.AuthProperties;
import com.eniglio.ragplatform.auth.repository.User;
import com.eniglio.ragplatform.auth.security.JwtKeyProvider;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Issues RS256-signed JWTs directly via Nimbus (already on the classpath through
 * spring-boot-starter-oauth2-resource-server, ADR 0016) rather than a higher-level
 * library like jjwt — Nimbus's {@code RSAKey} both signs tokens here and serializes
 * itself as a JWK for {@link com.eniglio.ragplatform.auth.controller.JwksController},
 * so the exact same key object backs both without any format conversion.
 */
@Service
public class TokenService {

    private static final String ISSUER = "auth-service";

    private final JwtKeyProvider jwtKeyProvider;
    private final AuthProperties authProperties;

    public TokenService(JwtKeyProvider jwtKeyProvider, AuthProperties authProperties) {
        this.jwtKeyProvider = jwtKeyProvider;
        this.authProperties = authProperties;
    }

    /**
     * docs/adr/0060-multi-department-membership-and-approval.md: {@code
     * approvedDepartments} comes in as a parameter, not off {@link User}, since
     * department membership moved out of that record entirely (a user can belong to
     * several). Always set as a claim, even when empty - unlike the single-department
     * claim this replaces (which was omitted when {@code null}), "no departments" is
     * now represented as an empty array, matching {@code role}'s "never absent"
     * precedent rather than the old singular claim's "absent means none" one.
     */
    public String issueToken(User user, List<String> approvedDepartments) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(authProperties.tokenTtl());

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(user.id())
                .issuer(ISSUER)
                .claim("tenantId", user.tenantId())
                .claim("email", user.email())
                .claim("role", user.role().name())
                .claim("departments", approvedDepartments)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .build();

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(jwtKeyProvider.signingKey().getKeyID())
                .build();

        SignedJWT signedJwt = new SignedJWT(header, claims);
        try {
            signedJwt.sign(new RSASSASigner(jwtKeyProvider.signingKey()));
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign the JWT", e);
        }
        return signedJwt.serialize();
    }

    public long tokenTtlSeconds() {
        return authProperties.tokenTtl().toSeconds();
    }
}
