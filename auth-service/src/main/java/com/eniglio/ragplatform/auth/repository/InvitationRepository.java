package com.eniglio.ragplatform.auth.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Redemption is done with a single atomic {@code UPDATE ... RETURNING} ({@link
 * #redeem(String)}), not a separate check-then-update - two concurrent redemption
 * attempts for the same token race on that one statement, and at most one can ever
 * see {@code redeemed_at IS NULL} at the moment it runs. {@link InvitationService}
 * still does a plain {@link #findByToken(String)} first only to produce a specific
 * error message (not found / already used / expired); that read being stale under a
 * race is harmless, since {@link #redeem(String)} is the only statement that actually
 * grants a tenant, and it re-checks everything itself.
 */
@Repository
public class InvitationRepository {

    private static final RowMapper<Invitation> INVITATION_MAPPER = (rs, rowNum) -> new Invitation(
            rs.getString("id"),
            rs.getString("tenant_id"),
            rs.getString("email"),
            rs.getString("token"),
            toInstant(rs.getTimestamp("expires_at")),
            toInstant(rs.getTimestamp("redeemed_at")),
            toInstant(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbcTemplate;

    public InvitationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Invitation create(String tenantId, String email, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        String token = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO invitations (id, tenant_id, email, token, expires_at) VALUES (?, ?, ?, ?, ?)",
                id, tenantId, email, token, Timestamp.from(expiresAt));
        return new Invitation(id.toString(), tenantId, email, token, expiresAt, null, Instant.now());
    }

    public Optional<Invitation> findByToken(String token) {
        return jdbcTemplate.query(
                        "SELECT id, tenant_id, email, token, expires_at, redeemed_at, created_at "
                                + "FROM invitations WHERE token = ?",
                        INVITATION_MAPPER, token)
                .stream()
                .findFirst();
    }

    public Optional<Invitation> redeem(String token) {
        return jdbcTemplate.query(
                        "UPDATE invitations SET redeemed_at = now() "
                                + "WHERE token = ? AND redeemed_at IS NULL AND expires_at > now() "
                                + "RETURNING id, tenant_id, email, token, expires_at, redeemed_at, created_at",
                        INVITATION_MAPPER, token)
                .stream()
                .findFirst();
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
