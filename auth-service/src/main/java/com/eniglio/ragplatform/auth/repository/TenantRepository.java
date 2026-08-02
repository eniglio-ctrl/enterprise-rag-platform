package com.eniglio.ragplatform.auth.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code tenants.id} is TEXT, not UUID (V2 migration) - it has to hold pre-existing
 * free-text tenant IDs from before this table existed (ADR 0016's original
 * simplified model), so the column type can't be tightened even though every *new*
 * tenant this repository creates uses a random UUID string as its id.
 */
@Repository
public class TenantRepository {

    private final JdbcTemplate jdbcTemplate;

    public TenantRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void create(String id) {
        jdbcTemplate.update("INSERT INTO tenants (id) VALUES (?)", id);
    }
}
