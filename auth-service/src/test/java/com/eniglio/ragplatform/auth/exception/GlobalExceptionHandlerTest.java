package com.eniglio.ragplatform.auth.exception;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security Phase 5: {@code security.authentication.failed} is the one new metric
 * this phase adds for auth-service - {@link AuthServiceTest}-style unit tests don't
 * touch this handler at all (they assert on the thrown exception, not the HTTP
 * response), so this is the only place the counter's actual behavior is verified
 * directly.
 */
class GlobalExceptionHandlerTest {

    @Test
    void incrementsTheAuthenticationFailedCounterOnInvalidCredentials() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        GlobalExceptionHandler handler = new GlobalExceptionHandler(meterRegistry);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");

        handler.handleInvalidCredentials(new InvalidCredentialsException("ana@example.com"), request);
        handler.handleInvalidCredentials(new InvalidCredentialsException("bruno@example.com"), request);

        assertThat(meterRegistry.get("security.authentication.failed").counter().count()).isEqualTo(2.0);
    }

    @Test
    void doesNotIncrementTheAuthenticationFailedCounterOnADuplicateEmailRegistration() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        GlobalExceptionHandler handler = new GlobalExceptionHandler(meterRegistry);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/register");

        handler.handleEmailAlreadyExists(new EmailAlreadyExistsException("ana@example.com"), request);

        assertThat(meterRegistry.find("security.authentication.failed").counter()).isNull();
    }
}
