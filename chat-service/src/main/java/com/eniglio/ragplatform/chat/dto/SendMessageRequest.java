package com.eniglio.ragplatform.chat.dto;

import jakarta.validation.constraints.NotBlank;

public record SendMessageRequest(@NotBlank String message) {
}
