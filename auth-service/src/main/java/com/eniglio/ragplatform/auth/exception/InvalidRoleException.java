package com.eniglio.ragplatform.auth.exception;

public class InvalidRoleException extends RuntimeException {

    public InvalidRoleException(String role) {
        super("role must be \"ADMIN\" or \"MEMBER\", got: " + role);
    }
}
