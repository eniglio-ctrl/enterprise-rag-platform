package com.eniglio.ragplatform.rag.gateway;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Every call to a chat model goes through here so {@code @CircuitBreaker}/
 * {@code @Retry} apply (see ADR 0009) — Resilience4j's annotations only intercept
 * calls made through the Spring proxy, so wrapping call sites inline inside
 * {@code RagQueryService} itself would silently do nothing (self-invocation bypasses
 * the proxy). Taking a {@code Supplier} instead of re-exposing {@code ChatClient}
 * keeps each call site's own prompt-building logic unchanged; generic so it covers
 * both plain-text calls ({@code .content()}) and structured-output ones
 * ({@code .entity(SomeRecord.class)}, used by the LLM reranker, ADR 0012).
 * <p>
 * Two methods, not one parametrized by provider name (ADR 0017): Resilience4j's
 * {@code @CircuitBreaker}/{@code @Retry} instance name is a compile-time annotation
 * attribute, so a single method can't pick "ollama" vs "lmstudio" at runtime — and
 * routing both providers' calls through the same named breaker would let an LM Studio
 * outage trip Ollama's circuit too, an unrelated dependency failing for an unrelated
 * reason. Callers pick the method matching {@code AvailableModel.provider()}.
 * <p>
 * Multi-LLM Phase 2a added {@code callOpenAiFallback}/{@code callGeminiFallback},
 * same shape and same reasoning: an invalid/exhausted OpenAI key must never trip
 * Gemini's breaker (or vice versa), and neither may share a breaker with the local
 * providers above — a cloud outage or quota exhaustion is a different failure mode
 * than a local server being down, and Phase 2b's fallback-trigger logic needs to be
 * able to tell them apart.
 * <p>
 * docs/ROADMAP.md item #17 added {@code @Bulkhead} to {@code callOllama}/{@code
 * callLmStudio} only, not the two cloud fallbacks: this is specifically about
 * protecting a local, single-process model server with genuinely limited concurrent
 * capacity from being overwhelmed by this application's own traffic — a cloud
 * provider scales independently of anything this bulkhead could do about it, and
 * the fallback path already has its own circuit breaker guarding against that
 * provider's outages/quota exhaustion.
 */
@Component
public class LlmGateway {

    @CircuitBreaker(name = "ollama")
    @Retry(name = "ollama")
    @Bulkhead(name = "ollama")
    public <T> T callOllama(Supplier<T> chatCall) {
        return chatCall.get();
    }

    @CircuitBreaker(name = "lmstudio")
    @Retry(name = "lmstudio")
    @Bulkhead(name = "lmstudio")
    public <T> T callLmStudio(Supplier<T> chatCall) {
        return chatCall.get();
    }

    @CircuitBreaker(name = "openai-fallback")
    @Retry(name = "openai-fallback")
    public <T> T callOpenAiFallback(Supplier<T> chatCall) {
        return chatCall.get();
    }

    @CircuitBreaker(name = "gemini-fallback")
    @Retry(name = "gemini-fallback")
    public <T> T callGeminiFallback(Supplier<T> chatCall) {
        return chatCall.get();
    }

    // Multi-LLM Phase 2e (ADR 0045): same shape/reasoning as the two fallbacks
    // above - an Anthropic auth/quota failure must never trip OpenAI's or
    // Gemini's breaker, or vice versa.
    @CircuitBreaker(name = "anthropic-fallback")
    @Retry(name = "anthropic-fallback")
    public <T> T callAnthropicFallback(Supplier<T> chatCall) {
        return chatCall.get();
    }
}
