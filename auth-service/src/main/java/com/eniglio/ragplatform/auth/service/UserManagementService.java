package com.eniglio.ragplatform.auth.service;

import com.eniglio.ragplatform.auth.dto.TenantUserResponse;
import com.eniglio.ragplatform.auth.exception.CannotChangeOwnRoleException;
import com.eniglio.ragplatform.auth.exception.InvalidRoleException;
import com.eniglio.ragplatform.auth.exception.NotTenantAdminException;
import com.eniglio.ragplatform.auth.exception.UserNotFoundException;
import com.eniglio.ragplatform.auth.repository.User;
import com.eniglio.ragplatform.auth.repository.UserRepository;
import com.eniglio.ragplatform.common.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ADR 0047: the two admin-only actions a tenant's ADMIN gets in this first version -
 * seeing who else is in the tenant, and promoting/demoting them. Both require the
 * caller to already be an ADMIN of their own tenant; {@link UserRepository#updateRole}
 * is itself scoped by {@code tenantId}, so a caller can never affect a user outside
 * their own tenant regardless of what id they pass.
 */
@Service
public class UserManagementService {

    private static final Logger log = LoggerFactory.getLogger(UserManagementService.class);

    private final UserRepository userRepository;

    public UserManagementService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public List<TenantUserResponse> listTenantUsers(String tenantId, Role callerRole) {
        requireAdmin(callerRole);
        return userRepository.findByTenantId(tenantId).stream()
                .map(user -> new TenantUserResponse(user.id(), user.email(), user.role().name()))
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
        User target = userRepository.findByIdAndTenantId(targetUserId, tenantId)
                .orElseThrow(() -> new UserNotFoundException(targetUserId));
        return new TenantUserResponse(target.id(), target.email(), target.role().name());
    }

    private void requireAdmin(Role callerRole) {
        if (callerRole != Role.ADMIN) {
            throw new NotTenantAdminException();
        }
    }
}
