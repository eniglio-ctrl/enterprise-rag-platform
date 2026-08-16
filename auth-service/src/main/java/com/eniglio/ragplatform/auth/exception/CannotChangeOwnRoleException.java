package com.eniglio.ragplatform.auth.exception;

/**
 * ADR 0047: blocks self-demotion (and self-promotion) outright, rather than counting
 * how many other ADMINs a tenant has left - simpler, and it makes it impossible for a
 * tenant's only ADMIN to ever lock themselves out of the role.
 */
public class CannotChangeOwnRoleException extends RuntimeException {

    public CannotChangeOwnRoleException() {
        super("You cannot change your own role");
    }
}
