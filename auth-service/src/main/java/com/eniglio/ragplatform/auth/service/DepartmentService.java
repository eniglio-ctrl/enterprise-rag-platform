package com.eniglio.ragplatform.auth.service;

import com.eniglio.ragplatform.auth.dto.DepartmentResponse;
import com.eniglio.ragplatform.auth.exception.DepartmentAlreadyExistsException;
import com.eniglio.ragplatform.auth.exception.NotTenantAdminException;
import com.eniglio.ragplatform.auth.repository.Department;
import com.eniglio.ragplatform.auth.repository.DepartmentRepository;
import com.eniglio.ragplatform.common.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * docs/adr/0059-department-based-sharing.md, docs/adr/0060-multi-department-membership
 * -and-approval.md. Deliberately the smallest possible registry - create and list, no
 * rename/delete - exactly what was asked for. Departments exist purely to give
 * {@link UserManagementService} and document sharing (ingestion-service) a controlled
 * list of names to draw from instead of free-typed strings that could drift.
 * <p>
 * {@code createDepartment} stays admin-only. {@code listDepartments} does not (ADR
 * 0060) - any authenticated tenant member can see the tenant's department names, since
 * the self-service "request to join a department" screen needs to show the pickable
 * list to non-admins too, and a department name isn't sensitive information.
 */
@Service
public class DepartmentService {

    private static final Logger log = LoggerFactory.getLogger(DepartmentService.class);

    private final DepartmentRepository departmentRepository;

    public DepartmentService(DepartmentRepository departmentRepository) {
        this.departmentRepository = departmentRepository;
    }

    public DepartmentResponse createDepartment(String tenantId, Role callerRole, String name) {
        requireAdmin(callerRole);
        if (departmentRepository.existsByTenantIdAndName(tenantId, name)) {
            throw new DepartmentAlreadyExistsException(name);
        }
        Department created = departmentRepository.create(tenantId, name);
        log.info("Created department {} in tenant {}", created.name(), tenantId);
        return new DepartmentResponse(created.id(), created.name());
    }

    public List<DepartmentResponse> listDepartments(String tenantId) {
        return departmentRepository.findByTenantId(tenantId).stream()
                .map(department -> new DepartmentResponse(department.id(), department.name()))
                .toList();
    }

    private void requireAdmin(Role callerRole) {
        if (callerRole != Role.ADMIN) {
            throw new NotTenantAdminException();
        }
    }
}
