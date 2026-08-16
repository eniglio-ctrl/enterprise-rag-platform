package com.eniglio.ragplatform.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Capped at 8000 characters, mirroring {@code rag-service}'s {@code ChatRequest
 * .question} limit - a single conversation message, not a document; the rate limiter
 * throttles request volume, not the size of any one request.
 */
public record SendMessageRequest(
        @NotBlank @Size(max = 8000, message = "message must be at most 8000 characters") String message) {
}
