package com.eniglio.ragplatform.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateDepartmentRequest(@NotBlank String name) {
}
