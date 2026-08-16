package com.eniglio.ragplatform.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Security Phase 5: every audit event across every service needs a way to be
 * correlated to the single inbound request that caused it, without grepping
 * timestamps and hoping. Registered directly with the servlet container at the
 * highest precedence ({@link CorrelationIdConfig}), not via {@code HttpSecurity} -
 * so it runs before Spring Security's entire filter chain, meaning the ID is already
 * in {@link MDC} for every log line any later filter or controller emits, including
 * ones Spring Security itself produces before this project's own code ever runs (a
 * rejected/expired JWT, for instance). Every service's {@code logging.structured
 * .format.console: ecs} setting (already configured everywhere) automatically
 * serializes current MDC entries as top-level structured log fields - no per-call
 * plumbing is needed beyond this filter setting it once per request.
 * <p>
 * Reuses an inbound {@value #HEADER} if the caller already set one (a request
 * forwarded from another one of this project's own services - see
 * {@code RagServiceGateway}), otherwise generates a new one - either way, it's echoed
 * back as a response header so a caller can report the exact ID a failure happened
 * under.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
