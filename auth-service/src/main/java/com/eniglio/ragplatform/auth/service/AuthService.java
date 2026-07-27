package com.eniglio.ragplatform.auth.service;

import com.eniglio.ragplatform.auth.dto.AuthResponse;
import com.eniglio.ragplatform.auth.dto.LoginRequest;
import com.eniglio.ragplatform.auth.dto.RegisterRequest;
import com.eniglio.ragplatform.auth.exception.EmailAlreadyExistsException;
import com.eniglio.ragplatform.auth.exception.InvalidCredentialsException;
import com.eniglio.ragplatform.auth.repository.User;
import com.eniglio.ragplatform.auth.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Deliberately simple tenant model (ADR 0016): registration takes a caller-supplied
 * {@code tenantId} rather than provisioning a new organization or requiring an invite
 * flow. Two users registering with the same {@code tenantId} share a tenant; there is
 * no ownership/admin concept over a tenant. Good enough to demonstrate real
 * multi-tenant isolation (ADR 0007) without building an organization-management
 * feature that isn't the point of this portfolio project.
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, TokenService tokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
    }

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException(request.email());
        }
        User user = userRepository.create(request.tenantId(), request.email(),
                passwordEncoder.encode(request.password()));
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
