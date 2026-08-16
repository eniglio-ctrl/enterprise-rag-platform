package com.eniglio.ragplatform.auth.repository;

import com.eniglio.ragplatform.common.security.Role;

/**
 * docs/adr/0060-multi-department-membership-and-approval.md: department membership
 * moved out of this record entirely and into {@link UserDepartmentRepository} - a user
 * can now belong to several departments, each with its own pending/approved state, so
 * a single field here no longer fits.
 */
public record User(String id, String tenantId, String email, String passwordHash, Role role) {
}
