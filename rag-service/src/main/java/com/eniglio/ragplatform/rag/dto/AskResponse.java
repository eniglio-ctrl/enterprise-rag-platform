package com.eniglio.ragplatform.rag.dto;

import java.util.List;

/**
 * Unified response for {@code POST /api/v1/ask}. {@code type} is either "answer" (a
 * text response is in {@code answer}) or "diagram" (a Mermaid definition is in
 * {@code mermaid}) — only the field matching {@code type} is populated.
 * {@code groundedness} is only ever set for {@code type == "answer"}, and only when
 * the request opted into the check.
 */
public record AskResponse(String type, String answer, String mermaid, List<Citation> citations,
                           Groundedness groundedness) {
}
