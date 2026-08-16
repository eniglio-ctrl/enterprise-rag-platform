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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

    // --- docs/adr/0059-department-based-sharing.md ---

    @Test
    void aTenantAdminCanCreateAndListDepartments() throws Exception {
        String ownerToken = registerAndGetToken("admin9@example.com");

        mockMvc.perform(post("/api/v1/auth/departments")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Financeiro\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Financeiro"));

        MvcResult listResult = mockMvc.perform(
                        get("/api/v1/auth/departments").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode departments = objectMapper.readTree(listResult.getResponse().getContentAsString());
        assertThat(departments.size()).isEqualTo(1);
        assertThat(departments.get(0).get("name").asText()).isEqualTo("Financeiro");
    }

    @Test
    void rejectsCreatingADuplicateDepartmentNameCaseInsensitively() throws Exception {
        String ownerToken = registerAndGetToken("admin10@example.com");

        mockMvc.perform(post("/api/v1/auth/departments")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Financeiro\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/departments")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"financeiro\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void aNonAdminCannotCreateDepartmentsButCanListThem() throws Exception {
        // docs/adr/0060-multi-department-membership-and-approval.md: listing was
        // relaxed from admin-only to any tenant member - self-service department
        // requests need to see the pickable list too. Creating stays admin-only.
        String ownerToken = registerAndGetToken("admin11@example.com");
        String memberToken = registerTeammateAndGetToken(ownerToken, "member11@example.com");

        mockMvc.perform(post("/api/v1/auth/departments")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"TI\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/auth/departments").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk());
    }

    @Test
    void aTenantAdminCanReplaceATeammatesApprovedDepartments() throws Exception {
        // docs/adr/0060-multi-department-membership-and-approval.md: the old
        // single-value PATCH .../department was replaced by a bulk "replace the whole
        // approved set" PATCH .../departments, same shape document sharing already
        // uses for sharedDepartments.
        String ownerToken = registerAndGetToken("admin12@example.com");
        String memberToken = registerTeammateAndGetToken(ownerToken, "member12@example.com");
        String memberUserId = userIdOf(memberToken);
        mockMvc.perform(post("/api/v1/auth/departments")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Financeiro\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(patch("/api/v1/auth/users/" + memberUserId + "/departments")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"departments\":[\"Financeiro\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedDepartments[0]").value("Financeiro"));

        mockMvc.perform(patch("/api/v1/auth/users/" + memberUserId + "/departments")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"departments\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedDepartments").isEmpty());
    }

    @Test
    void assigningAUserToANonexistentDepartmentReturns404() throws Exception {
        String ownerToken = registerAndGetToken("admin13@example.com");
        String memberToken = registerTeammateAndGetToken(ownerToken, "member13@example.com");
        String memberUserId = userIdOf(memberToken);

        mockMvc.perform(patch("/api/v1/auth/users/" + memberUserId + "/departments")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"departments\":[\"Does Not Exist\"]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void departmentNamesAreIsolatedPerTenant() throws Exception {
        // Two different tenants creating the exact same department name must not
        // conflict with each other - the uniqueness check (and the V4 migration's own
        // unique index) is scoped by tenant_id, not global.
        String tenant1Token = registerAndGetToken("admin14@example.com");
        String tenant2Token = registerAndGetToken("admin15@example.com");

        mockMvc.perform(post("/api/v1/auth/departments")
                        .header("Authorization", "Bearer " + tenant1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Financeiro\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/departments")
                        .header("Authorization", "Bearer " + tenant2Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Financeiro\"}"))
                .andExpect(status().isCreated());
    }

    // --- docs/adr/0060-multi-department-membership-and-approval.md ---

    @Test
    void anAdminCanGrantAdminRoleThroughAnInvitation() throws Exception {
        String ownerToken = registerAndGetToken("admin16@example.com");

        String invitationToken = createInvitationWithRole(ownerToken, "future-admin@example.com", "ADMIN");
        String registerBody = """
                {"email":"future-admin@example.com","password":"supersecret","invitationToken":"%s"}
                """.formatted(invitationToken);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).get("role").asText())
                .isEqualTo("ADMIN");
    }

    @Test
    void aNonAdminCannotGrantAdminRoleThroughAnInvitation() throws Exception {
        String ownerToken = registerAndGetToken("admin17@example.com");
        String memberToken = registerTeammateAndGetToken(ownerToken, "member17@example.com");

        mockMvc.perform(post("/api/v1/auth/invitations")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"someone@example.com\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aNonAdminCanStillCreateAPlainMemberInvitation() throws Exception {
        // ADR 0031's flat "any member can invite" model is preserved for plain MEMBER
        // invitations - only requesting ADMIN is newly restricted.
        String ownerToken = registerAndGetToken("admin18@example.com");
        String memberToken = registerTeammateAndGetToken(ownerToken, "member18@example.com");

        mockMvc.perform(post("/api/v1/auth/invitations")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"someone-else@example.com\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void requestedDepartmentsAtRegistrationArePendingNotImmediatelyGranted() throws Exception {
        String ownerToken = registerAndGetToken("admin19@example.com");
        createDepartment(ownerToken, "Financeiro");
        String invitationToken = createInvitation(ownerToken, "pending-dept@example.com");

        String registerBody = """
                {"email":"pending-dept@example.com","password":"supersecret","invitationToken":"%s",
                "requestedDepartments":["Financeiro"]}
                """.formatted(invitationToken);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andReturn();
        String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();

        // Not approved yet - the freshly issued token carries no departments.
        assertThat(SignedJWT.parse(token).getJWTClaimsSet().getStringListClaim("departments")).isEmpty();

        JsonNode member = findUser(ownerToken, "pending-dept@example.com");
        assertThat(member.get("approvedDepartments")).isEmpty();
        assertThat(toTextList(member.get("pendingDepartments"))).containsExactly("Financeiro");
    }

    @Test
    void anAdminApprovingADepartmentRequestGrantsItOnTheNextLogin() throws Exception {
        String ownerToken = registerAndGetToken("admin20@example.com");
        String departmentId = createDepartment(ownerToken, "Financeiro");
        String invitationToken = createInvitation(ownerToken, "approved-dept@example.com");
        String registerBody = """
                {"email":"approved-dept@example.com","password":"supersecret","invitationToken":"%s",
                "requestedDepartments":["Financeiro"]}
                """.formatted(invitationToken);
        MvcResult registerResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andReturn();
        String memberUserId = objectMapper.readTree(registerResult.getResponse().getContentAsString())
                .get("userId").asText();

        mockMvc.perform(post("/api/v1/auth/users/" + memberUserId + "/department-requests/" + departmentId
                        + "/approve")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedDepartments[0]").value("Financeiro"))
                .andExpect(jsonPath("$.pendingDepartments").isEmpty());

        String loginBody = """
                {"email":"approved-dept@example.com","password":"supersecret"}
                """;
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andReturn();
        String newToken = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("token").asText();
        assertThat(SignedJWT.parse(newToken).getJWTClaimsSet().getStringListClaim("departments"))
                .containsExactly("Financeiro");
    }

    @Test
    void anAdminRejectingADepartmentRequestRemovesItWithNoHistoryKept() throws Exception {
        String ownerToken = registerAndGetToken("admin21@example.com");
        String departmentId = createDepartment(ownerToken, "Financeiro");
        String memberToken = registerTeammateAndGetToken(ownerToken, "rejected-dept@example.com");
        String memberUserId = userIdOf(memberToken);
        mockMvc.perform(post("/api/v1/auth/users/me/department-requests")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"departments\":[\"Financeiro\"]}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/auth/users/" + memberUserId + "/department-requests/" + departmentId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        JsonNode member = findUser(ownerToken, "rejected-dept@example.com");
        assertThat(member.get("approvedDepartments")).isEmpty();
        assertThat(member.get("pendingDepartments")).isEmpty();
        MvcResult queueResult = mockMvc.perform(get("/api/v1/auth/department-requests")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(queueResult.getResponse().getContentAsString())).isEmpty();
    }

    @Test
    void aUserCanRequestAdditionalDepartmentsAfterRegistration() throws Exception {
        String ownerToken = registerAndGetToken("admin22@example.com");
        createDepartment(ownerToken, "TI");
        String memberToken = registerTeammateAndGetToken(ownerToken, "self-service@example.com");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/users/me/department-requests")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"departments\":[\"TI\"]}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode profile = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(toTextList(profile.get("pendingDepartments"))).containsExactly("TI");
    }

    @Test
    void requestingAnAlreadyPendingDepartmentIsIdempotent() throws Exception {
        String ownerToken = registerAndGetToken("admin23@example.com");
        createDepartment(ownerToken, "TI");
        String memberToken = registerTeammateAndGetToken(ownerToken, "idempotent-dept@example.com");

        mockMvc.perform(post("/api/v1/auth/users/me/department-requests")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"departments\":[\"TI\"]}"))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(post("/api/v1/auth/users/me/department-requests")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"departments\":[\"TI\"]}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode profile = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(toTextList(profile.get("pendingDepartments"))).containsExactly("TI");
    }

    @Test
    void requestingAnUnknownDepartmentNameReturns404() throws Exception {
        String ownerToken = registerAndGetToken("admin24@example.com");
        String memberToken = registerTeammateAndGetToken(ownerToken, "unknown-dept@example.com");

        mockMvc.perform(post("/api/v1/auth/users/me/department-requests")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"departments\":[\"Does Not Exist\"]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getOwnProfileReturnsTheCallersOwnRoleAndDepartments() throws Exception {
        String ownerToken = registerAndGetToken("admin25@example.com");

        MvcResult result = mockMvc.perform(get("/api/v1/auth/users/me")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode profile = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(profile.get("email").asText()).isEqualTo("admin25@example.com");
        assertThat(profile.get("role").asText()).isEqualTo("ADMIN");
        assertThat(profile.get("approvedDepartments")).isEmpty();
        assertThat(profile.get("pendingDepartments")).isEmpty();
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

    // docs/adr/0060-multi-department-membership-and-approval.md
    private String createInvitationWithRole(String ownerToken, String invitedEmail, String role) throws Exception {
        String body = """
                {"email":"%s","role":"%s"}
                """.formatted(invitedEmail, role);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/invitations")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    /** Returns the newly created department's id. */
    private String createDepartment(String ownerToken, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/departments")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private JsonNode findUser(String adminToken, String email) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/users").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode users = objectMapper.readTree(result.getResponse().getContentAsString());
        for (JsonNode user : users) {
            if (user.get("email").asText().equals(email)) {
                return user;
            }
        }
        throw new AssertionError("No user found with email " + email);
    }

    private List<String> toTextList(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        arrayNode.forEach(node -> values.add(node.asText()));
        return values;
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
