package com.eniglio.ragplatform.auth.repository;

import com.eniglio.ragplatform.common.security.Role;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class UserRepository {

    private static final RowMapper<User> USER_MAPPER = (rs, rowNum) -> new User(
            rs.getString("id"), rs.getString("tenant_id"), rs.getString("email"), rs.getString("password_hash"),
            Role.valueOf(rs.getString("role")));

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<User> findByEmail(String email) {
        return jdbcTemplate.query(
                        "SELECT id, tenant_id, email, password_hash, role FROM users "
                                + "WHERE lower(email) = lower(?)",
                        USER_MAPPER, email)
                .stream()
                .findFirst();
    }

    public boolean existsByEmail(String email) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE lower(email) = lower(?)", Integer.class, email);
        return count != null && count > 0;
    }

    public User create(String tenantId, String email, String passwordHash, Role role) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, tenant_id, email, password_hash, role) VALUES (?, ?, ?, ?, ?)",
                id, tenantId, email, passwordHash, role.name());
        return new User(id.toString(), tenantId, email, passwordHash, role);
    }

    public List<User> findByTenantId(String tenantId) {
        return jdbcTemplate.query(
                "SELECT id, tenant_id, email, password_hash, role FROM users "
                        + "WHERE tenant_id = ? ORDER BY email",
                USER_MAPPER, tenantId);
    }

    // id = ?::uuid, not a bare id = ? - `id` is a `uuid` column and userId arrives here
    // as a plain String (the JWT subject claim), and Postgres has no implicit
    // uuid = character varying operator (the exact "operator does not exist" failure
    // ADR 0046's DocumentSharingRepository already hit and fixed the same way).
    public Optional<User> findByIdAndTenantId(String userId, String tenantId) {
        return jdbcTemplate.query(
                        "SELECT id, tenant_id, email, password_hash, role FROM users "
                                + "WHERE id = ?::uuid AND tenant_id = ?",
                        USER_MAPPER, userId, tenantId)
                .stream()
                .findFirst();
    }

    /**
     * Scoped by {@code tenantId} in the WHERE clause, not just {@code userId} - a
     * target belonging to a different tenant affects zero rows here rather than ever
     * being updated, and {@code UserManagementService} (ADR 0047) treats "zero rows" as
     * 404 for both "doesn't exist" and "exists in another tenant", the same
     * non-leaking spirit {@code InvitationService} already follows.
     */
    public int updateRole(String userId, String tenantId, Role role) {
        return jdbcTemplate.update(
                "UPDATE users SET role = ? WHERE id = ?::uuid AND tenant_id = ?",
                role.name(), userId, tenantId);
    }
}
