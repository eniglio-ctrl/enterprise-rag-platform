package com.eniglio.ragplatform.rag.service;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multi-LLM Phase 2b: each trigger condition is verified independently, plus the
 * negative case (a normal, successful local answer must never offer the fallback) —
 * exactly the roadmap's own "done when" criterion for this phase. Uses a real
 * {@link CircuitBreakerRegistry} (not a mock) so {@link CircuitBreaker#transitionToOpenState()}
 * drives genuine state transitions, the same way production code would observe them.
 */
class FallbackTriggerEvaluatorTest {

    private final CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
    private final FallbackTriggerEvaluator evaluator = new FallbackTriggerEvaluator(circuitBreakerRegistry);

    private static final List<Document> SOME_CHUNKS = List.of(
            Document.builder().id("1").text("conteúdo relevante").metadata(Map.of("source", "a.md")).build());

    @Test
    void offersFallbackWhenTheLocalCircuitBreakerIsOpen() {
        circuitBreakerRegistry.circuitBreaker("ollama").transitionToOpenState();

        assertThat(evaluator.shouldOfferFallback("ollama", SOME_CHUNKS)).isTrue();
    }

    @Test
    void offersFallbackWhenTheLmStudioCircuitBreakerIsOpenIndependentlyOfOllama() {
        circuitBreakerRegistry.circuitBreaker("lmstudio").transitionToOpenState();

        assertThat(evaluator.shouldOfferFallback("lmstudio", SOME_CHUNKS)).isTrue();
        // A different provider's breaker tripping must not affect ollama's own check
        // (ADR 0009/0017's isolation principle applies to this decision too).
        assertThat(evaluator.shouldOfferFallback("ollama", SOME_CHUNKS)).isFalse();
    }

    @Test
    void offersFallbackWhenNoChunksWereRetrieved() {
        assertThat(evaluator.shouldOfferFallback("ollama", List.of())).isTrue();
    }

    @Test
    void doesNotOfferFallbackForANormalSuccessfulAnswer() {
        assertThat(evaluator.shouldOfferFallback("ollama", SOME_CHUNKS)).isFalse();
    }

    @Test
    void aClosedCircuitBreakerAloneIsNotEnoughToOfferFallback() {
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("ollama",
                CircuitBreakerConfig.custom().build());
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        assertThat(evaluator.shouldOfferFallback("ollama", SOME_CHUNKS)).isFalse();
    }
}
