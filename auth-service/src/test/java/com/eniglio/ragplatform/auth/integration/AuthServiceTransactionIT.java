package com.eniglio.ragplatform.auth.integration;

import com.eniglio.ragplatform.auth.AuthServiceApplication;
import com.eniglio.ragplatform.auth.repository.User;
import com.eniglio.ragplatform.auth.repository.UserRepository;
import com.eniglio.ragplatform.common.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A real bug flagged by the user: {@code AuthService.register()} redeemed an
 * invitation (or created a tenant, on the no-invitation path) before creating the
 * user - a failure in the user insert afterward (e.g. a real concurrent-registration
 * race hitting {@code users_email_idx}) left the invitation permanently consumed with
 * no user ever created. Fixed with {@code @Transactional} on {@code register()}.
 * <p>
 * {@code UserRepository} is mocked here, not the real thing {@code AuthIT} exercises -
 * this test isn't about reproducing the exact race (two concurrent HTTP requests
 * racing a unique index), it's about proving the transactional boundary actually
 * rolls back everything that happened earlier in the same method when
 * {@code userRepository.create} throws, which a mock can force deterministically and
 * a real race condition can't.
 */
@Testcontainers
@SpringBootTest(classes = AuthServiceApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthServiceTransactionIT {

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

    @org.springframework.beans.factory.annotation.Autowired
    private MockMvc mockMvc;

    @org.springframework.beans.factory.annotation.Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    void aFailedUserInsertRollsBackTheInvitationRedemption() throws Exception {
        String tenantId = "tx-test-tenant-" + UUID.randomUUID();
        String email = "tx-rollback-test@example.com";
        String token = "tx-rollback-token-" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tenants (id) VALUES (?)", tenantId);
        jdbcTemplate.update(
                "INSERT INTO invitations (id, tenant_id, email, token, expires_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), tenantId, email, token, Timestamp.from(Instant.now().plusSeconds(3600)));

        given(userRepository.existsByEmail(email)).willReturn(false);
        given(userRepository.create(anyString(), anyString(), anyString(), any(Role.class)))
                .willThrow(new RuntimeException("simulated insert failure"));

        String body = """
                {"email":"%s","password":"supersecret","invitationToken":"%s"}
                """.formatted(email, token);
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is5xxServerError());

        Timestamp redeemedAt = jdbcTemplate.queryForObject(
                "SELECT redeemed_at FROM invitations WHERE token = ?", Timestamp.class, token);
        assertThat(redeemedAt).as("invitation must stay unredeemed when the user was never actually created")
                .isNull();
    }

    @Test
    void aFailedUserInsertRollsBackTheNewTenantOnTheNoInvitationPath() throws Exception {
        String email = "tx-rollback-no-invite@example.com";
        given(userRepository.existsByEmail(email)).willReturn(false);
        given(userRepository.create(anyString(), anyString(), anyString(), any(Role.class)))
                .willThrow(new RuntimeException("simulated insert failure"));

        Integer tenantCountBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM tenants", Integer.class);

        String body = """
                {"email":"%s","password":"supersecret"}
                """.formatted(email);
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is5xxServerError());

        // A plain count delta, not a time-windowed or ID-based query: the tenant id
        // AuthService generates internally isn't observable from this black-box HTTP
        // test, and this project's test suite runs other test classes against
        // independently-provisioned Testcontainers instances, but not necessarily in
        // strict isolation within a single class's own container - a delta of zero is
        // the one assertion that can't produce a false pass either way.
        Integer tenantCountAfter = jdbcTemplate.queryForObject("SELECT count(*) FROM tenants", Integer.class);
        assertThat(tenantCountAfter).as("no new tenant row should survive without the user it was created for")
                .isEqualTo(tenantCountBefore);
    }
}
