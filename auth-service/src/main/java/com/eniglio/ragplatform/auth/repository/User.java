package com.eniglio.ragplatform.auth.repository;

import com.eniglio.ragplatform.common.security.Role;

public record User(String id, String tenantId, String email, String passwordHash, Role role) {
}
