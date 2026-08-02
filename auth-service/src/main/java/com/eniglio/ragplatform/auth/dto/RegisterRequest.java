package com.eniglio.ragplatform.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code invitationToken} is optional (Security Phase 4, ADR 0031): absent, it
 * auto-creates a brand-new tenant for this user; present, it must redeem a valid,
 * unexpired, not-yet-used invitation whose email matches {@code email} exactly. There
 * is no longer a free-text {@code tenantId} field - joining an existing tenant by
 * typing its name is exactly what this phase removes.
 */
public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, message = "password must be at least 8 characters") String password,
        String invitationToken) {
}
