package com.eniglio.ragplatform.auth.controller;

import com.eniglio.ragplatform.auth.dto.TenantUserResponse;
import com.eniglio.ragplatform.auth.dto.UpdateRoleRequest;
import com.eniglio.ragplatform.auth.service.UserManagementService;
import com.eniglio.ragplatform.common.security.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ADR 0047: both endpoints require the caller to be an ADMIN of their own tenant -
 * there is still no separate super-admin role, only a per-tenant one, so every lookup
 * here stays scoped to {@code JwtClaims.tenantId(jwt)}, the same boundary every other
 * controller in this service already respects.
 */
@RestController
@Tag(name = "Users", description = "Tenant membership and role management (ADR 0047)")
public class UserController {

    private final UserManagementService userManagementService;

    public UserController(UserManagementService userManagementService) {
        this.userManagementService = userManagementService;
    }

    @Operation(summary = "List the caller's tenant members", description = "Admin-only")
    @ApiResponse(responseCode = "200", description = "Tenant members")
    @ApiResponse(responseCode = "403", description = "Caller is not a tenant admin")
    @GetMapping("/api/v1/auth/users")
    public List<TenantUserResponse> listUsers(@AuthenticationPrincipal Jwt jwt) {
        return userManagementService.listTenantUsers(JwtClaims.tenantId(jwt), JwtClaims.role(jwt));
    }

    @Operation(summary = "Promote or demote a tenant member", description = "Admin-only; a caller may not change "
            + "their own role")
    @ApiResponse(responseCode = "200", description = "Role updated")
    @ApiResponse(responseCode = "400", description = "role is neither ADMIN nor MEMBER, or targets the caller")
    @ApiResponse(responseCode = "403", description = "Caller is not a tenant admin")
    @ApiResponse(responseCode = "404", description = "No such user in the caller's tenant")
    @PatchMapping("/api/v1/auth/users/{userId}/role")
    public TenantUserResponse updateRole(
            @PathVariable("userId") String userId,
            @Valid @RequestBody UpdateRoleRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return userManagementService.updateRole(userId, JwtClaims.tenantId(jwt), JwtClaims.userId(jwt),
                JwtClaims.role(jwt), request.role());
    }
}
