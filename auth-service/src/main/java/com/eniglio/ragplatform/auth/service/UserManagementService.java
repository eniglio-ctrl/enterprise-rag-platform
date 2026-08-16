package com.eniglio.ragplatform.auth.service;

import com.eniglio.ragplatform.auth.dto.MyProfileResponse;
import com.eniglio.ragplatform.auth.dto.PendingDepartmentRequestResponse;
import com.eniglio.ragplatform.auth.dto.TenantUserResponse;
import com.eniglio.ragplatform.auth.exception.CannotChangeOwnRoleException;
import com.eniglio.ragplatform.auth.exception.DepartmentNotFoundException;
import com.eniglio.ragplatform.auth.exception.DepartmentRequestNotFoundException;
import com.eniglio.ragplatform.auth.exception.InvalidRoleException;
import com.eniglio.ragplatform.auth.exception.NotTenantAdminException;
import com.eniglio.ragplatform.auth.exception.UserNotFoundException;
import com.eniglio.ragplatform.auth.repository.DepartmentRepository;
import com.eniglio.ragplatform.auth.repository.PendingUserDepartment;
import com.eniglio.ragplatform.auth.repository.User;
import com.eniglio.ragplatform.auth.repository.UserDepartment;
import com.eniglio.ragplatform.auth.repository.UserDepartmentRepository;
import com.eniglio.ragplatform.auth.repository.UserRepository;
import com.eniglio.ragplatform.common.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ADR 0047: the admin-only actions a tenant's ADMIN gets over its members - seeing who
 * else is in the tenant, promoting/demoting them, and (docs/adr/0060-multi-department
 * -membership-and-approval.md) approving/rejecting/directly assigning department
 * membership. All admin-only methods require the caller to already be an ADMIN of
 * their own tenant; every repository call is itself scoped by {@code tenantId}, so a
 * caller can never affect a user outside their own tenant regardless of what id they
 * pass. {@code requestDepartments} and {@code getOwnProfile} are the two exceptions -
 * any authenticated member can call them, always acting on their own account only
 * (the target user id is never caller-supplied for those two).
 */
@Service
public class UserManagementService {

    private static final Logger log = LoggerFactory.getLogger(UserManagementService.class);

    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final UserDepartmentRepository userDepartmentRepository;

    public UserManagementService(UserRepository userRepository, DepartmentRepository departmentRepository,
            UserDepartmentRepository userDepartmentRepository) {
        this.userRepository = userRepository;
        this.departmentRepository = departmentRepository;
        this.userDepartmentRepository = userDepartmentRepository;
    }

    public List<TenantUserResponse> listTenantUsers(String tenantId, Role callerRole) {
        requireAdmin(callerRole);
        return userRepository.findByTenantId(tenantId).stream()
                .map(user -> toTenantUserResponse(user, userDepartmentRepository.findByUserId(user.id())))
                .toList();
    }

