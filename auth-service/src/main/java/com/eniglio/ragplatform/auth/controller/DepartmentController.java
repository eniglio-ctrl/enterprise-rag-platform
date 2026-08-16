package com.eniglio.ragplatform.auth.controller;

import com.eniglio.ragplatform.auth.dto.CreateDepartmentRequest;
import com.eniglio.ragplatform.auth.dto.DepartmentResponse;
import com.eniglio.ragplatform.auth.service.DepartmentService;
import com.eniglio.ragplatform.common.security.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * docs/adr/0059-department-based-sharing.md: {@code createDepartment} is admin-only,
 * same tenant boundary every other controller in this service already respects.
 * {@code listDepartments} is not (docs/adr/0060-multi-department-membership-and
 * -approval.md) - any authenticated tenant member can see department names.
 */
@RestController
@Tag(name = "Departments", description = "Tenant-scoped department registry, used for document sharing")
public class DepartmentController {

    private final DepartmentService departmentService;

    public DepartmentController(DepartmentService departmentService) {
        this.departmentService = departmentService;
    }

    @Operation(summary = "Create a department in the caller's tenant", description = "Admin-only")
    @ApiResponse(responseCode = "201", description = "Department created")
    @ApiResponse(responseCode = "403", description = "Caller is not a tenant admin")
    @ApiResponse(responseCode = "409", description = "A department with this name already exists in the tenant")
    @PostMapping("/api/v1/auth/departments")
    public ResponseEntity<DepartmentResponse> createDepartment(
            @Valid @RequestBody CreateDepartmentRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        DepartmentResponse response = departmentService.createDepartment(
                JwtClaims.tenantId(jwt), JwtClaims.role(jwt), request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "List every department in the caller's tenant",
            description = "Any authenticated tenant member")
    @ApiResponse(responseCode = "200", description = "Tenant departments")
    @GetMapping("/api/v1/auth/departments")
    public List<DepartmentResponse> listDepartments(@AuthenticationPrincipal Jwt jwt) {
        return departmentService.listDepartments(JwtClaims.tenantId(jwt));
    }
}
