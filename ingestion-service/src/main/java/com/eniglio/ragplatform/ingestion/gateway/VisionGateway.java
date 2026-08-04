package com.eniglio.ragplatform.ingestion.gateway;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Wraps the vision-model call used to describe uploaded images (ADR 0018) so
 * {@code @CircuitBreaker}/{@code @Retry} apply — same "ollama" instance
 * {@link VectorStoreGateway} already uses for embedding calls (ADR 0009), since both
 * are Ollama and an outage affects them the same way. A separate bean, not a method
 * inside {@code ImageDescriptionService} itself, because Resilience4j's annotations
 * only intercept calls made through the Spring proxy — self-invocation bypasses it.
 */
@Component
public class VisionGateway {

    @CircuitBreaker(name = "ollama")
    @Retry(name = "ollama")
    @Bulkhead(name = "ollama")
    public <T> T call(Supplier<T> visionCall) {
        return visionCall.get();
    }
}
