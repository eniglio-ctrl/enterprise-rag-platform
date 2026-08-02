package com.eniglio.ragplatform.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;

class AuditingAccessDeniedHandlerTest {

    @Test
    void respondsWith403AndAnEmptyBody() throws Exception {
        AuditingAccessDeniedHandler handler =
                new AuditingAccessDeniedHandler(new RateLimitProperties(true, 0, java.util.List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/v1/documents/1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new AccessDeniedException("insufficient authority"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).isEmpty();
    }
}
