package com.eniglio.ragplatform.chat.gateway;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * docs/ROADMAP.md item #17: {@code ConversationService}'s own {@code
 * chatClient.prompt()...call()} had no {@code @CircuitBreaker}/{@code @Retry}/{@code
 * @Bulkhead} at all before this — unlike rag-service and ingestion-service's
 * equivalent local-model calls (both already wrapped by their own gateway classes,
 * ADR 0009), this service's direct Ollama call was a real, unflagged gap: a hung or
 * failing local model server here would hang/fail this request with no retry, no
 * circuit breaker, and no bound on concurrent calls. Same "wrap in a separate
 * {@code @Component}, take a {@code Supplier}" pattern as rag-service's own {@code
 * LlmGateway} — Resilience4j's annotations only intercept calls made through the
 * Spring proxy, so wrapping the call site inline inside {@code ConversationService}
 * itself would silently do nothing (self-invocation bypasses the proxy).
 */
@Component
public class LlmGateway {

    @CircuitBreaker(name = "ollama")
    @Retry(name = "ollama")
    @Bulkhead(name = "ollama")
    public <T> T callOllama(Supplier<T> chatCall) {
        return chatCall.get();
    }
}
