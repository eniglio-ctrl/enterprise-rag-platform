package com.eniglio.ragplatform.auth.exception;

/**
 * Also thrown when the target id belongs to a different tenant, deliberately
 * indistinguishable from "doesn't exist at all" - the same non-leaking spirit
 * {@code InvitationService} already follows for its own lookups.
 */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(String userId) {
        super("User not found: " + userId);
    }
}
