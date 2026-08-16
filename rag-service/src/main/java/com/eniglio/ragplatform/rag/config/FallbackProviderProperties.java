package com.eniglio.ragplatform.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

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
 * <p>
 * docs/ROADMAP.md item #17's timeout audit found both cloud clients (OpenAI's own
 * SDK-built client, and {@link com.eniglio.ragplatform.rag.gateway.GeminiClient}'s
 * hand-built one) had no explicit timeout at all, unlike every local-model client in
 * this codebase. {@code connectTimeout}/{@code readTimeout} here are shared between
 * both providers rather than one pair per provider — same simplification already
 * used for {@code rag.ollama.*} covering both Ollama and LM Studio: these are both
 * cloud APIs with similar latency characteristics, and a much shorter read timeout
 * than local inference's — a cloud API hanging for 180s like a local model
 * legitimately can is itself a signal something is wrong, not normal latency.
 */
@ConfigurationProperties(prefix = "rag.fallback-providers")
public record FallbackProviderProperties(
        OpenAi openai, Gemini gemini, Anthropic anthropic, Duration connectTimeout, Duration readTimeout) {

    /**
     * docs/adr/0056-llm-cost-metering-for-fallback-calls.md: {@code
     * inputPricePerMillionTokens}/{@code outputPricePerMillionTokens} are
     * illustrative prices (the public list price range for the default {@code
     * gpt-4o-mini}/{@code gemini-flash-latest}/{@code claude-haiku-4-5} models
     * this project ships), not a maintained official price table - an operator
     * running a different model or a negotiated rate must update these to match
     * what they're actually billed.
     */
    public record OpenAi(String apiKey, String model, double inputPricePerMillionTokens,
                          double outputPricePerMillionTokens) {
    }

    /**
     * {@code model} defaults to {@code gemini-flash-latest} in {@code
     * application.yml}, not a specific dated version - confirmed by direct testing
     * against the real Generative Language API (not Vertex AI, which is a different
     * Google product Spring AI 1.0.0 only ships a starter for) that dated aliases
     * like {@code gemini-2.5-flash}/{@code gemini-1.5-flash} can 404 for a given
     * account ("no longer available to new users" / deprecated) while the
     * {@code -latest} alias resolves correctly. Pricing defaults to {@code 0.0}/
     * {@code 0.0}: this project's real key (ADR 0036) is on Gemini's genuinely free
     * tier, so $0.00 is the correct number to show, not a placeholder.
     */
    public record Gemini(String apiKey, String model, double inputPricePerMillionTokens,
                          double outputPricePerMillionTokens) {
    }

    /**
     * Multi-LLM Phase 2e (ADR 0045). Unlike OpenAI/Gemini, no {@code
     * ANTHROPIC_API_KEY} has been generated for this project yet — {@code apiKey}
     * is blank by default, on purpose and indefinitely, not a TODO. {@link
     * com.eniglio.ragplatform.rag.service.RagQueryService#answerViaPublicLlmFallback}
     * checks for a blank key before ever attempting a call, and treats an
     * authentication/quota failure from the provider the same way — both return a
     * clear, graceful response instead of a raw exception, exactly the behavior
     * OpenAI's own real zero-credits state already required.
     */
    public record Anthropic(String apiKey, String model, double inputPricePerMillionTokens,
                             double outputPricePerMillionTokens) {
    }
}
