package com.eniglio.ragplatform.rag.exception;

import com.eniglio.ragplatform.rag.dto.ErrorResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Invalid request");
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    /**
     * Thrown by Resilience4j (ADR 0009) when the "ollama" circuit is open — the
     * dependency is already known to be down, so this request wasn't even attempted.
     */
    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<ErrorResponse> handleCircuitOpen(CallNotPermittedException ex, HttpServletRequest request) {
        log.warn("Ollama circuit breaker is open, rejecting request without attempting it: {}", ex.getMessage());
        return build(HttpStatus.SERVICE_UNAVAILABLE,
                "O serviço de IA está temporariamente indisponível. Tente novamente em instantes.", request);
    }

    /**
     * Thrown after Resilience4j's retry attempts (ADR 0009) are exhausted — the only
     * outbound HTTP client in this service talks to Ollama, so this exception type is
     * unambiguous here.
     */
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ErrorResponse> handleOllamaUnreachable(ResourceAccessException ex, HttpServletRequest request) {
        log.error("Ollama unreachable after retries", ex);
        return build(HttpStatus.SERVICE_UNAVAILABLE,
                "O serviço de IA está temporariamente indisponível. Tente novamente em instantes.", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message, HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(Instant.now(), status.value(), status.getReasonPhrase(), message, request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
