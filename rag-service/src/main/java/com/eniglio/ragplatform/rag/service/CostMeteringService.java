package com.eniglio.ragplatform.rag.service;

import com.eniglio.ragplatform.rag.config.FallbackProviderProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * docs/adr/0056-llm-cost-metering-for-fallback-calls.md. Every model reachable
 * through {@link com.eniglio.ragplatform.rag.gateway.LlmGateway}'s local providers
 * (Ollama/LM Studio) is genuinely free to run - only the three public-LLM fallback
 * providers (ADR 0038/0045) have a real per-token dollar cost, so this is called
 * only from {@link RagQueryService#answerViaPublicLlmFallback} - local calls never
 * reach it, and their real cost (zero) is never even computed.
 */
@Component
public class CostMeteringService {

    private static final Logger log = LoggerFactory.getLogger(CostMeteringService.class);

    private final FallbackProviderProperties fallbackProviderProperties;
    private final MeterRegistry meterRegistry;

    public CostMeteringService(FallbackProviderProperties fallbackProviderProperties, MeterRegistry meterRegistry) {
        this.fallbackProviderProperties = fallbackProviderProperties;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Spring AI's {@code Usage} defaults to {@code EmptyUsage} (0/0, never {@code
     * null}) when a provider's response carries no usage metadata - confirmed by
     * reading Spring AI's own source, not assumed. A real, successful chat
     * completion never has literally zero tokens in both, so {@code promptTokens +
     * completionTokens == 0} is the actual signal for "this provider didn't report
     * usage", not a null-check that would never fire and would silently mask an
     * unknown cost as a known-zero one.
     */
    public void recordFallbackCall(String provider, String model, int promptTokens, int completionTokens) {
        if (promptTokens + completionTokens == 0) {
            log.warn("Provider '{}' returned no token usage metadata - cost for this call is unknown, not zero",
                    provider);
            Counter.builder("llm.cost.usage_unavailable")
                    .description("Fallback calls whose token usage metadata was unavailable, so cost could not be computed")
                    .tag("provider", provider)
                    .register(meterRegistry)
                    .increment();
            return;
        }

        PriceRate priceRate = priceRateFor(provider);
        double costUsd = (promptTokens / 1_000_000.0) * priceRate.inputPricePerMillionTokens()
                + (completionTokens / 1_000_000.0) * priceRate.outputPricePerMillionTokens();

        DistributionSummary.builder("llm.cost.usd")
                .description("Estimated USD cost of a public-LLM fallback call, from configured per-provider pricing")
                .tag("provider", provider)
                .tag("model", model)
                .register(meterRegistry)
                .record(costUsd);

        Counter.builder("llm.tokens.consumed")
                .description("Tokens consumed by public-LLM fallback calls")
                .tag("provider", provider)
                .tag("model", model)
                .tag("token_type", "prompt")
                .register(meterRegistry)
                .increment(promptTokens);
        Counter.builder("llm.tokens.consumed")
                .description("Tokens consumed by public-LLM fallback calls")
                .tag("provider", provider)
                .tag("model", model)
                .tag("token_type", "completion")
                .register(meterRegistry)
                .increment(completionTokens);
    }

    private PriceRate priceRateFor(String provider) {
        return switch (provider) {
            case "openai" -> new PriceRate(fallbackProviderProperties.openai().inputPricePerMillionTokens(),
                    fallbackProviderProperties.openai().outputPricePerMillionTokens());
            case "anthropic" -> new PriceRate(fallbackProviderProperties.anthropic().inputPricePerMillionTokens(),
                    fallbackProviderProperties.anthropic().outputPricePerMillionTokens());
            default -> new PriceRate(fallbackProviderProperties.gemini().inputPricePerMillionTokens(),
                    fallbackProviderProperties.gemini().outputPricePerMillionTokens());
        };
    }

    private record PriceRate(double inputPricePerMillionTokens, double outputPricePerMillionTokens) {
    }
}
