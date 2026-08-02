package com.eniglio.ragplatform.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * Security Phase 5: audits every request Spring Security rejects before
 * authentication ever succeeds (missing, malformed, or expired bearer token). The
 * default behavior already returns 401 with an empty body and zero logging - this
 * keeps that exact response shape, only adding a structured audit log line
 * (correlation ID included automatically via MDC, {@code
 * com.eniglio.ragplatform.common.logging.CorrelationIdFilter}).
 */
public class AuditingAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(AuditingAuthenticationEntryPoint.class);

    private final RateLimitProperties properties;

    public AuditingAuthenticationEntryPoint(RateLimitProperties properties) {
        this.properties = properties;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        log.warn("Access denied (unauthenticated): {} {} from {} - {}", request.getMethod(),
                request.getRequestURI(), ClientIpResolver.resolve(request, properties.trustedProxyHops()),
                authException.getMessage());
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }
}
