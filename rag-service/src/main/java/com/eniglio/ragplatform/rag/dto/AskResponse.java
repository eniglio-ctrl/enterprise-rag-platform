package com.eniglio.ragplatform.rag.dto;

import com.eniglio.ragplatform.common.web.Citation;

import java.util.List;

/**
 * Unified response for {@code POST /api/v1/ask}. {@code type} is either "answer" (a
 * text response is in {@code answer}) or "diagram" (a Mermaid definition is in
 * {@code mermaid}) — only the field matching {@code type} is populated.
 * {@code groundedness} is only ever set for {@code type == "answer"}, and only when
 * the request opted into the check. {@code model} is the chat model that generated
 * the response (ADR 0017).
 * <p>
 * {@code fallbackAvailable}/{@code source} (Multi-LLM Phase 2c, ADR 0038) mirror
 * {@link ChatResponse}'s own fields of the same name — see there for the full
 * contract. Always {@code null}/{@code "local"} for {@code type == "diagram"}; the
 * public-LLM fallback only ever applies to text answers.
 */
public record AskResponse(String type, String answer, String mermaid, List<Citation> citations,
                           Groundedness groundedness, String model, Boolean fallbackAvailable, String source) {
}
