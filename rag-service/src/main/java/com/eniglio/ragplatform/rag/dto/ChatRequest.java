package com.eniglio.ragplatform.rag.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code grounded} opts into a second LLM call that verifies the answer is actually
 * supported by the retrieved context (see ADR 0008). {@code rerank} opts into an
 * LLM-as-judge pass over a wider candidate pool from hybrid search (see ADR 0012).
 * Both default to off since each is a full extra Ollama round trip. {@code model}
 * picks a chat model from {@code rag.available-models} (ADR 0017), overriding the
 * configured default for this request only; {@code null} or an id not in that list
 * falls back to the default silently.
 * <p>
 * {@code useFallback} is the explicit confirmation step of the Multi-LLM Phase 2c
 * fallback flow (ADR 0038): a first request that triggers {@link
 * com.eniglio.ragplatform.rag.service.FallbackTriggerEvaluator} gets back {@code
 * fallbackAvailable: true} and no answer is generated at all — only a follow-up
 * request with {@code useFallback: true} actually calls a public LLM, and even then
 * only the raw {@code question} is ever sent to it, never any retrieved chunk or
 * document content. {@code fallbackProvider} picks which one ({@code "openai"} or
 * {@code "gemini"}); {@code null} or anything else defaults to {@code "gemini"} —
 * the only one of the two verified working end-to-end as of ADR 0036 (OpenAI's key
 * authenticates but the account has zero credits).
 */
public record ChatRequest(@NotBlank String question, Boolean grounded, Boolean rerank, String model,
                           Boolean useFallback, String fallbackProvider) {

    public boolean isGrounded() {
        return Boolean.TRUE.equals(grounded);
    }

    public boolean isRerank() {
        return Boolean.TRUE.equals(rerank);
    }

    public boolean isUseFallback() {
        return Boolean.TRUE.equals(useFallback);
    }
}
