package com.eniglio.ragplatform.auth.integration;

import com.eniglio.ragplatform.auth.AuthServiceApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.vault.VaultContainer;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Production Readiness Phase 2, ADR 0048: proves the actual "done when" of the phase -
 * a real secret sourced from Vault, rotated without restarting the process that
 * consumes it - against a real Vault (Testcontainers), not a mock. {@code AuthIT}
 * keeps Vault disabled ({@code spring.cloud.vault.enabled: false},
 * {@code application-test.yml}); this class overrides that back to {@code true}.
 * <p>
 * Deliberately via JVM system properties in a static initializer, not
 * {@code @DynamicPropertySource}: a real, confirmed bug caught only by actually
 * running this against the real docker-compose stack (not by reasoning about the
 * ordering) - {@code spring.config.import: vault://} resolves during Spring Boot's
 * earliest environment-preparation phase, before {@code @DynamicPropertySource}
 * values are even applied (those come later, via a Spring TestContext
 * {@code ApplicationContextInitializer}). Using {@code @DynamicPropertySource} here
 * meant Vault's host/port/token were still their defaults ({@code localhost:8200},
 * no token) at the moment {@code spring.config.import} actually tried to resolve them,
 * silently failing (swallowed by {@code optional:}) and falling back to an ephemeral
 * key on every single run - a false pass, since two different ephemeral keys are
 * still "different keys" regardless of whether real Vault rotation ever happened.
 * System properties, set at class-load time (before any JUnit extension or Spring
 * context runs), are visible from the very first moment Spring reads its Environment.
 */
@Testcontainers
@SpringBootTest(classes = AuthServiceApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VaultKeyRotationIT {

    private static final String VAULT_TOKEN = "test-root-token";
    private static final String PEM_A = generatePem();
    private static final String PEM_B = generatePem();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
            .withDatabaseName("ragplatform")
            .withUsername("ragplatform")
            .withPassword("ragplatform");

    @Container
    static VaultContainer<?> vault = new VaultContainer<>("hashicorp/vault:1.17")
            .withVaultToken(VAULT_TOKEN)
            .withInitCommand("kv put secret/auth-service auth.signing-key.value=" + toBase64(PEM_A));

    static {
        // See the class javadoc: this has to happen at class-load time, before Spring
        // Boot's own environment preparation, not via @DynamicPropertySource.
        vault.start();
        System.setProperty("spring.cloud.vault.enabled", "true");
        System.setProperty("spring.cloud.vault.host", vault.getHost());
        System.setProperty("spring.cloud.vault.port", String.valueOf(vault.getMappedPort(8200)));
        System.setProperty("spring.cloud.vault.scheme", "http");
        System.setProperty("spring.cloud.vault.token", VAULT_TOKEN);
    }

    @AfterAll
    static void clearSystemProperties() {
        // Surefire reuses one JVM across test classes by default - leaving these set
        // would leak into AuthIT (which relies on Vault staying disabled) if it runs
        // afterward in the same fork.
        System.clearProperty("spring.cloud.vault.enabled");
        System.clearProperty("spring.cloud.vault.host");
        System.clearProperty("spring.cloud.vault.port");
        System.clearProperty("spring.cloud.vault.scheme");
        System.clearProperty("spring.cloud.vault.token");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=auth");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rotatesTheSigningKeyViaVaultAndRefreshWithoutRestartingTheProcess() throws Exception {
        // Confirms the initial resolution actually came from Vault (PEM_A), not the
        // ephemeral fallback - the exact gap that let a false pass through earlier.
        RSAKey expectedKeyA = expectedPublicKey(PEM_A);
        String tokenBeforeRotation = registerAndGetToken("vault-rotation-test@example.com");
        RSAKey keyBeforeRotation = currentJwksPublicKey();
        assertThat(keyBeforeRotation.getKeyID()).isEqualTo(expectedKeyA.getKeyID());
        assertThat(verifies(tokenBeforeRotation, keyBeforeRotation)).isTrue();

        // Rotate: overwrite the secret in Vault with a different key, then ask the
        // running process to pick it up - no restart, no new container, same JVM.
        vault.execInContainer("vault", "kv", "put", "secret/auth-service",
                "auth.signing-key.value=" + toBase64(PEM_B));
        mockMvc.perform(post("/actuator/refresh")).andExpect(status().isOk());

        RSAKey expectedKeyB = expectedPublicKey(PEM_B);
        RSAKey keyAfterRotation = currentJwksPublicKey();
        assertThat(keyAfterRotation.getKeyID()).isEqualTo(expectedKeyB.getKeyID());
        assertThat(keyAfterRotation.toJSONString()).isNotEqualTo(keyBeforeRotation.toJSONString());

        String tokenAfterRotation = registerAndGetToken("vault-rotation-test-2@example.com");
        assertThat(verifies(tokenAfterRotation, keyAfterRotation)).isTrue();

        // The hard-cutover tradeoff this phase's design accepts (documented in ADR
        // 0048): a token signed before rotation no longer verifies once the JWKS
        // publishes the new key - there is no grace-period, dual-key window here.
        assertThat(verifies(tokenBeforeRotation, keyAfterRotation)).isFalse();
    }

    private String registerAndGetToken(String email) throws Exception {
        String body = """
                {"email":"%s","password":"supersecret"}
                """.formatted(email);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private RSAKey currentJwksPublicKey() throws Exception {
        MvcResult result = mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode jwks = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode firstKey = jwks.get("keys").get(0);
        return RSAKey.parse(objectMapper.writeValueAsString(firstKey));
    }

    private boolean verifies(String token, RSAKey publicKey) throws Exception {
        SignedJWT signedJwt = SignedJWT.parse(token);
        return signedJwt.verify(new RSASSAVerifier(publicKey));
    }

    /** The kid JwtKeyProvider.fromPem would derive for a given PEM - a thumbprint, not random. */
    private static RSAKey expectedPublicKey(String pem) throws Exception {
        String base64Body = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64Body);
        java.security.KeyFactory keyFactory = java.security.KeyFactory.getInstance("RSA");
        java.security.interfaces.RSAPrivateCrtKey privateKey = (java.security.interfaces.RSAPrivateCrtKey)
                keyFactory.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(der));
        java.security.interfaces.RSAPublicKey publicKey = (java.security.interfaces.RSAPublicKey)
                keyFactory.generatePublic(new java.security.spec.RSAPublicKeySpec(
                        privateKey.getModulus(), privateKey.getPublicExponent()));
        return new RSAKey.Builder(publicKey).keyIDFromThumbprint("SHA-256").build();
    }

    private static String toBase64(String pem) {
        return Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.UTF_8));
    }

    /** A fresh, real PKCS8 PEM RSA private key - JwtKeyProvider.fromPem's own format. */
    private static String generatePem() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            String base64Der = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
            return "-----BEGIN PRIVATE KEY-----\n" + base64Der + "\n-----END PRIVATE KEY-----\n";
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate a test RSA key pair", e);
        }
    }
}
