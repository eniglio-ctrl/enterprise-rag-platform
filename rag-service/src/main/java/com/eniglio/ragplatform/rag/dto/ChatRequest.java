package com.eniglio.ragplatform.rag.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code grounded} opts into a second LLM call that verifies the answer is actually
 * supported by the retrieved context (see ADR 0008) — off by default since it doubles
 * the latency of the request.
 */
public record ChatRequest(@NotBlank String question, Boolean grounded) {

    public boolean isGrounded() {
        return Boolean.TRUE.equals(grounded);
    }
}
