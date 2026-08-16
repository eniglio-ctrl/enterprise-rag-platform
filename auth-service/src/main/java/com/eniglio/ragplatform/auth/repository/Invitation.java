package com.eniglio.ragplatform.auth.repository;

import com.eniglio.ragplatform.common.security.Role;

import java.time.Instant;

/**
 * {@code role} (docs/adr/0060-multi-department-membership-and-approval.md): the role
 * redeeming this invitation grants, decided at creation time by whoever created it -
 * defaults to {@link Role#MEMBER}, and only an existing tenant ADMIN may create one
 * requesting {@link Role#ADMIN} (enforced in {@code InvitationController}, not here).
 */
public record Invitation(
        String id,
        String tenantId,
        String email,
        String token,
        Role role,
        Instant expiresAt,
        Instant redeemedAt,
        Instant createdAt) {

    public boolean isRedeemed() {
        return redeemedAt != null;
    }

    public boolean isExpired() {
        return expiresAt.isBefore(Instant.now());
    }
}
