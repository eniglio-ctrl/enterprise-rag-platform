package com.eniglio.ragplatform.ingestion.exception;

/** ADR 0047: only an ADMIN of the caller's own tenant may list every document in it. */
public class NotTenantAdminException extends RuntimeException {

    public NotTenantAdminException() {
        super("Only a tenant admin may perform this action");
    }
}
