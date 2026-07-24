package com.eniglio.ragplatform.rag.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(@NotBlank String question) {
}
