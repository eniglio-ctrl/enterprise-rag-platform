package com.eniglio.ragplatform.auth.dto;

public record AuthResponse(String token, String tokenType, long expiresInSeconds, String tenantId, String userId) {
}
