package com.eniglio.ragplatform.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * {@code invitationToken} is optional (Security Phase 4, ADR 0031): absent, it
 * auto-creates a brand-new tenant for this user; present, it must redeem a valid,
 * unexpired, not-yet-used invitation whose email matches {@code email} exactly. There
 * is no longer a free-text {@code tenantId} field - joining an existing tenant by
 * typing its name is exactly what this phase removes.
 * <p>
 * {@code requestedDepartments} (docs/adr/0060-multi-department-membership-and
 * -approval.md): optional, only meaningful alongside {@code invitationToken} - the
 * department(s) the invitee wants to join, submitted as pending requests once the
 * account is created. Ignored entirely on the no-invitation (tenant-bootstrap) path,
 * since no department registry exists yet at that point.
 */
public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, message = "password must be at least 8 characters") String password,
        String invitationToken,
        List<String> requestedDepartments) {
}
