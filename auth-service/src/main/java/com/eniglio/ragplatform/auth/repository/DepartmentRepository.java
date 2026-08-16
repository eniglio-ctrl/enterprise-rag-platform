package com.eniglio.ragplatform.auth.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * docs/adr/0059-department-based-sharing.md. Deliberately minimal - an admin can
 * create and list departments, nothing else (no rename/delete), matching exactly
 * what was asked for. Same case-insensitive-unique-per-tenant shape as
 * {@code UserRepository}'s own email uniqueness (V4 migration's index).
 */
@Repository
public class DepartmentRepository {

    private static final RowMapper<Department> DEPARTMENT_MAPPER = (rs, rowNum) -> new Department(
            rs.getString("id"), rs.getString("tenant_id"), rs.getString("name"));

    private final JdbcTemplate jdbcTemplate;

    public DepartmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Department create(String tenantId, String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO departments (id, tenant_id, name) VALUES (?, ?, ?)",
                id, tenantId, name);
        return new Department(id.toString(), tenantId, name);
    }

    public boolean existsByTenantIdAndName(String tenantId, String name) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM departments WHERE tenant_id = ? AND lower(name) = lower(?)",
                Integer.class, tenantId, name);
        return count != null && count > 0;
    }

    public List<Department> findByTenantId(String tenantId) {
        return jdbcTemplate.query(
                "SELECT id, tenant_id, name FROM departments WHERE tenant_id = ? ORDER BY name",
                DEPARTMENT_MAPPER, tenantId);
    }

    public Optional<Department> findByTenantIdAndName(String tenantId, String name) {
        return jdbcTemplate.query(
                        "SELECT id, tenant_id, name FROM departments WHERE tenant_id = ? AND lower(name) = lower(?)",
                        DEPARTMENT_MAPPER, tenantId, name)
                .stream()
                .findFirst();
    }
}
