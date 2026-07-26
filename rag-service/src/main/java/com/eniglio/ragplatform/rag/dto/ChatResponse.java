package com.eniglio.ragplatform.rag.dto;

import java.util.List;

/**
 * {@code groundedness} is {@code null} unless the request opted in
 * ({@code "grounded": true}) — it's a second LLM call, so it's not run by default.
 */
public record ChatResponse(String answer, List<Citation> citations, Groundedness groundedness) {
}
