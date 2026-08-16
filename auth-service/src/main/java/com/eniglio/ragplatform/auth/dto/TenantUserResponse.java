package com.eniglio.ragplatform.auth.dto;

import java.util.List;

/**
 * docs/adr/0060-multi-department-membership-and-approval.md: {@code department}
 * (single, nullable) replaced by two lists - a user can belong to several
 * departments, and the caller (the admin's Team screen) needs to tell approved apart
 * from still-pending. Both are never {@code null}, empty list when there are none.
 */
public record TenantUserResponse(
        String id, String email, String role, List<String> approvedDepartments, List<String> pendingDepartments) {
}
