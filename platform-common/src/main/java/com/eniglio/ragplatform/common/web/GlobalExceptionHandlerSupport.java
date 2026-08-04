package com.eniglio.ragplatform.common.web;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;

/**
 * Shared exception handling for both services' {@code @RestControllerAdvice}: the
 * failure modes coming from Ollama being down (ADR 0009) are identical everywhere it's
 * called from, and the generic catch-all is the same too. Each service's own
 * {@code GlobalExceptionHandler} extends this and adds whatever handlers are specific
 * to it (validation, upload errors, etc.) — Spring resolves {@code @ExceptionHandler}
 * methods declared on a superclass just like ones declared directly on the advice bean.
 */
public abstract class GlobalExceptionHandlerSupport {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandlerSupport.class);

    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<ErrorResponse> handleCircuitOpen(CallNotPermittedException ex, HttpServletRequest request) {
        log.warn("Ollama circuit breaker is open, rejecting request without attempting it: {}", ex.getMessage());
        return build(HttpStatus.SERVICE_UNAVAILABLE,
                "O serviço de IA está temporariamente indisponível. Tente novamente em instantes.", request);
    }

    // docs/ROADMAP.md item #17: a saturated bulkhead means "already at the
    // configured concurrency limit for this dependency", a different situation
    // from the circuit breaker being open (dependency judged unhealthy) - both
    // map to the same 503 from the caller's point of view (retry later), but are
    // logged distinctly so the two failure modes stay distinguishable in practice.
    @ExceptionHandler(BulkheadFullException.class)
    public ResponseEntity<ErrorResponse> handleBulkheadFull(BulkheadFullException ex, HttpServletRequest request) {
        log.warn("Bulkhead full, rejecting request instead of queueing it: {}", ex.getMessage());
        return build(HttpStatus.SERVICE_UNAVAILABLE,
                "O serviço de IA está sob alta demanda. Tente novamente em instantes.", request);
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ErrorResponse> handleOllamaUnreachable(ResourceAccessException ex, HttpServletRequest request) {
        log.error("Ollama unreachable after retries", ex);
        return build(HttpStatus.SERVICE_UNAVAILABLE,
                "O serviço de IA está temporariamente indisponível. Tente novamente em instantes.", request);
    }

    // A real bug found by Security Phase 6's live verification: disabling an
    // endpoint (management.endpoints.web.exposure.include, springdoc.*.enabled) on
    // the demo profile made a request to it fall through Spring's own routing as
    // NoResourceFoundException - which the generic Exception.class handler below
    // was catching and turning into a misleading 500, instead of the real 404 no
    // route existing actually means. Predates this phase (any genuinely mistyped
    // path already hit this), only became visible once Phase 6 made previously-200
    // admin endpoints take this exact path.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "Not found", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request);
    }

    protected ResponseEntity<ErrorResponse> build(HttpStatus status, String message, HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(Instant.now(), status.value(), status.getReasonPhrase(), message, request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
