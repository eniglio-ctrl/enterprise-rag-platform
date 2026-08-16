package com.eniglio.ragplatform.auth.service;

import com.eniglio.ragplatform.auth.config.AuthProperties;
import com.eniglio.ragplatform.auth.dto.DepartmentResponse;
import com.eniglio.ragplatform.auth.exception.InvalidInvitationException;
import com.eniglio.ragplatform.auth.repository.Invitation;
import com.eniglio.ragplatform.auth.repository.InvitationRepository;
import com.eniglio.ragplatform.common.security.Role;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * The invitation model this phase introduces (ADR 0031): a single-use, time-limited
 * grant tied to one specific {@code tenantId} and one specific email address - not a
 * generic, reusable "join code". {@link #redeem} does the expiry/single-use/email
 * checks up front against a plain read purely to produce a specific error message;
 * the actual grant of access is decided entirely by {@link
 * InvitationRepository#redeem(String)}'s atomic {@code UPDATE ... RETURNING}, so a
 * stale read here can never let two registrations consume the same invitation.
 * <p>
 * {@code role} (docs/adr/0060-multi-department-membership-and-approval.md): the
 * role this invitation grants on redemption, decided by whoever creates it (subject to
 * {@code InvitationController}'s "only an ADMIN may request ADMIN" rule).
 */
@Service
public class InvitationService {

    private final InvitationRepository invitationRepository;
    private final AuthProperties authProperties;
    private final DepartmentService departmentService;

    public InvitationService(InvitationRepository invitationRepository, AuthProperties authProperties,
            DepartmentService departmentService) {
        this.invitationRepository = invitationRepository;
        this.authProperties = authProperties;
        this.departmentService = departmentService;
    }

    public Invitation createInvitation(String tenantId, String email, Role role) {
        Instant expiresAt = Instant.now().plus(authProperties.invitationTtl());
        return invitationRepository.create(tenantId, email, role, expiresAt);
    }

    public Invitation redeem(String token, String email) {
        Invitation invitation = validate(token);
        if (!invitation.email().equalsIgnoreCase(email)) {
            throw new InvalidInvitationException("Invitation was issued to a different email address");
        }
        return invitationRepository.redeem(token)
                .orElseThrow(() -> new InvalidInvitationException("Invitation has already been used"));
    }

    /**
     * docs/adr/0060: unauthenticated by design - a valid, not-yet-used, unexpired
     * invitation token is itself the authorization to see the target tenant's
     * department names, which the registration form needs in order to render a picker
     * before the invitee has any account (or JWT) at all.
     */
    public List<DepartmentResponse> departmentsForToken(String token) {
        Invitation invitation = validate(token);
        return departmentService.listDepartments(invitation.tenantId());
    }

    private Invitation validate(String token) {
        Invitation invitation = invitationRepository.findByToken(token)
                .orElseThrow(() -> new InvalidInvitationException("Invitation not found"));
        if (invitation.isRedeemed()) {
            throw new InvalidInvitationException("Invitation has already been used");
        }
        if (invitation.isExpired()) {
            throw new InvalidInvitationException("Invitation has expired");
        }
        return invitation;
    }
}
