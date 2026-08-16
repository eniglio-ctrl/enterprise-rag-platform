package com.eniglio.ragplatform.auth.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * docs/adr/0060-multi-department-membership-and-approval.md. A separate class from
 * {@link DepartmentRepository} - different table, different lifecycle (requests get
 * approved/rejected, department names never change once created) - the same split
 * {@link InvitationRepository} already has from {@link UserRepository} despite the two
 * being related.
 */
@Repository
public class UserDepartmentRepository {

    private static final RowMapper<UserDepartment> USER_DEPARTMENT_MAPPER = (rs, rowNum) -> new UserDepartment(
            rs.getString("id"), rs.getString("user_id"), rs.getString("department_id"),
            rs.getString("department_name"), rs.getString("status"), rs.getTimestamp("requested_at").toInstant());

    private static final RowMapper<PendingUserDepartment> PENDING_MAPPER = (rs, rowNum) -> new PendingUserDepartment(
            rs.getString("user_id"), rs.getString("user_email"), rs.getString("department_id"),
            rs.getString("department_name"), rs.getTimestamp("requested_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public UserDepartmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<UserDepartment> findByUserId(String userId) {
        return jdbcTemplate.query(
                "SELECT ud.id, ud.user_id, ud.department_id, d.name AS department_name, ud.status, ud.requested_at "
                        + "FROM user_departments ud JOIN departments d ON d.id = ud.department_id "
                        + "WHERE ud.user_id = ?::uuid ORDER BY d.name",
                USER_DEPARTMENT_MAPPER, userId);
    }

    public List<String> findApprovedNamesByUserId(String userId) {
        return jdbcTemplate.query(
                "SELECT d.name FROM user_departments ud JOIN departments d ON d.id = ud.department_id "
                        + "WHERE ud.user_id = ?::uuid AND ud.status = ? ORDER BY d.name",
                (rs, rowNum) -> rs.getString("name"), userId, UserDepartment.APPROVED);
    }

    /** Tenant-wide queue for the admin approval screen - every user's pending requests at once. */
    public List<PendingUserDepartment> findPendingByTenantId(String tenantId) {
        return jdbcTemplate.query(
                "SELECT ud.user_id, u.email AS user_email, ud.department_id, d.name AS department_name, "
                        + "ud.requested_at FROM user_departments ud "
                        + "JOIN users u ON u.id = ud.user_id JOIN departments d ON d.id = ud.department_id "
                        + "WHERE u.tenant_id = ? AND ud.status = ? ORDER BY ud.requested_at",
                PENDING_MAPPER, tenantId, UserDepartment.PENDING);
    }

    /**
     * {@code ON CONFLICT ... DO NOTHING} on the {@code (user_id, department_id)} unique
     * index - the service layer already checks for an existing pending/approved row
     * before calling this (so a duplicate request is a silent no-op, not an error), but
     * this is a cheap defensive backstop against a race between that check and this
     * insert.
     */
    public void insertPending(String userId, String departmentId) {
        jdbcTemplate.update(
                "INSERT INTO user_departments (user_id, department_id, status) VALUES (?::uuid, ?::uuid, ?) "
                        + "ON CONFLICT (user_id, department_id) DO NOTHING",
                userId, departmentId, UserDepartment.PENDING);
    }

    /** Tenant-scoped the same defensive way {@code UserRepository.updateRole} already is. */
    public int approve(String userId, String departmentId, String tenantId) {
        return jdbcTemplate.update(
                "UPDATE user_departments SET status = ?, decided_at = now() "
                        + "WHERE user_id = ?::uuid AND department_id = ?::uuid AND status = ? "
                        + "AND EXISTS (SELECT 1 FROM users u WHERE u.id = user_departments.user_id "
                        + "AND u.tenant_id = ?)",
                UserDepartment.APPROVED, userId, departmentId, UserDepartment.PENDING, tenantId);
    }

    /** A straight DELETE - no history kept for a rejected request, by explicit product decision. */
    public int reject(String userId, String departmentId, String tenantId) {
        return jdbcTemplate.update(
                "DELETE FROM user_departments WHERE user_id = ?::uuid AND department_id = ?::uuid AND status = ? "
                        + "AND EXISTS (SELECT 1 FROM users u WHERE u.id = user_departments.user_id "
                        + "AND u.tenant_id = ?)",
                userId, departmentId, UserDepartment.PENDING, tenantId);
    }

    /**
     * Replaces the caller's *entire* approved set in one call - same "replace the whole
     * list" shape {@code DocumentSharingService.updateSharing} already uses for a
     * document's {@code sharedDepartments}. Any department in {@code departmentIds}
     * that already has a {@code PENDING} row for this user is silently promoted to
     * {@code APPROVED} via {@code ON CONFLICT ... DO UPDATE} - an admin's direct
     * assignment always takes precedence over an open request for the same department.
     */
    public void replaceApproved(String userId, String tenantId, List<String> departmentIds) {
        if (departmentIds.isEmpty()) {
            jdbcTemplate.update(
                    "DELETE FROM user_departments WHERE user_id = ?::uuid AND status = ? "
                            + "AND EXISTS (SELECT 1 FROM users u WHERE u.id = user_departments.user_id "
                            + "AND u.tenant_id = ?)",
                    userId, UserDepartment.APPROVED, tenantId);
            return;
        }
        String placeholders = departmentIds.stream().map(id -> "?::uuid").collect(Collectors.joining(","));
        List<Object> deleteParams = new ArrayList<>();
        deleteParams.add(userId);
        deleteParams.add(UserDepartment.APPROVED);
        deleteParams.addAll(departmentIds);
        deleteParams.add(tenantId);
        jdbcTemplate.update(
                "DELETE FROM user_departments WHERE user_id = ?::uuid AND status = ? "
                        + "AND department_id NOT IN (" + placeholders + ") "
                        + "AND EXISTS (SELECT 1 FROM users u WHERE u.id = user_departments.user_id "
                        + "AND u.tenant_id = ?)",
                deleteParams.toArray());
        for (String departmentId : departmentIds) {
            jdbcTemplate.update(
                    "INSERT INTO user_departments (user_id, department_id, status, decided_at) "
                            + "VALUES (?::uuid, ?::uuid, ?, now()) "
                            + "ON CONFLICT (user_id, department_id) DO UPDATE SET status = ?, decided_at = now()",
                    userId, departmentId, UserDepartment.APPROVED, UserDepartment.APPROVED);
        }
    }
}
