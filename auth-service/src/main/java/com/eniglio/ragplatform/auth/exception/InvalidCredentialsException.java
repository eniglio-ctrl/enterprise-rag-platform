package com.eniglio.ragplatform.auth.exception;

/**
 * Carries the attempted email purely for server-side audit logging (Security Phase
 * 5) - never echoed back in the client-facing response, which stays the deliberately
 * generic "Invalid email or password" regardless of whether the email existed at
 * all, so a failed login can't be used to enumerate registered accounts.
 */
public class InvalidCredentialsException extends RuntimeException {

    private final String email;

    public InvalidCredentialsException(String email) {
        super("Invalid email or password");
        this.email = email;
    }

    public String email() {
        return email;
    }
}
