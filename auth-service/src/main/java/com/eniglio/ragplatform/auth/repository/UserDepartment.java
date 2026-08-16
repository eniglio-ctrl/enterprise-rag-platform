package com.eniglio.ragplatform.auth.repository;

import java.time.Instant;

/**
 * docs/adr/0060-multi-department-membership-and-approval.md. {@code departmentName}
 * is resolved via a join in every query that returns this record - every consumer
 * needs the name (JWT claim, UI, sharing checks), never just the id.
 */
public record UserDepartment(
        String id,
        String userId,
        String departmentId,
        String departmentName,
        String status,
        Instant requestedAt) {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
}
