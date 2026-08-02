package com.eniglio.ragplatform.auth.service;

import com.eniglio.ragplatform.auth.dto.AuthResponse;
import com.eniglio.ragplatform.auth.dto.LoginRequest;
import com.eniglio.ragplatform.auth.dto.RegisterRequest;
import com.eniglio.ragplatform.auth.exception.EmailAlreadyExistsException;
import com.eniglio.ragplatform.auth.exception.InvalidCredentialsException;
import com.eniglio.ragplatform.auth.repository.Invitation;
import com.eniglio.ragplatform.auth.repository.TenantRepository;
import com.eniglio.ragplatform.auth.repository.User;
import com.eniglio.ragplatform.auth.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Tenant/invitation model (Security Phase 4, ADR 0031, superseding ADR 0016's
 * caller-supplied free-text {@code tenantId}): registering with no {@code
 * invitationToken} always creates a brand-new tenant with a non-guessable UUID id -
 * there is no way to join an existing tenant by typing its name anymore. Registering
 * with a token redeems it via {@link InvitationService}, which enforces single-use,
 * expiry, and an exact email match before handing back the tenant to join.
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final InvitationService invitationService;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    public AuthService(UserRepository userRepository, TenantRepository tenantRepository,
            InvitationService invitationService, PasswordEncoder passwordEncoder, TokenService tokenService) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.invitationService = invitationService;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
    }

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException(request.email());
        }

        String tenantId;
        if (request.invitationToken() == null || request.invitationToken().isBlank()) {
            tenantId = UUID.randomUUID().toString();
            tenantRepository.create(tenantId);
        } else {
            Invitation invitation = invitationService.redeem(request.invitationToken(), request.email());
            tenantId = invitation.tenantId();
        }

        User user = userRepository.create(tenantId, request.email(), passwordEncoder.encode(request.password()));
        return toAuthResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(request.password(), user.passwordHash())) {
            throw new InvalidCredentialsException();
        }
        return toAuthResponse(user);
    }

    private AuthResponse toAuthResponse(User user) {
        String token = tokenService.issueToken(user);
        return new AuthResponse(token, "Bearer", tokenService.tokenTtlSeconds(), user.tenantId(), user.id());
    }
}
