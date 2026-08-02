package com.eniglio.ragplatform.auth.service;

import com.eniglio.ragplatform.auth.config.AuthProperties;
import com.eniglio.ragplatform.auth.exception.InvalidInvitationException;
import com.eniglio.ragplatform.auth.repository.Invitation;
import com.eniglio.ragplatform.auth.repository.InvitationRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * The invitation model this phase introduces (ADR 0031): a single-use, time-limited
 * grant tied to one specific {@code tenantId} and one specific email address - not a
 * generic, reusable "join code". {@link #redeem} does the expiry/single-use/email
 * checks up front against a plain read purely to produce a specific error message;
 * the actual grant of access is decided entirely by {@link
 * InvitationRepository#redeem(String)}'s atomic {@code UPDATE ... RETURNING}, so a
 * stale read here can never let two registrations consume the same invitation.
 */
@Service
public class InvitationService {

    private final InvitationRepository invitationRepository;
    private final AuthProperties authProperties;

    public InvitationService(InvitationRepository invitationRepository, AuthProperties authProperties) {
        this.invitationRepository = invitationRepository;
        this.authProperties = authProperties;
    }

    public Invitation createInvitation(String tenantId, String email) {
        Instant expiresAt = Instant.now().plus(authProperties.invitationTtl());
        return invitationRepository.create(tenantId, email, expiresAt);
    }

    public Invitation redeem(String token, String email) {
        Invitation invitation = invitationRepository.findByToken(token)
                .orElseThrow(() -> new InvalidInvitationException("Invitation not found"));
        if (invitation.isRedeemed()) {
            throw new InvalidInvitationException("Invitation has already been used");
        }
        if (invitation.isExpired()) {
            throw new InvalidInvitationException("Invitation has expired");
        }
        if (!invitation.email().equalsIgnoreCase(email)) {
            throw new InvalidInvitationException("Invitation was issued to a different email address");
        }
        return invitationRepository.redeem(token)
                .orElseThrow(() -> new InvalidInvitationException("Invitation has already been used"));
    }
}
