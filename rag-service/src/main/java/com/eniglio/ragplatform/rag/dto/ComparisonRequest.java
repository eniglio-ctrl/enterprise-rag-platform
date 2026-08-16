package com.eniglio.ragplatform.rag.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * docs/adr/0057-document-comparison.md. {@code @Size(min = 2)} enforces the one
 * fixed business rule ("comparing" needs at least two documents) at the request
 * boundary; the configurable upper bound ({@code rag.document-comparison.max-documents})
 * is enforced at runtime in {@code RagQueryService} instead, since a Bean
 * Validation annotation can't reference a dynamically configured property.
 * {@code @NotEmpty} is required alongside {@code @Size} - {@code @Size} alone
 * does not reject a {@code null} list.
 */
public record ComparisonRequest(
        @NotEmpty @Size(min = 2, message = "at least 2 documentIds are required to compare") List<String> documentIds,
        String model) {
}
