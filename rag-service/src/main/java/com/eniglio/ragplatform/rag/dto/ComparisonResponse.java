package com.eniglio.ragplatform.rag.dto;

import java.util.List;

/**
 * docs/adr/0057-document-comparison.md. {@code comparison} is structured prose
 * (agreements/contradictions/unique-points sections, each point citing which
 * document it came from by name inline) rather than a DTO parsed per document -
 * same reasoning {@link SummaryResponse} has no {@code citations} list: the
 * documents themselves are the context, and a rigid Java-side parser across N
 * documents would scale worse and be more fragile against a local model than
 * {@link FaqResponse}'s single-document Q&amp;A parsing already is.
 */
public record ComparisonResponse(String comparison, List<String> sources, List<String> documentIds, String model) {
}
