package com.eniglio.ragplatform.rag.gateway;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Every call to the chat model goes through here so {@code @CircuitBreaker}/
 * {@code @Retry} apply (see ADR 0009) — Resilience4j's annotations only intercept
 * calls made through the Spring proxy, so wrapping call sites inline inside
 * {@code RagQueryService} itself would silently do nothing (self-invocation bypasses
 * the proxy). Taking a {@code Supplier} instead of re-exposing {@code ChatClient}
 * keeps each call site's own prompt-building logic unchanged; generic so it covers
 * both plain-text calls ({@code .content()}) and structured-output ones
 * ({@code .entity(SomeRecord.class)}, used by the LLM reranker, ADR 0012).
 */
@Component
public class LlmGateway {

    @CircuitBreaker(name = "ollama")
    @Retry(name = "ollama")
    public <T> T call(Supplier<T> chatCall) {
        return chatCall.get();
    }
}
