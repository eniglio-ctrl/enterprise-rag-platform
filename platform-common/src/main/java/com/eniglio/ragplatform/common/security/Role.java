package com.eniglio.ragplatform.common.security;

/**
 * ADR 0047: a tenant-scoped role, not a platform-wide one — an {@code ADMIN} manages
 * users and document permissions only within their own tenant, mirroring the absolute
 * tenant isolation every other part of this codebase already enforces. Shared between
 * auth-service (issues the {@code "role"} JWT claim) and ingestion-service (reads it to
 * decide whether a caller may override a document's own owner), the same way
 * {@link com.eniglio.ragplatform.common.authorization.DocumentVisibility} is shared for
 * the ABAC model (ADR 0046) — both services must agree on the exact same values.
 */
public enum Role {
    MEMBER,
    ADMIN
}
