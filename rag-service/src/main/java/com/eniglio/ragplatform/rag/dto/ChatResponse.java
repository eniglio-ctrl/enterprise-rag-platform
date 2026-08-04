package com.eniglio.ragplatform.rag.dto;

import com.eniglio.ragplatform.common.web.Citation;

import java.util.List;

/**
 * {@code groundedness} is {@code null} unless the request opted in
 * ({@code "grounded": true}) — it's a second LLM call, so it's not run by default.
 * {@code model} is the chat model that actually generated {@code answer} (ADR 0017).
 * <p>
 * Multi-LLM Phase 2c (ADR 0038): {@code fallbackAvailable} is {@code true} only when
 * {@link com.eniglio.ragplatform.rag.service.FallbackTriggerEvaluator} fired and the
 * request did **not** confirm {@code useFallback} — {@code answer} is still populated
 * with an explanatory message in that case (so an older client that doesn't know
 * about this field still shows something sensible), but no LLM, local or public, was
 * actually called. {@code source} is one of three values, never left for the caller
 * to infer from the shape of the rest of the response, so {@code web-ui} (Phase 2d)
 * never has to guess: {@code "local"} (grounded in this tenant's own retrieved
 * documents, the default/normal path), {@code "public-llm"} (a real call to
 * OpenAI/Gemini/Anthropic actually succeeded), or {@code "public-llm-unavailable"}
 * (Multi-LLM Phase 2e, ADR 0045 — the confirmed provider had no API key configured,
 * or rejected the request for a real external reason like an invalid key or no
 * credits/quota; {@code answer} is a graceful, human-readable message in that case,
 * never a raw exception).
 */
public record ChatResponse(String answer, List<Citation> citations, Groundedness groundedness, String model,
                            Boolean fallbackAvailable, String source) {
}
