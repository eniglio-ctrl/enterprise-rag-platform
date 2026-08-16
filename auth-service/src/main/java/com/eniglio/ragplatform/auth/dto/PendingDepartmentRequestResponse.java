package com.eniglio.ragplatform.auth.dto;

import java.time.Instant;

/** docs/adr/0060-multi-department-membership-and-approval.md: one row of the admin's tenant-wide approval queue. */
public record PendingDepartmentRequestResponse(
        String userId, String userEmail, String departmentId, String departmentName, Instant requestedAt) {
}
