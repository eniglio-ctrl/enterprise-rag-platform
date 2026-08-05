package com.eniglio.ragplatform.auth.security;

import com.eniglio.ragplatform.auth.config.AuthProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.UUID;

/**
 * Loads the RSA signing key from a mounted secret file or a Base64-encoded env var
 * (Security Phase 4, ADR 0031), instead of generating one in memory at every startup
 * (ADR 0016's original, explicitly-flagged limitation). Either source is expected to
 * hold a PKCS8 PEM-encoded RSA private key; the public half and the JWKS {@code kid}
 * are both derived from it, so the same persisted key backs signing ({@link
 * com.eniglio.ragplatform.auth.service.TokenService}) and JWKS publication ({@link
 * com.eniglio.ragplatform.auth.controller.JwksController}) identically across
 * restarts. The {@code kid} is a SHA-256 thumbprint of the key itself, not a random
 * value, specifically so it stays identical across restarts too - a downstream
 * service resolving a token's {@code kid} against a freshly-fetched JWKS after this
 * service restarts must still find the same entry.
 * <p>
 * Falls back to generating an ephemeral key when neither source is configured,
 * logging a loud warning - fine for tests (a JVM that never restarts mid-test doesn't
 * care that the key isn't persisted), never acceptable for a real deployment, which
 * always supplies one of the two properties.
 * <p>
 * Re-resolves the key on an {@link EnvironmentChangeEvent} (Production Readiness
 * Phase 2, ADR 0048) - fired by {@code POST /actuator/refresh} after Vault's secret
 * changes. Two designs were tried and disproved by actually running this against the
 * real stack before landing on reading {@link Environment} directly, below:
 * <ol>
 *   <li>{@code @RefreshScope} looked correct on paper ({@link AuthProperties} is a
 *   {@code @ConfigurationProperties} record that already re-binds on refresh) but the
 *   JWKS key never changed after a real rotation - confirmed with a constructor-call
 *   counter that only ever fired once. Root cause: {@link AuthProperties} is an
 *   immutable record, so refreshing it discards the old instance and creates a new
 *   one rather than mutating it in place; a plain constructor-injected reference (all
 *   a scoped-proxy's lazily-recreated target would still hold) stays pointed at the
 *   stale instance regardless.</li>
 *   <li>Injecting {@code ObjectProvider<AuthProperties>} and re-fetching the bean
 *   inside the event listener looked like it fixed that, but a stricter assertion
 *   (comparing against the exact kid the new key should produce, not just "some kid
 *   changed") caught a second real bug: {@code ConfigurationPropertiesRebinder} is
 *   itself just another {@code EnvironmentChangeEvent} listener, on a different bean,
 *   with no defined ordering relative to this one - depending on registration order,
 *   this listener could run *before* {@code AuthProperties} had actually been
 *   rebound, reading the stale value right back out of the "current" bean.</li>
 * </ol>
 * Reading {@link Environment} directly sidesteps both: the environment's own
 * PropertySources are already updated by the time any {@code EnvironmentChangeEvent}
 * listener runs (the event's own key list is computed as a diff *after* that update),
 * so there's no rebinding step to race against.
 */
@Component
public class JwtKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyProvider.class);

    static final String PATH_PROPERTY = "auth.signing-key.path";
    static final String VALUE_PROPERTY = "auth.signing-key.value";

    private final Environment environment;
    private volatile RSAKey rsaKey;

    public JwtKeyProvider(Environment environment) {
        this.environment = environment;
        this.rsaKey = resolveKey();
    }

    @EventListener(EnvironmentChangeEvent.class)
    public void onEnvironmentChange(EnvironmentChangeEvent event) {
        if (!event.getKeys().contains(VALUE_PROPERTY) && !event.getKeys().contains(PATH_PROPERTY)) {
            return;
        }
        this.rsaKey = resolveKey();
        log.info("JWT signing key re-resolved after a configuration refresh (kid={})", rsaKey.getKeyID());
    }

    public RSAKey signingKey() {
        return rsaKey;
    }

    public RSAKey publicJwk() {
        return rsaKey.toPublicJWK();
    }

    private RSAKey resolveKey() {
        String pem = resolvePem(environment.getProperty(PATH_PROPERTY), environment.getProperty(VALUE_PROPERTY));
        return pem != null ? fromPem(pem) : generateEphemeral();
    }

    private static String resolvePem(String path, String value) {
        if (path != null && !path.isBlank()) {
            try {
                return Files.readString(Path.of(path), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read the JWT signing key from " + path, e);
            }
        }
        if (value != null && !value.isBlank()) {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        }
        return null;
    }

    private static RSAKey fromPem(String pem) {
        String base64Body = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64Body);
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) keyFactory.generatePrivate(new PKCS8EncodedKeySpec(der));
            RSAPublicKey publicKey = (RSAPublicKey) keyFactory.generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyIDFromThumbprint("SHA-256")
                    .build();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | JOSEException e) {
            throw new IllegalStateException("Failed to parse the configured JWT signing key - expected a "
                    + "PKCS8 PEM-encoded RSA private key", e);
        }
    }

    private static RSAKey generateEphemeral() {
        log.warn("No JWT signing key configured (auth.signing-key.path / auth.signing-key.value) - generating "
                + "an ephemeral in-memory key. Every token this instance issues becomes invalid on the next "
                + "restart. Acceptable for tests only - never for a real deployment.");
        try {
            return new RSAKeyGenerator(2048)
                    .keyID(UUID.randomUUID().toString())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to generate an ephemeral RSA signing key", e);
        }
    }
}
