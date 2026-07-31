package com.eniglio.ragplatform.auth.integration;

import com.eniglio.ragplatform.auth.AuthServiceApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises register/login/JWKS against a real Postgres instance, and — beyond just
 * checking HTTP status codes — actually verifies a token issued by {@code /register}
 * cryptographically against the public key served at {@code /.well-known/jwks.json},
 * the same way ingestion-service/rag-service/chat-service's resource-server config
 * would. A wiring mistake between {@link com.eniglio.ragplatform.auth.security.JwtKeyProvider}
 * and {@link com.eniglio.ragplatform.auth.service.TokenService} (e.g. signing with one
 * key but publishing another) would pass a naive "token is a non-blank string" check
 * but fail this one.
 */
@Testcontainers
@SpringBootTest(classes = AuthServiceApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
            .withDatabaseName("ragplatform")
            .withUsername("ragplatform")
            .withPassword("ragplatform");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=auth");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void registersLogsInAndIssuesATokenThatVerifiesAgainstTheJwksEndpoint() throws Exception {
        String registerBody = """
                {"email":"ana@example.com","password":"supersecret","tenantId":"acme"}
                """;

        MvcResult registerResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode registerJson = objectMapper.readTree(registerResult.getResponse().getContentAsString());
        assertThat(registerJson.get("tenantId").asText()).isEqualTo("acme");
        assertThat(registerJson.get("tokenType").asText()).isEqualTo("Bearer");
        String token = registerJson.get("token").asText();

        String loginBody = """
                {"email":"ana@example.com","password":"supersecret"}
                """;
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk());

        verifyTokenAgainstJwks(token, registerJson.get("userId").asText());
    }

    @Test
    void rejectsRegisteringTheSameEmailTwice() throws Exception {
        String body = """
                {"email":"dup@example.com","password":"supersecret","tenantId":"acme"}
                """;
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsLoginWithTheWrongPassword() throws Exception {
        String registerBody = """
                {"email":"bruno@example.com","password":"supersecret","tenantId":"acme"}
                """;
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(registerBody))
                .andExpect(status().isCreated());

        String wrongLogin = """
                {"email":"bruno@example.com","password":"wrong-password"}
                """;
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(wrongLogin))
                .andExpect(status().isUnauthorized());
    }

    private void verifyTokenAgainstJwks(String token, String expectedUserId) throws Exception {
        MvcResult jwksResult = mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode jwks = objectMapper.readTree(jwksResult.getResponse().getContentAsString());
        JsonNode firstKey = jwks.get("keys").get(0);
        RSAKey publicKey = RSAKey.parse(objectMapper.writeValueAsString(firstKey));

        SignedJWT signedJwt = SignedJWT.parse(token);
        boolean verified = signedJwt.verify(new RSASSAVerifier(publicKey));

        assertThat(verified).isTrue();
        assertThat(signedJwt.getJWTClaimsSet().getSubject()).isEqualTo(expectedUserId);
        assertThat(signedJwt.getJWTClaimsSet().getClaim("tenantId")).isEqualTo("acme");
    }
}
