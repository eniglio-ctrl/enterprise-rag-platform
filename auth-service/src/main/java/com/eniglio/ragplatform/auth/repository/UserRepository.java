package com.eniglio.ragplatform.auth.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class UserRepository {

    private static final RowMapper<User> USER_MAPPER = (rs, rowNum) -> new User(
            rs.getString("id"), rs.getString("tenant_id"), rs.getString("email"), rs.getString("password_hash"));

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<User> findByEmail(String email) {
        return jdbcTemplate.query(
                        "SELECT id, tenant_id, email, password_hash FROM users WHERE lower(email) = lower(?)",
                        USER_MAPPER, email)
                .stream()
                .findFirst();
    }

    public boolean existsByEmail(String email) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE lower(email) = lower(?)", Integer.class, email);
        return count != null && count > 0;
    }

    public User create(String tenantId, String email, String passwordHash) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, tenant_id, email, password_hash) VALUES (?, ?, ?, ?)",
                id, tenantId, email, passwordHash);
        return new User(id.toString(), tenantId, email, passwordHash);
    }
}
