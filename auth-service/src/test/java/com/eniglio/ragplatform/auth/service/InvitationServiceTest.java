package com.eniglio.ragplatform.auth.service;

import com.eniglio.ragplatform.auth.config.AuthProperties;
import com.eniglio.ragplatform.auth.exception.InvalidInvitationException;
import com.eniglio.ragplatform.auth.repository.Invitation;
import com.eniglio.ragplatform.auth.repository.InvitationRepository;
import com.eniglio.ragplatform.common.security.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class InvitationServiceTest {

    @Mock
    private InvitationRepository invitationRepository;

    @Mock
    private DepartmentService departmentService;

    private InvitationService newService() {
        AuthProperties properties = new AuthProperties(Duration.ofHours(1), Duration.ofDays(7));
        return new InvitationService(invitationRepository, properties, departmentService);
    }

    private static Invitation invitation(String tenantId, String email, Instant expiresAt, Instant redeemedAt) {
        return new Invitation("inv-1", tenantId, email, "tok-1", Role.MEMBER, expiresAt, redeemedAt, Instant.now());
    }

    @Test
    void createInvitationSetsExpiryFromTheConfiguredTtl() {
        ArgumentCaptor<Instant> expiresAtCaptor = ArgumentCaptor.forClass(Instant.class);
        given(invitationRepository.create(eq("acme"), eq("ana@example.com"), eq(Role.MEMBER),
                expiresAtCaptor.capture()))
                .willAnswer(invocation -> invitation("acme", "ana@example.com", invocation.getArgument(3), null));

        newService().createInvitation("acme", "ana@example.com", Role.MEMBER);

        Instant expiresAt = expiresAtCaptor.getValue();
        assertThat(expiresAt).isAfter(Instant.now().plus(Duration.ofDays(6)));
        assertThat(expiresAt).isBefore(Instant.now().plus(Duration.ofDays(8)));
    }

    @Test
    void redeemsAValidUnexpiredInvitationMatchingTheEmail() {
        Invitation pending = invitation("acme", "ana@example.com", Instant.now().plusSeconds(3600), null);
        Invitation redeemed = invitation("acme", "ana@example.com", pending.expiresAt(), Instant.now());
        given(invitationRepository.findByToken("tok-1")).willReturn(Optional.of(pending));
        given(invitationRepository.redeem("tok-1")).willReturn(Optional.of(redeemed));

        Invitation result = newService().redeem("tok-1", "ana@example.com");

        assertThat(result.isRedeemed()).isTrue();
        assertThat(result.tenantId()).isEqualTo("acme");
    }

    @Test
    void rejectsATokenThatDoesNotExist() {
        given(invitationRepository.findByToken("missing")).willReturn(Optional.empty());

        assertThatThrownBy(() -> newService().redeem("missing", "ana@example.com"))
                .isInstanceOf(InvalidInvitationException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void rejectsAnAlreadyRedeemedInvitation() {
        Invitation redeemed = invitation("acme", "ana@example.com", Instant.now().plusSeconds(3600), Instant.now());
        given(invitationRepository.findByToken("tok-1")).willReturn(Optional.of(redeemed));

        assertThatThrownBy(() -> newService().redeem("tok-1", "ana@example.com"))
                .isInstanceOf(InvalidInvitationException.class)
                .hasMessageContaining("already been used");
    }

    @Test
    void rejectsAnExpiredInvitation() {
        Invitation expired = invitation("acme", "ana@example.com", Instant.now().minusSeconds(1), null);
        given(invitationRepository.findByToken("tok-1")).willReturn(Optional.of(expired));

        assertThatThrownBy(() -> newService().redeem("tok-1", "ana@example.com"))
                .isInstanceOf(InvalidInvitationException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void rejectsAnInvitationRedeemedWithADifferentEmail() {
        Invitation pending = invitation("acme", "ana@example.com", Instant.now().plusSeconds(3600), null);
        given(invitationRepository.findByToken("tok-1")).willReturn(Optional.of(pending));

        assertThatThrownBy(() -> newService().redeem("tok-1", "someone-else@example.com"))
                .isInstanceOf(InvalidInvitationException.class)
                .hasMessageContaining("different email");
    }

    @Test
    void rejectsARaceWhereAnotherRequestRedeemsTheTokenFirst() {
        Invitation pending = invitation("acme", "ana@example.com", Instant.now().plusSeconds(3600), null);
        given(invitationRepository.findByToken("tok-1")).willReturn(Optional.of(pending));
        given(invitationRepository.redeem("tok-1")).willReturn(Optional.empty());

        assertThatThrownBy(() -> newService().redeem("tok-1", "ana@example.com"))
                .isInstanceOf(InvalidInvitationException.class)
                .hasMessageContaining("already been used");
    }

    @Test
    void departmentsForTokenReturnsTheTenantsDepartmentsForAValidToken() {
        // docs/adr/0060-multi-department-membership-and-approval.md
        Invitation pending = invitation("acme", "ana@example.com", Instant.now().plusSeconds(3600), null);
        given(invitationRepository.findByToken("tok-1")).willReturn(Optional.of(pending));

        newService().departmentsForToken("tok-1");

        verify(departmentService).listDepartments("acme");
    }

    @Test
    void departmentsForTokenRejectsAnExpiredToken() {
        Invitation expired = invitation("acme", "ana@example.com", Instant.now().minusSeconds(1), null);
        given(invitationRepository.findByToken("tok-1")).willReturn(Optional.of(expired));

        assertThatThrownBy(() -> newService().departmentsForToken("tok-1"))
                .isInstanceOf(InvalidInvitationException.class)
                .hasMessageContaining("expired");
    }
}
