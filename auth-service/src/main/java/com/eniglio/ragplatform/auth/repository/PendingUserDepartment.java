package com.eniglio.ragplatform.auth.repository;

import java.time.Instant;

/**
 * docs/adr/0060-multi-department-membership-and-approval.md: the shape
 * {@link UserDepartmentRepository#findPendingByTenantId} returns for the admin's
 * tenant-wide approval queue - unlike {@link UserDepartment} (one user's own view of
 * their memberships), this needs to say *which* user each pending request belongs to.
 */
public record PendingUserDepartment(
        String userId,
        String userEmail,
        String departmentId,
        String departmentName,
        Instant requestedAt) {
}
