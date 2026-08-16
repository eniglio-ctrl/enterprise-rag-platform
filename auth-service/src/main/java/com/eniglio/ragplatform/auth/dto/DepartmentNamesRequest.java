package com.eniglio.ragplatform.auth.dto;

import java.util.List;

/**
 * docs/adr/0060-multi-department-membership-and-approval.md: the body shape for both
 * the admin's "replace this user's whole approved set" endpoint and the self-service
 * "request to join these departments" endpoint - same "replace/request the whole
 * list" shape {@code UpdateSharingRequest}'s {@code sharedDepartments} already uses in
 * ingestion-service. {@code null} is treated the same as an empty list by both
 * callers (clears the approved set / requests nothing).
 */
public record DepartmentNamesRequest(List<String> departments) {
}
