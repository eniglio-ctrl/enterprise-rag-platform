package com.eniglio.ragplatform.auth.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Generates one RSA keypair in memory at startup and holds it for the lifetime of the
 * process — the same key signs every token this instance issues and is the one
 * exposed at {@code /.well-known/jwks.json} (ADR 0016). Tokens issued before a
 * restart stop validating after one, since the key isn't persisted; acceptable for a
 * portfolio demo, called out explicitly as a known limitation rather than solved with
 * a mounted key file or secret store.
 */
@Component
public class JwtKeyProvider {

    private final RSAKey rsaKey;

    public JwtKeyProvider() {
        try {
            this.rsaKey = new RSAKeyGenerator(2048)
                    .keyID(UUID.randomUUID().toString())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to generate the RSA signing key", e);
        }
    }

    public RSAKey signingKey() {
        return rsaKey;
    }

    public RSAKey publicJwk() {
        return rsaKey.toPublicJWK();
    }
}