    public TenantUserResponse updateRole(String targetUserId, String tenantId, String callerUserId, Role callerRole,
            String requestedRole) {
        requireAdmin(callerRole);

        Role newRole;
        try {
            newRole = Role.valueOf(requestedRole == null ? "" : requestedRole.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidRoleException(requestedRole);
        }

        if (targetUserId.equals(callerUserId)) {
            throw new CannotChangeOwnRoleException();
        }

        int updated = userRepository.updateRole(targetUserId, tenantId, newRole);
        if (updated == 0) {
            throw new UserNotFoundException(targetUserId);
        }

        log.info("Changed role of user {} in tenant {} to {} (by {})", targetUserId, tenantId, newRole, callerUserId);
        return fetchOrThrow(targetUserId, tenantId);
    }

    /**
     * docs/adr/0060-multi-department-membership-and-approval.md: admin-only, replaces
     * the target user's *entire* approved department set in one call - same "replace
     * the whole list" shape {@code DocumentSharingService.updateSharing} already uses.
     * No "can't change your own" restriction, same as before. Wrapped in a transaction
     * since {@link UserDepartmentRepository#replaceApproved} issues several statements
     * that need to land together.
     */
    @Transactional
    public TenantUserResponse replaceApprovedDepartments(String targetUserId, String tenantId, Role callerRole,
            List<String> departmentNames) {
        requireAdmin(callerRole);
        List<String> departmentIds = resolveDepartmentIds(tenantId, departmentNames);
        userDepartmentRepository.replaceApproved(targetUserId, tenantId, departmentIds);
        log.info("Replaced approved departments of user {} in tenant {} with {}", targetUserId, tenantId,
                departmentNames);
        return fetchOrThrow(targetUserId, tenantId);
    }

    /**
     * docs/adr/0060: self-service - any authenticated member, always acting on their
     * own account. Idempotent: a name already {@code PENDING} or {@code APPROVED} for
     * the caller is silently skipped rather than erroring, since the request body can
     * carry several names at once and a partial conflict shouldn't fail the whole call.
     */
    public MyProfileResponse requestDepartments(String callerUserId, String tenantId, List<String> departmentNames) {
        List<String> departmentIds = resolveDepartmentIds(tenantId, departmentNames);
        for (String departmentId : departmentIds) {
            userDepartmentRepository.insertPending(callerUserId, departmentId);
        }
        log.info("User {} in tenant {} requested departments {}", callerUserId, tenantId, departmentNames);
        return getOwnProfile(callerUserId, tenantId);
    }

    public List<PendingDepartmentRequestResponse> listPendingRequests(String tenantId, Role callerRole) {
        requireAdmin(callerRole);
        return userDepartmentRepository.findPendingByTenantId(tenantId).stream()
                .map(UserManagementService::toPendingResponse)
                .toList();
    }

    public TenantUserResponse approveDepartmentRequest(String targetUserId, String departmentId, String tenantId,
            Role callerRole) {
        requireAdmin(callerRole);
        int updated = userDepartmentRepository.approve(targetUserId, departmentId, tenantId);
        if (updated == 0) {
            throw new DepartmentRequestNotFoundException(targetUserId, departmentId);
        }
        log.info("Approved department {} for user {} in tenant {}", departmentId, targetUserId, tenantId);
        return fetchOrThrow(targetUserId, tenantId);
    }

    /** No history kept for a rejected request, by explicit product decision - it's a straight delete. */
    public void rejectDepartmentRequest(String targetUserId, String departmentId, String tenantId, Role callerRole) {
        requireAdmin(callerRole);
        int deleted = userDepartmentRepository.reject(targetUserId, departmentId, tenantId);
        if (deleted == 0) {
            throw new DepartmentRequestNotFoundException(targetUserId, departmentId);
        }
        log.info("Rejected department {} for user {} in tenant {}", departmentId, targetUserId, tenantId);
    }

    /**
     * docs/adr/0060: any authenticated member can see their own role/department state
     * - unlike {@link #listTenantUsers}, this never leaks another user's data, since
     * {@code callerUserId} always comes from the caller's own JWT subject claim, never
     * a path parameter.
     */
    public MyProfileResponse getOwnProfile(String callerUserId, String tenantId) {
        User user = userRepository.findByIdAndTenantId(callerUserId, tenantId)
                .orElseThrow(() -> new UserNotFoundException(callerUserId));
        List<UserDepartment> memberships = userDepartmentRepository.findByUserId(user.id());
        return new MyProfileResponse(user.id(), user.email(), user.role().name(),
                namesWithStatus(memberships, UserDepartment.APPROVED), namesWithStatus(memberships, UserDepartment.PENDING));
    }

    private List<String> resolveDepartmentIds(String tenantId, List<String> departmentNames) {
        List<String> names = departmentNames == null ? List.of() : departmentNames;
        return names.stream()
                .map(name -> departmentRepository.findByTenantIdAndName(tenantId, name)
                        .orElseThrow(() -> new DepartmentNotFoundException(name))
                        .id())
                .toList();
    }

    private TenantUserResponse fetchOrThrow(String userId, String tenantId) {
        User user = userRepository.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        return toTenantUserResponse(user, userDepartmentRepository.findByUserId(user.id()));
    }

    private static TenantUserResponse toTenantUserResponse(User user, List<UserDepartment> memberships) {
        return new TenantUserResponse(user.id(), user.email(), user.role().name(),
                namesWithStatus(memberships, UserDepartment.APPROVED), namesWithStatus(memberships, UserDepartment.PENDING));
    }

    private static List<String> namesWithStatus(List<UserDepartment> memberships, String status) {
        return memberships.stream()
                .filter(membership -> status.equals(membership.status()))
                .map(UserDepartment::departmentName)
                .toList();
    }

    private static PendingDepartmentRequestResponse toPendingResponse(PendingUserDepartment pending) {
        return new PendingDepartmentRequestResponse(pending.userId(), pending.userEmail(), pending.departmentId(),
                pending.departmentName(), pending.requestedAt());
    }

    private void requireAdmin(Role callerRole) {
        if (callerRole != Role.ADMIN) {
            throw new NotTenantAdminException();
        }
    }
}
