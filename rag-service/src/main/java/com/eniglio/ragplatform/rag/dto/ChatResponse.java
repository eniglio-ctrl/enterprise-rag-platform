package com.eniglio.ragplatform.rag.dto;

import com.eniglio.ragplatform.common.web.Citation;

import java.util.List;

/**
 * {@code groundedness} is {@code null} unless the request opted in
 * ({@code "grounded": true}) — it's a second LLM call, so it's not run by default.
 * {@code model} is the chat model that actually generated {@code answer} (ADR 0017).
 */
public record ChatResponse(String answer, List<Citation> citations, Groundedness groundedness, String model) {
}
