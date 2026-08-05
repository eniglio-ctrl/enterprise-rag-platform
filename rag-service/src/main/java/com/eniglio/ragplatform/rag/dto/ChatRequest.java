package com.eniglio.ragplatform.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code question} is capped at 8000 characters - a single question, not a document;
 * the rate limiter (Security Phase 2) throttles request *volume*, not the size of any
 * one request, so without this a single oversized question could still pressure
 * embeddings, the database, and the LLM regardless of how few requests a caller sends.
 * <p>
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
 * document content. {@code fallbackProvider} picks which one ({@code "openai"},
 * {@code "anthropic"} — Multi-LLM Phase 2e, ADR 0045 — or {@code "gemini"});
 * {@code null} or anything else defaults to {@code "gemini"}, the only one of the
 * three verified working end-to-end as of ADR 0036 (OpenAI authenticates but the
 * account has zero credits; Anthropic has no key generated at all yet). Any
 * provider without a configured key, or one that rejects the request for a real
 * external reason, answers gracefully with {@code source: "public-llm-unavailable"}
 * rather than failing the request.
 */
public record ChatRequest(
        @NotBlank @Size(max = 8000, message = "question must be at most 8000 characters") String question,
        Boolean grounded, Boolean rerank, String model, Boolean useFallback, String fallbackProvider) {

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
