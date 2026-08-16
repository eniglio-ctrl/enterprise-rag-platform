package com.eniglio.ragplatform.rag.service;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Multi-LLM Phase 2b: decides, structurally, whether the public-LLM fallback
 * (Phase 2a) should be offered — never via keyword/string matching on the answer
 * text, the exact mistake ADR 0024 already replaced once for routing.
 * <p>
 * Two independent trigger conditions, either one sufficient:
 * <ul>
 *   <li><b>Local infra failure</b> — the resolved model's own Resilience4j circuit
 *   breaker ({@code ollama}/{@code lmstudio}, ADR 0009) is already {@code OPEN},
 *   meaning recent real calls to that provider have been failing. Checked
 *   <i>before</i> attempting generation, not by catching a failure after the
 *   fact — the point is to avoid one more doomed call against a provider already
 *   known to be down.</li>
 *   <li><b>Content insufficiency</b> — retrieval found nothing at all.</li>
 * </ul>
 * <p>
 * <b>A real correction to the roadmap's original text, found before writing this
 * class</b>: the plan said to reuse "the existing score already on every
 * citation" against a threshold. Inspecting {@link HybridSearchService#fuseWithRrf}
 * shows that score is the post-RRF-fusion score (sum of {@code 1/(60+rank)} across
 * whichever of the vector/full-text legs a document appeared in) — a completely
 * different, much smaller scale than the cosine-similarity {@code
 * rag.similarity-threshold} (0.5) the roadmap's phrasing implied. With {@code
 * RRF_K = 60}, even a document ranked #1 in both legs scores only
 * {@code 2/61 ≈ 0.033}; reusing 0.5 against this scale would make "insufficient"
 * true for every real answer. Since the vector leg already discards anything
 * below {@code rag.similarity-threshold} <i>before</i> fusion (ADR 0012), an
 * empty {@code retrieved} list is already the correct, meaningful signal for
 * "nothing relevant was found" — no second, arbitrarily-calibrated RRF-scale
 * threshold is needed on top of it.
 */
@Component
public class FallbackTriggerEvaluator {

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public FallbackTriggerEvaluator(CircuitBreakerRegistry circuitBreakerRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    /**
     * {@code provider} is {@link com.eniglio.ragplatform.rag.config.RagProperties.AvailableModel#provider()}
     * of the already-resolved model (never the raw, possibly-"auto" request field) —
     * mirrors how {@link RagQueryService#clientFor} and {@link RagQueryService#callLlm}
     * already dispatch by provider name.
     */
    public boolean shouldOfferFallback(String provider, List<Document> retrieved) {
        return isLocalCircuitOpen(provider) || retrieved.isEmpty();
    }

    boolean isLocalCircuitOpen(String provider) {
        String breakerName = "lmstudio".equals(provider) ? "lmstudio" : "ollama";
        return circuitBreakerRegistry.circuitBreaker(breakerName).getState() == CircuitBreaker.State.OPEN;
    }
}
