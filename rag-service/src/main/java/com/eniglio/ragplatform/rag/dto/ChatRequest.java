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
 */
public record ChatRequest(@NotBlank String question, Boolean grounded, Boolean rerank, String model) {

    public boolean isGrounded() {
        return Boolean.TRUE.equals(grounded);
    }

    public boolean isRerank() {
        return Boolean.TRUE.equals(rerank);
    }
}
