package com.eniglio.ragplatform.auth.dto;

import java.time.Instant;

public record InvitationResponse(String token, String email, Instant expiresAt) {
}
