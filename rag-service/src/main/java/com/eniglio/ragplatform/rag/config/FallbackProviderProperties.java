package com.eniglio.ragplatform.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Multi-LLM Phase 2a: deliberately a separate config section from {@link
 * RagProperties#availableModels()}, not a couple more entries in that list. Every
 * entry in {@code rag.available-models} is immediately user-selectable in the
 * `web-ui` dropdown ({@code ModelsController} echoes the list verbatim) — putting a
 * public-cloud fallback provider there would make it pickable like any local model,
 * silently skipping the confirmation gate (Phase 2c) that keeps this feature honest
 * about company content never reaching a public API without the user's explicit,
 * per-call consent.
 * <p>
 * Both {@code apiKey} fields default to blank (never {@code :?required}) so the
 * {@code test} profile — which has no real key and must never call a real API — keeps
 * working exactly as before this phase. A blank key means {@link
 * com.eniglio.ragplatform.rag.gateway.LlmGateway#callOpenAiFallback}/{@code
 * callGeminiFallback} simply fail at the real provider's own auth check if ever
 * actually invoked with one, which only happens once Phase 2c's confirmation flow
 * exists to trigger it.
 */
@ConfigurationProperties(prefix = "rag.fallback-providers")
public record FallbackProviderProperties(OpenAi openai, Gemini gemini) {

    public record OpenAi(String apiKey, String model) {
    }

    /**
     * {@code model} defaults to {@code gemini-flash-latest} in {@code
     * application.yml}, not a specific dated version - confirmed by direct testing
     * against the real Generative Language API (not Vertex AI, which is a different
     * Google product Spring AI 1.0.0 only ships a starter for) that dated aliases
     * like {@code gemini-2.5-flash}/{@code gemini-1.5-flash} can 404 for a given
     * account ("no longer available to new users" / deprecated) while the
     * {@code -latest} alias resolves correctly.
     */
    public record Gemini(String apiKey, String model) {
    }
}
