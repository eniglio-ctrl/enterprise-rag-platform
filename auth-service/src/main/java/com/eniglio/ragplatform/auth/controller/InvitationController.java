package com.eniglio.ragplatform.auth.controller;

import com.eniglio.ragplatform.auth.dto.CreateInvitationRequest;
import com.eniglio.ragplatform.auth.dto.DepartmentResponse;
import com.eniglio.ragplatform.auth.dto.InvitationResponse;
import com.eniglio.ragplatform.auth.exception.NotTenantAdminException;
import com.eniglio.ragplatform.auth.repository.Invitation;
import com.eniglio.ragplatform.auth.service.InvitationService;
import com.eniglio.ragplatform.common.security.JwtClaims;
import com.eniglio.ragplatform.common.security.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code create} requires a valid bearer token, unlike {@link AuthController} - a
 * caller can only invite people into their own tenant (the one their own token's
 * {@code tenantId} claim names), never an arbitrary one. There is still no separate
 * admin/owner role for a plain {@code MEMBER} invitation (ADR 0031 keeps that flat):
 * any authenticated member of a tenant can invite another member to it. The one
 * exception (docs/adr/0060-multi-department-membership-and-approval.md): requesting
 * {@code role: "ADMIN"} on the invitation itself requires the caller to already be an
 * ADMIN - otherwise a plain member could hand a friend admin rights just by inviting
 * them, which the flat "any member can invite" model was never meant to allow.
 * <p>
 * {@code departmentsForToken} is deliberately unauthenticated - see
 * {@link InvitationService#departmentsForToken(String)}.
 */
@RestController
@Tag(name = "Invitations", description = "Invite a teammate into the caller's own tenant")
public class InvitationController {

    private final InvitationService invitationService;

    public InvitationController(InvitationService invitationService) {
        this.invitationService = invitationService;
    }

    @Operation(summary = "Invite a teammate",
            description = "Creates a single-use invitation, scoped to the caller's tenant and the given email. "
                    + "Requesting role=ADMIN requires the caller to already be an ADMIN")
    @PostMapping("/api/v1/auth/invitations")
    public ResponseEntity<InvitationResponse> create(@Valid @RequestBody CreateInvitationRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Role requestedRole = request.role() == null || request.role().isBlank()
                ? Role.MEMBER : Role.valueOf(request.role().toUpperCase());
        if (requestedRole == Role.ADMIN && JwtClaims.role(jwt) != Role.ADMIN) {
            throw new NotTenantAdminException();
        }
        Invitation invitation = invitationService.createInvitation(JwtClaims.tenantId(jwt), request.email(),
                requestedRole);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new InvitationResponse(invitation.token(), invitation.email(), invitation.role().name(),
                        invitation.expiresAt()));
    }

    @Operation(summary = "List the departments of the tenant behind an invitation token", description =
            "Unauthenticated - a valid, unused, unexpired invitation token is itself the authorization; lets the "
                    + "registration form offer a department picker before the invitee has any account")
    @GetMapping("/api/v1/auth/invitations/{token}/departments")
    public List<DepartmentResponse> departmentsForToken(@PathVariable("token") String token) {
        return invitationService.departmentsForToken(token);
    }
}
