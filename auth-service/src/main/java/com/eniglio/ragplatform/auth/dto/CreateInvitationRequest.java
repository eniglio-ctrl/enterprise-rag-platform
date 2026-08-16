package com.eniglio.ragplatform.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * {@code role} (docs/adr/0060-multi-department-membership-and-approval.md): optional,
 * defaults to {@code MEMBER} when absent/blank. Requesting {@code ADMIN} is only
 * allowed when the caller is already an ADMIN (enforced in {@code InvitationController}).
 */
public record CreateInvitationRequest(@NotBlank @Email String email, String role) {
}
