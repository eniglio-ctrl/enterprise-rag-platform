package com.eniglio.ragplatform.auth.controller;

import com.eniglio.ragplatform.auth.dto.DepartmentNamesRequest;
import com.eniglio.ragplatform.auth.dto.MyProfileResponse;
import com.eniglio.ragplatform.auth.dto.PendingDepartmentRequestResponse;
import com.eniglio.ragplatform.auth.dto.TenantUserResponse;
import com.eniglio.ragplatform.auth.dto.UpdateRoleRequest;
import com.eniglio.ragplatform.auth.service.UserManagementService;
import com.eniglio.ragplatform.common.security.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ADR 0047: most endpoints here require the caller to be an ADMIN of their own tenant
 * - every lookup stays scoped to {@code JwtClaims.tenantId(jwt)}, the same boundary
 * every other controller in this service already respects.
 * <p>
 * docs/adr/0060-multi-department-membership-and-approval.md: {@code /users/me} and
 * {@code /users/me/department-requests} are the two exceptions - any authenticated
 * member can call them, always acting on their own account (the JWT subject, never a
 * path parameter).
 */
@RestController
@Tag(name = "Users", description = "Tenant membership, role, and department management")
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

    @Operation(summary = "The caller's own role and department state", description = "Any authenticated member")
    @ApiResponse(responseCode = "200", description = "The caller's own profile")
    @GetMapping("/api/v1/auth/users/me")
    public MyProfileResponse getOwnProfile(@AuthenticationPrincipal Jwt jwt) {
        return userManagementService.getOwnProfile(JwtClaims.userId(jwt), JwtClaims.tenantId(jwt));
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

    @Operation(summary = "Replace a tenant member's approved departments", description = "Admin-only; "
            + "docs/adr/0060-multi-department-membership-and-approval.md - replaces the whole approved set")
    @ApiResponse(responseCode = "200", description = "Departments updated")
    @ApiResponse(responseCode = "403", description = "Caller is not a tenant admin")
    @ApiResponse(responseCode = "404", description = "No such user, or no such department, in the caller's tenant")
    @PatchMapping("/api/v1/auth/users/{userId}/departments")
    public TenantUserResponse replaceApprovedDepartments(
            @PathVariable("userId") String userId,
            @RequestBody DepartmentNamesRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return userManagementService.replaceApprovedDepartments(userId, JwtClaims.tenantId(jwt), JwtClaims.role(jwt),
                request.departments());
    }

    @Operation(summary = "Request to join one or more departments", description = "Any authenticated member; "
            + "creates a pending request per department, subject to admin approval")
    @ApiResponse(responseCode = "200", description = "The caller's own profile, including the new pending requests")
    @ApiResponse(responseCode = "404", description = "One of the requested department names doesn't exist")
    @PostMapping("/api/v1/auth/users/me/department-requests")
    public MyProfileResponse requestDepartments(
            @RequestBody DepartmentNamesRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return userManagementService.requestDepartments(JwtClaims.userId(jwt), JwtClaims.tenantId(jwt),
                request.departments());
    }

    @Operation(summary = "List every pending department request in the caller's tenant", description = "Admin-only")
    @ApiResponse(responseCode = "200", description = "Pending requests")
    @ApiResponse(responseCode = "403", description = "Caller is not a tenant admin")
    @GetMapping("/api/v1/auth/department-requests")
    public List<PendingDepartmentRequestResponse> listPendingRequests(@AuthenticationPrincipal Jwt jwt) {
        return userManagementService.listPendingRequests(JwtClaims.tenantId(jwt), JwtClaims.role(jwt));
    }

    @Operation(summary = "Approve a pending department request", description = "Admin-only")
    @ApiResponse(responseCode = "200", description = "Request approved")
    @ApiResponse(responseCode = "403", description = "Caller is not a tenant admin")
    @ApiResponse(responseCode = "404", description = "No pending request for that user/department")
    @PostMapping("/api/v1/auth/users/{userId}/department-requests/{departmentId}/approve")
    public TenantUserResponse approveDepartmentRequest(
            @PathVariable("userId") String userId,
            @PathVariable("departmentId") String departmentId,
            @AuthenticationPrincipal Jwt jwt) {
        return userManagementService.approveDepartmentRequest(userId, departmentId, JwtClaims.tenantId(jwt),
                JwtClaims.role(jwt));
    }

    @Operation(summary = "Reject a pending department request", description = "Admin-only; deletes it outright, "
            + "no history kept")
    @ApiResponse(responseCode = "204", description = "Request rejected")
    @ApiResponse(responseCode = "403", description = "Caller is not a tenant admin")
    @ApiResponse(responseCode = "404", description = "No pending request for that user/department")
    @DeleteMapping("/api/v1/auth/users/{userId}/department-requests/{departmentId}")
    public ResponseEntity<Void> rejectDepartmentRequest(
            @PathVariable("userId") String userId,
            @PathVariable("departmentId") String departmentId,
            @AuthenticationPrincipal Jwt jwt) {
        userManagementService.rejectDepartmentRequest(userId, departmentId, JwtClaims.tenantId(jwt),
                JwtClaims.role(jwt));
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
