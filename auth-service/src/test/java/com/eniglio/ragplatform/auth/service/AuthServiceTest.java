package com.eniglio.ragplatform.auth.service;

import com.eniglio.ragplatform.auth.dto.AuthResponse;
import com.eniglio.ragplatform.auth.dto.LoginRequest;
import com.eniglio.ragplatform.auth.dto.RegisterRequest;
import com.eniglio.ragplatform.auth.exception.EmailAlreadyExistsException;
import com.eniglio.ragplatform.auth.exception.InvalidCredentialsException;
import com.eniglio.ragplatform.auth.repository.DepartmentRepository;
import com.eniglio.ragplatform.auth.repository.Invitation;
import com.eniglio.ragplatform.auth.repository.TenantRepository;
import com.eniglio.ragplatform.auth.repository.User;
import com.eniglio.ragplatform.auth.repository.UserDepartmentRepository;
import com.eniglio.ragplatform.auth.repository.UserRepository;
import com.eniglio.ragplatform.common.security.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private InvitationService invitationService;

    @Mock
    private DepartmentRepository departmentRepository;

    @Mock
    private UserDepartmentRepository userDepartmentRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TokenService tokenService;

    private AuthService newService() {
        return new AuthService(userRepository, tenantRepository, invitationService, departmentRepository,
                userDepartmentRepository, passwordEncoder, tokenService);
    }

    @Test
    void registeringWithNoInvitationTokenCreatesABrandNewTenantAndBecomesItsAdmin() {
        given(userRepository.existsByEmail("ana@example.com")).willReturn(false);
        given(passwordEncoder.encode("supersecret")).willReturn("hashed");
        given(tokenService.issueToken(any(), eq(List.of()))).willReturn("jwt-token");
        given(tokenService.tokenTtlSeconds()).willReturn(3600L);
        ArgumentCaptor<String> tenantIdCaptor = ArgumentCaptor.forClass(String.class);
        given(userRepository.create(tenantIdCaptor.capture(), eq("ana@example.com"), eq("hashed"), eq(Role.ADMIN)))
                .willAnswer(invocation -> new User("user-1", invocation.getArgument(0), "ana@example.com", "hashed",
                        Role.ADMIN));

        AuthResponse response = newService()
                .register(new RegisterRequest("ana@example.com", "supersecret", null, null));

        verify(tenantRepository).create(tenantIdCaptor.getValue());
        verify(invitationService, never()).redeem(anyString(), anyString());
        assertThat(response.tenantId()).isEqualTo(tenantIdCaptor.getValue());
        assertThat(response.token()).isEqualTo("jwt-token");
        assertThat(response.userId()).isEqualTo("user-1");
        assertThat(response.role()).isEqualTo("ADMIN");
    }

    @Test
    void registeringWithAValidInvitationTokenJoinsItsTenantAsAMemberInsteadOfCreatingOne() {
        given(userRepository.existsByEmail("ana@example.com")).willReturn(false);
        given(passwordEncoder.encode("supersecret")).willReturn("hashed");
        Invitation invitation = new Invitation("inv-1", "acme", "ana@example.com", "tok-1", Role.MEMBER,
                Instant.now().plusSeconds(3600), Instant.now(), Instant.now());
        given(invitationService.redeem("tok-1", "ana@example.com")).willReturn(invitation);
        User created = new User("user-1", "acme", "ana@example.com", "hashed", Role.MEMBER);
        given(userRepository.create("acme", "ana@example.com", "hashed", Role.MEMBER)).willReturn(created);
        given(tokenService.issueToken(created, List.of())).willReturn("jwt-token");
        given(tokenService.tokenTtlSeconds()).willReturn(3600L);

        AuthResponse response = newService()
                .register(new RegisterRequest("ana@example.com", "supersecret", "tok-1", null));

        verify(tenantRepository, never()).create(anyString());
        assertThat(response.tenantId()).isEqualTo("acme");
        assertThat(response.token()).isEqualTo("jwt-token");
        assertThat(response.role()).isEqualTo("MEMBER");
    }

    @Test
    void registeringWithAnAdminGrantingInvitationJoinsAsAnAdmin() {
        // docs/adr/0060-multi-department-membership-and-approval.md
        given(userRepository.existsByEmail("ana@example.com")).willReturn(false);
        given(passwordEncoder.encode("supersecret")).willReturn("hashed");
        Invitation invitation = new Invitation("inv-1", "acme", "ana@example.com", "tok-1", Role.ADMIN,
                Instant.now().plusSeconds(3600), Instant.now(), Instant.now());
        given(invitationService.redeem("tok-1", "ana@example.com")).willReturn(invitation);
        User created = new User("user-1", "acme", "ana@example.com", "hashed", Role.ADMIN);
        given(userRepository.create("acme", "ana@example.com", "hashed", Role.ADMIN)).willReturn(created);
        given(tokenService.issueToken(created, List.of())).willReturn("jwt-token");
        given(tokenService.tokenTtlSeconds()).willReturn(3600L);

        AuthResponse response = newService()
                .register(new RegisterRequest("ana@example.com", "supersecret", "tok-1", null));

        assertThat(response.role()).isEqualTo("ADMIN");
    }

    @Test
    void rejectsRegistrationWithAnAlreadyRegisteredEmail() {
        given(userRepository.existsByEmail("ana@example.com")).willReturn(true);

        assertThatThrownBy(() -> newService()
                .register(new RegisterRequest("ana@example.com", "supersecret", null, null)))
                .isInstanceOf(EmailAlreadyExistsException.class);

        verify(userRepository, never()).create(anyString(), anyString(), anyString(), any());
        verify(tenantRepository, never()).create(anyString());
    }

    @Test
    void logsInWithCorrectCredentialsAndIssuesAToken() {
        User existing = new User("user-1", "acme", "ana@example.com", "hashed", Role.MEMBER);
        given(userRepository.findByEmail("ana@example.com")).willReturn(Optional.of(existing));
        given(passwordEncoder.matches("supersecret", "hashed")).willReturn(true);
        given(userDepartmentRepository.findApprovedNamesByUserId("user-1")).willReturn(List.of());
        given(tokenService.issueToken(existing, List.of())).willReturn("jwt-token");
        given(tokenService.tokenTtlSeconds()).willReturn(3600L);

        AuthResponse response = newService().login(new LoginRequest("ana@example.com", "supersecret"));

        assertThat(response.token()).isEqualTo("jwt-token");
        assertThat(response.userId()).isEqualTo("user-1");
    }

    @Test
    void rejectsLoginWithAnUnknownEmail() {
        given(userRepository.findByEmail("nobody@example.com")).willReturn(Optional.empty());

        assertThatThrownBy(() -> newService().login(new LoginRequest("nobody@example.com", "whatever")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void rejectsLoginWithTheWrongPassword() {
        User existing = new User("user-1", "acme", "ana@example.com", "hashed", Role.MEMBER);
        given(userRepository.findByEmail("ana@example.com")).willReturn(Optional.of(existing));
        given(passwordEncoder.matches("wrong", "hashed")).willReturn(false);

        assertThatThrownBy(() -> newService().login(new LoginRequest("ana@example.com", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(tokenService, never()).issueToken(any(), any());
    }
}
