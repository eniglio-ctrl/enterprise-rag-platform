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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises register/login/JWKS and the tenant-invitation model (Security Phase 4,
 * ADR 0031) against a real Postgres instance, and — beyond just checking HTTP status
 * codes — actually verifies a token issued by {@code /register} cryptographically
 * against the public key served at {@code /.well-known/jwks.json}, the same way
 * ingestion-service/rag-service/chat-service's resource-server config would. A wiring
 * mistake between {@link com.eniglio.ragplatform.auth.security.JwtKeyProvider} and
 * {@link com.eniglio.ragplatform.auth.service.TokenService} (e.g. signing with one key
 * but publishing another) would pass a naive "token is a non-blank string" check but
 * fail this one.
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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void registersWithNoInvitationCreatesANewTenantAndIssuesAVerifiableToken() throws Exception {
        String registerBody = """
                {"email":"ana@example.com","password":"supersecret"}
                """;

        MvcResult registerResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode registerJson = objectMapper.readTree(registerResult.getResponse().getContentAsString());
        assertThat(registerJson.get("tenantId").asText()).isNotBlank();
        assertThat(registerJson.get("tokenType").asText()).isEqualTo("Bearer");
        // ADR 0047: whoever creates a tenant (registers with no invitation) becomes its
        // first ADMIN automatically - there is no other way a tenant's first ADMIN can
        // come into existence.
        assertThat(registerJson.get("role").asText()).isEqualTo("ADMIN");
        String tenantId = registerJson.get("tenantId").asText();
        String token = registerJson.get("token").asText();

        String loginBody = """
                {"email":"ana@example.com","password":"supersecret"}
                """;
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk());

        verifyTokenAgainstJwks(token, registerJson.get("userId").asText(), tenantId);
    }

    @Test
    void rejectsRegisteringTheSameEmailTwice() throws Exception {
        String body = """
                {"email":"dup@example.com","password":"supersecret"}
                """;
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsLoginWithTheWrongPassword() throws Exception {
        String registerBody = """
                {"email":"bruno@example.com","password":"supersecret"}
                """;
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(registerBody))
                .andExpect(status().isCreated());

        String wrongLogin = """
                {"email":"bruno@example.com","password":"wrong-password"}
                """;
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(wrongLogin))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createInvitationRequiresAToken() throws Exception {
        String body = """
                {"email":"invited@example.com"}
                """;
        mockMvc.perform(post("/api/v1/auth/invitations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTeammateCanJoinAnExistingTenantThroughAValidInvitation() throws Exception {
        String ownerToken = registerAndGetToken("owner@example.com");

        String invitationBody = """
                {"email":"teammate@example.com"}
                """;
        MvcResult invitationResult = mockMvc.perform(post("/api/v1/auth/invitations")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invitationBody))
                .andExpect(status().isCreated())
                .andReturn();
        String token = objectMapper.readTree(invitationResult.getResponse().getContentAsString())
                .get("token").asText();

        String ownerTenantId = tenantIdOf(ownerToken);

        String registerBody = """
                {"email":"teammate@example.com","password":"supersecret","invitationToken":"%s"}
                """.formatted(token);
        MvcResult registerResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode registerJson = objectMapper.readTree(registerResult.getResponse().getContentAsString());
        assertThat(registerJson.get("tenantId").asText()).isEqualTo(ownerTenantId);
        // ADR 0047: joining through an invitation always grants MEMBER, never ADMIN -
        // promotion is a separate, explicit action an existing admin takes afterward.
        assertThat(registerJson.get("role").asText()).isEqualTo("MEMBER");
    }

    @Test
    void rejectsRedeemingTheSameInvitationTwice() throws Exception {
        String ownerToken = registerAndGetToken("owner2@example.com");
        String token = createInvitation(ownerToken, "teammate2@example.com");

        String firstAttempt = """
                {"email":"teammate2@example.com","password":"supersecret","invitationToken":"%s"}
                """.formatted(token);
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(firstAttempt))
                .andExpect(status().isCreated());

        String secondAttempt = """
                {"email":"someone-else@example.com","password":"supersecret","invitationToken":"%s"}
                """.formatted(token);
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(secondAttempt))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAnInvitationRedeemedWithADifferentEmail() throws Exception {
        String ownerToken = registerAndGetToken("owner3@example.com");
        String token = createInvitation(ownerToken, "teammate3@example.com");

        String wrongEmail = """
                {"email":"not-the-invited-email@example.com","password":"supersecret","invitationToken":"%s"}
                """.formatted(token);
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(wrongEmail))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAnExpiredInvitationEvenWithTheCorrectEmail() throws Exception {
        String ownerToken = registerAndGetToken("owner4@example.com");
        String token = createInvitation(ownerToken, "teammate4@example.com");

        // Manually expire the row - the same guarantee this phase's "done when"
        // requires verifying for real, not just by reasoning about the code.
        jdbcTemplate.update("UPDATE invitations SET expires_at = now() - interval '1 day' WHERE token = ?", token);

        String registerBody = """
                {"email":"teammate4@example.com","password":"supersecret","invitationToken":"%s"}
                """.formatted(token);
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(registerBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aTenantAdminCanListItsMembersButAMemberCannot() throws Exception {
        String ownerToken = registerAndGetToken("admin1@example.com");
        String memberToken = registerTeammateAndGetToken(ownerToken, "member1@example.com");

        mockMvc.perform(get("/api/v1/auth/users").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());

        MvcResult listResult = mockMvc.perform(
                        get("/api/v1/auth/users").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode users = objectMapper.readTree(listResult.getResponse().getContentAsString());
        assertThat(users.size()).isEqualTo(2);
        List<String> emailAndRole = new ArrayList<>();
        users.forEach(user -> emailAndRole.add(user.get("email").asText() + ":" + user.get("role").asText()));
        assertThat(emailAndRole).containsExactlyInAnyOrder("admin1@example.com:ADMIN", "member1@example.com:MEMBER");
    }

    @Test
    void aTenantAdminCanPromoteAndDemoteATeammate() throws Exception {
        String ownerToken = registerAndGetToken("admin2@example.com");
        String memberToken = registerTeammateAndGetToken(ownerToken, "member2@example.com");
        String memberUserId = userIdOf(memberToken);

        mockMvc.perform(patch("/api/v1/auth/users/" + memberUserId + "/role")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        mockMvc.perform(patch("/api/v1/auth/users/" + memberUserId + "/role")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"MEMBER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MEMBER"));
    }

    @Test
    void aNonAdminCannotChangeAnyonesRole() throws Exception {
        String ownerToken = registerAndGetToken("admin3@example.com");
        String memberToken = registerTeammateAndGetToken(ownerToken, "member3@example.com");
        String ownerUserId = userIdOf(ownerToken);

        mockMvc.perform(patch("/api/v1/auth/users/" + ownerUserId + "/role")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"MEMBER\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAdminCannotChangeTheirOwnRole() throws Exception {
        String ownerToken = registerAndGetToken("admin4@example.com");
        String ownerUserId = userIdOf(ownerToken);

        mockMvc.perform(patch("/api/v1/auth/users/" + ownerUserId + "/role")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"MEMBER\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAnInvalidRoleValue() throws Exception {
        String ownerToken = registerAndGetToken("admin5@example.com");
        String memberToken = registerTeammateAndGetToken(ownerToken, "member5@example.com");
        String memberUserId = userIdOf(memberToken);

        mockMvc.perform(patch("/api/v1/auth/users/" + memberUserId + "/role")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"SUPERUSER\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changingTheRoleOfAUserInAnotherTenantReturns404() throws Exception {
        String owner1Token = registerAndGetToken("admin6@example.com");
        String owner2Token = registerAndGetToken("admin7@example.com");
        String owner2UserId = userIdOf(owner2Token);

        mockMvc.perform(patch("/api/v1/auth/users/" + owner2UserId + "/role")
                        .header("Authorization", "Bearer " + owner1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"MEMBER\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void changingTheRoleOfAnUnknownUserReturns404() throws Exception {
        String ownerToken = registerAndGetToken("admin8@example.com");

        mockMvc.perform(patch("/api/v1/auth/users/" + UUID.randomUUID() + "/role")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"MEMBER\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void theBackfillQueryPromotesTheEarliestUserOfATenantToAdmin() {
        // Mirrors V3__user_role.sql's backfill UPDATE, scoped to one tenant_id here so
        // it can't touch rows other tests in this class create against the same shared
        // container. The migration itself already ran (against an empty `users` table)
        // when this container started, so this is how the DISTINCT ON + ORDER BY logic
        // actually gets exercised against real, deliberately-ordered rows.
        String tenantId = "legacy-tenant-" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tenants (id) VALUES (?)", tenantId);
        UUID earliest = insertLegacyUser(tenantId, "first@example.com", Instant.now().minusSeconds(120));
        UUID middle = insertLegacyUser(tenantId, "second@example.com", Instant.now().minusSeconds(60));
        UUID latest = insertLegacyUser(tenantId, "third@example.com", Instant.now());

        jdbcTemplate.update("""
                UPDATE users SET role = 'ADMIN'
                WHERE id IN (
                    SELECT DISTINCT ON (tenant_id) id FROM users
                    WHERE tenant_id = ?
                    ORDER BY tenant_id, created_at ASC
                )
                """, tenantId);

        assertThat(roleOf(earliest)).isEqualTo("ADMIN");
        assertThat(roleOf(middle)).isEqualTo("MEMBER");
        assertThat(roleOf(latest)).isEqualTo("MEMBER");
    }

    private UUID insertLegacyUser(String tenantId, String email, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, tenant_id, email, password_hash, created_at) VALUES (?, ?, ?, 'hash', ?)",
                id, tenantId, email, Timestamp.from(createdAt));
        return id;
    }

    private String roleOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT role FROM users WHERE id = ?::uuid", String.class, userId.toString());
    }

    private String registerTeammateAndGetToken(String ownerToken, String email) throws Exception {
        String invitationToken = createInvitation(ownerToken, email);
        String body = """
                {"email":"%s","password":"supersecret","invitationToken":"%s"}
                """.formatted(email, invitationToken);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private String userIdOf(String token) throws Exception {
        return SignedJWT.parse(token).getJWTClaimsSet().getSubject();
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

    private String createInvitation(String ownerToken, String invitedEmail) throws Exception {
        String body = """
                {"email":"%s"}
                """.formatted(invitedEmail);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/invitations")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private String tenantIdOf(String token) throws Exception {
        return SignedJWT.parse(token).getJWTClaimsSet().getClaim("tenantId").toString();
    }

    private void verifyTokenAgainstJwks(String token, String expectedUserId, String expectedTenantId)
            throws Exception {
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
        assertThat(signedJwt.getJWTClaimsSet().getClaim("tenantId")).isEqualTo(expectedTenantId);
    }
}
