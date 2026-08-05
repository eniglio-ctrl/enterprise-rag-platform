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

    public String issueToken(User user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(authProperties.tokenTtl());

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(user.id())
                .issuer(ISSUER)
                .claim("tenantId", user.tenantId())
                .claim("email", user.email())
                .claim("role", user.role().name())
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
