package com.eniglio.ragplatform.common.security;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Exercises the two "done when" criteria from Security Phase 2
 * (docs/SECURITY-HARDENING-ROADMAP.md) that matter most directly to this class: N+1
 * requests from the same key get a real 429, and a forged {@code X-Forwarded-For}
 * cannot move a client into a different bucket when no proxy hop is trusted. The
 * curl-loop-against-the-running-stack half of "done when" is verified manually
 * (ADR 0028), not here — this is the fast, deterministic unit-level half.
 */
class RateLimitFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static RateLimitProperties.Rule rule(String name, String pattern, boolean keyByIp, int capacity) {
        return new RateLimitProperties.Rule(name, pattern, keyByIp, capacity, Duration.ofMinutes(1));
    }

    @Test
    void requestsWithinCapacityAllPassThrough() throws Exception {
        RateLimitProperties properties = new RateLimitProperties(true, 0,
                List.of(rule("auth", "/api/v1/auth/*", true, 3)));
        RateLimitFilter filter = new RateLimitFilter(properties, new SimpleMeterRegistry());
        FilterChain chain = Mockito.mock(FilterChain.class);

        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
            request.setRemoteAddr("10.0.0.1");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(200);
        }
        verify(chain, times(3)).doFilter(Mockito.any(), Mockito.any());
    }

    @Test
    void theNPlusOnethRequestFromTheSameKeyIsRejectedWith429() throws Exception {
        RateLimitProperties properties = new RateLimitProperties(true, 0,
                List.of(rule("auth", "/api/v1/auth/*", true, 3)));
        RateLimitFilter filter = new RateLimitFilter(properties, new SimpleMeterRegistry());
        FilterChain chain = Mockito.mock(FilterChain.class);

        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
            request.setRemoteAddr("10.0.0.1");
            filter.doFilter(request, new MockHttpServletResponse(), chain);
        }

        MockHttpServletRequest fourthRequest = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        fourthRequest.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse fourthResponse = new MockHttpServletResponse();
        filter.doFilter(fourthRequest, fourthResponse, chain);

        assertThat(fourthResponse.getStatus()).isEqualTo(429);
        assertThat(fourthResponse.getHeader("Retry-After")).isNotNull();
        verify(chain, times(3)).doFilter(Mockito.any(), Mockito.any());
    }

    @Test
    void aForgedXForwardedForHasZeroEffectWhenNoProxyHopIsTrusted() throws Exception {
        RateLimitProperties properties = new RateLimitProperties(true, 0,
                List.of(rule("auth", "/api/v1/auth/*", true, 1)));
        RateLimitFilter filter = new RateLimitFilter(properties, new SimpleMeterRegistry());
        FilterChain chain = Mockito.mock(FilterChain.class);

        MockHttpServletRequest first = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        first.setRemoteAddr("10.0.0.1");
        first.addHeader("X-Forwarded-For", "1.2.3.4");
        filter.doFilter(first, new MockHttpServletResponse(), chain);

        // Same real peer, a different forged X-Forwarded-For — with trustedProxyHops
        // 0 (the default everywhere in this project), the header must be completely
        // ignored, so this still lands in the exact same bucket as the first request
        // and gets blocked, not treated as a fresh client.
        MockHttpServletRequest second = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        second.setRemoteAddr("10.0.0.1");
        second.addHeader("X-Forwarded-For", "9.9.9.9");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(second, secondResponse, chain);

        assertThat(secondResponse.getStatus()).isEqualTo(429);
        verify(chain, times(1)).doFilter(Mockito.any(), Mockito.any());
    }

    @Test
    void differentIpsGetIndependentBuckets() throws Exception {
        RateLimitProperties properties = new RateLimitProperties(true, 0,
                List.of(rule("auth", "/api/v1/auth/*", true, 1)));
        RateLimitFilter filter = new RateLimitFilter(properties, new SimpleMeterRegistry());
        FilterChain chain = Mockito.mock(FilterChain.class);

        MockHttpServletRequest first = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        first.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(first, firstResponse, chain);

        MockHttpServletRequest second = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        second.setRemoteAddr("10.0.0.2");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(second, secondResponse, chain);

        assertThat(firstResponse.getStatus()).isEqualTo(200);
        assertThat(secondResponse.getStatus()).isEqualTo(200);
    }

    @Test
    void keysByAuthenticatedTenantWhenTheRuleIsNotIpBased() throws Exception {
        RateLimitProperties properties = new RateLimitProperties(true, 0,
                List.of(rule("rag-query", "/api/v1/ask", false, 1)));
        RateLimitFilter filter = new RateLimitFilter(properties, new SimpleMeterRegistry());
        FilterChain chain = Mockito.mock(FilterChain.class);

        authenticateAs("acme");
        MockHttpServletRequest first = new MockHttpServletRequest("POST", "/api/v1/ask");
        first.setRemoteAddr("10.0.0.1");
        filter.doFilter(first, new MockHttpServletResponse(), chain);

        // Same tenant, different IP - still the same bucket, because the rule keys
        // by tenant, not by IP.
        MockHttpServletRequest second = new MockHttpServletRequest("POST", "/api/v1/ask");
        second.setRemoteAddr("10.0.0.9");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(second, secondResponse, chain);
        assertThat(secondResponse.getStatus()).isEqualTo(429);

        // A different tenant, even from the very same IP as the first request, gets
        // its own independent bucket.
        authenticateAs("globex");
        MockHttpServletRequest third = new MockHttpServletRequest("POST", "/api/v1/ask");
        third.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse thirdResponse = new MockHttpServletResponse();
        filter.doFilter(third, thirdResponse, chain);
        assertThat(thirdResponse.getStatus()).isEqualTo(200);
    }

    @Test
    void aPathMatchingNoRuleIsNeverRateLimited() throws Exception {
        RateLimitProperties properties = new RateLimitProperties(true, 0,
                List.of(rule("auth", "/api/v1/auth/*", true, 1)));
        RateLimitFilter filter = new RateLimitFilter(properties, new SimpleMeterRegistry());
        FilterChain chain = Mockito.mock(FilterChain.class);

        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
            request.setRemoteAddr("10.0.0.1");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(200);
        }
        verify(chain, times(5)).doFilter(Mockito.any(), Mockito.any());
    }

    @Test
    void disabledGloballyNeverBlocksRegardlessOfVolume() throws Exception {
        RateLimitProperties properties = new RateLimitProperties(false, 0,
                List.of(rule("auth", "/api/v1/auth/*", true, 1)));
        RateLimitFilter filter = new RateLimitFilter(properties, new SimpleMeterRegistry());
        FilterChain chain = Mockito.mock(FilterChain.class);

        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
            request.setRemoteAddr("10.0.0.1");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(200);
        }
        verify(chain, times(5)).doFilter(Mockito.any(), Mockito.any());
    }

    private static void authenticateAs(String tenantId) {
        Jwt jwt = Jwt.withTokenValue("test")
                .header("alg", "none")
                .claim("tenantId", tenantId)
                .subject(tenantId)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }
}
