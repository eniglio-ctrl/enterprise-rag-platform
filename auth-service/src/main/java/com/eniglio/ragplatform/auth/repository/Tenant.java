package com.eniglio.ragplatform.auth.repository;

import java.time.Instant;

public record Tenant(String id, Instant createdAt) {
}
