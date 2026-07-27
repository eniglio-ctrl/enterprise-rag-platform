package com.eniglio.ragplatform.auth.repository;

public record User(String id, String tenantId, String email, String passwordHash) {
}
