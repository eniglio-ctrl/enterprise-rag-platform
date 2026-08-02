package com.eniglio.ragplatform.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.assertThat;

class AuditingAuthenticationEntryPointTest {

    @Test
    void respondsWith401AndAnEmptyBody() throws Exception {
        // sendError(status) - deliberately no message argument - is what keeps the
        // AuthenticationException's own message (e.g. "expired JWT", "malformed
        // token") server-side, in the audit log line only, never echoed to the
        // caller.
        AuditingAuthenticationEntryPoint entryPoint =
                new AuditingAuthenticationEntryPoint(new RateLimitProperties(true, 0, java.util.List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/invitations");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new BadCredentialsException("expired JWT"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).isEmpty();
    }
}
