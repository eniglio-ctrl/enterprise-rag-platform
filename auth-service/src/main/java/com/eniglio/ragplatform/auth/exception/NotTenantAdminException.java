package com.eniglio.ragplatform.auth.exception;

/** ADR 0047: only an ADMIN of the caller's own tenant may manage its members. */
public class NotTenantAdminException extends RuntimeException {

    public NotTenantAdminException() {
        super("Only a tenant admin may perform this action");
    }
}
