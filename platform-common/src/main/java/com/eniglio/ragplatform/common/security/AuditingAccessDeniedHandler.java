package com.eniglio.ragplatform.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * Security Phase 5: audits every request Spring Security rejects with a valid
 * principal but insufficient authority. Nothing in this project actually grants or
 * denies by role today (ADR 0031 keeps the tenant model deliberately flat, no RBAC),
 * so this mostly documents the case for whenever that changes rather than firing
 * regularly - kept alongside {@link AuditingAuthenticationEntryPoint} so both 401 and
 * 403 paths are audited consistently, not just the one that happens to fire today.
 */
public class AuditingAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(AuditingAccessDeniedHandler.class);

    private final RateLimitProperties properties;

    public AuditingAccessDeniedHandler(RateLimitProperties properties) {
        this.properties = properties;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        log.warn("Access denied (forbidden): {} {} from {} - {}", request.getMethod(), request.getRequestURI(),
                ClientIpResolver.resolve(request, properties.trustedProxyHops()), accessDeniedException.getMessage());
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
    }
}
