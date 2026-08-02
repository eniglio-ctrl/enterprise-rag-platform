package com.eniglio.ragplatform.auth.controller;

import com.eniglio.ragplatform.auth.dto.CreateInvitationRequest;
import com.eniglio.ragplatform.auth.dto.InvitationResponse;
import com.eniglio.ragplatform.auth.repository.Invitation;
import com.eniglio.ragplatform.auth.service.InvitationService;
import com.eniglio.ragplatform.common.security.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Requires a valid bearer token, unlike {@link AuthController} - a caller can only
 * invite people into their own tenant (the one their own token's {@code tenantId}
 * claim names), never an arbitrary one. There is no separate admin/owner role
 * (ADR 0031 keeps that out of scope): any authenticated member of a tenant can invite
 * another member to it, mirroring how the tenant model overall stays deliberately
 * flat.
 */
@RestController
@Tag(name = "Invitations", description = "Invite a teammate into the caller's own tenant")
public class InvitationController {

    private final InvitationService invitationService;

    public InvitationController(InvitationService invitationService) {
        this.invitationService = invitationService;
    }

    @Operation(summary = "Invite a teammate",
            description = "Creates a single-use invitation, scoped to the caller's tenant and the given email")
    @PostMapping("/api/v1/auth/invitations")
    public ResponseEntity<InvitationResponse> create(@Valid @RequestBody CreateInvitationRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Invitation invitation = invitationService.createInvitation(JwtClaims.tenantId(jwt), request.email());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new InvitationResponse(invitation.token(), invitation.email(), invitation.expiresAt()));
    }
}
