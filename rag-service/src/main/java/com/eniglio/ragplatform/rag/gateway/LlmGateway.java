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
 * keeps each call site's own prompt-building logic unchanged.
 */
@Component
public class LlmGateway {

    @CircuitBreaker(name = "ollama")
    @Retry(name = "ollama")
    public String call(Supplier<String> chatCall) {
        return chatCall.get();
    }
}
