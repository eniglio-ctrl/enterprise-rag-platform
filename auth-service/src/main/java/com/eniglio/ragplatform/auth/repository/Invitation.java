package com.eniglio.ragplatform.auth.repository;

import java.time.Instant;

public record Invitation(
        String id,
        String tenantId,
        String email,
        String token,
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
