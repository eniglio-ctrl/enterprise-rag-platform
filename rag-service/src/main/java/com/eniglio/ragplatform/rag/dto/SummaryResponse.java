package com.eniglio.ragplatform.rag.dto;

/**
 * docs/PRODUCT-DIFFERENTIATION-ROADMAP.md Phase 8. No {@code citations} list, unlike
 * {@link AskResponse}/{@link ChatResponse} - the entire indexed document is the
 * context here, not a top-K similarity result, so {@code source}/{@code documentId}
 * alone give full traceability back to "that specific document" without needing a
 * per-claim citation.
 */
public record SummaryResponse(String summary, String source, String documentId, String model) {
}
