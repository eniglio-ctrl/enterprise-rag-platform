package com.eniglio.ragplatform.auth.dto;

import java.util.List;

/**
 * docs/adr/0060-multi-department-membership-and-approval.md: what {@code GET
 * /api/v1/auth/users/me} returns - the one endpoint any authenticated member (not just
 * an admin) can call to see their own role and department state, so the self-service
 * "My departments" screen doesn't have to fall back on a possibly-stale JWT claim.
 */
public record MyProfileResponse(
        String id, String email, String role, List<String> approvedDepartments, List<String> pendingDepartments) {
}
