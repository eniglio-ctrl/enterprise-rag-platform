package com.eniglio.ragplatform.rag.dto;

import java.util.List;

/**
 * docs/PRODUCT-DIFFERENTIATION-ROADMAP.md Phase 8. Same reasoning as
 * {@link SummaryResponse} for the lack of a {@code citations} list - the whole
 * document is the context, so {@code source}/{@code documentId} alone give
 * traceability.
 */
public record FaqResponse(List<FaqItem> items, String source, String documentId, String model) {

    public record FaqItem(String question, String answer) {
    }
}
