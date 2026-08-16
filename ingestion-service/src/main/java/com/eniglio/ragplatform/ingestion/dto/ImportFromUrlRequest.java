package com.eniglio.ragplatform.ingestion.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * docs/EXTERNAL-DATA-INTEGRATION-ROADMAP.md Phase 1. {@code url} is otherwise
 * unvalidated here — scheme/host/private-address checks all happen in {@link
 * com.eniglio.ragplatform.ingestion.service.UrlDocumentFetcher}, which needs to run
 * them anyway to actually fetch, so a separate Bean Validation annotation here would
 * just duplicate that logic with a weaker (format-only) check.
 */
public record ImportFromUrlRequest(@NotBlank String url) {
}
