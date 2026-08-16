package com.eniglio.ragplatform.rag.service;

import com.eniglio.ragplatform.rag.config.FallbackProviderProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class CostMeteringServiceTest {

    private static final FallbackProviderProperties PROPERTIES = new FallbackProviderProperties(
            new FallbackProviderProperties.OpenAi("key", "gpt-4o-mini", 1.0, 2.0),
            new FallbackProviderProperties.Gemini("key", "gemini-flash-latest", 0.0, 0.0),
            new FallbackProviderProperties.Anthropic("key", "claude-haiku-4-5-20251001", 3.0, 6.0),
            Duration.ofSeconds(5), Duration.ofSeconds(30));

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final CostMeteringService service = new CostMeteringService(PROPERTIES, meterRegistry);

    @Test
    void recordsCostForOpenAiUsingItsConfiguredPrices() {
        service.recordFallbackCall("openai", "gpt-4o-mini", 1_000_000, 500_000);

        // 1,000,000 prompt tokens * $1.0/1M + 500,000 completion tokens * $2.0/1M = $2.00
        assertThat(meterRegistry.get("llm.cost.usd").tag("provider", "openai").summary().totalAmount())
                .isCloseTo(2.0, offset(1e-9));
    }

    @Test
    void recordsCostForAnthropicUsingItsOwnConfiguredPrices() {
        service.recordFallbackCall("anthropic", "claude-haiku-4-5-20251001", 1_000_000, 1_000_000);

        // 1,000,000 * $3.0/1M + 1,000,000 * $6.0/1M = $9.00
        assertThat(meterRegistry.get("llm.cost.usd").tag("provider", "anthropic").summary().totalAmount())
                .isCloseTo(9.0, offset(1e-9));
    }

    @Test
    void recordsZeroCostForGeminiOnItsFreeTierPricing() {
        service.recordFallbackCall("gemini", "gemini-flash-latest", 200_000, 100_000);

        assertThat(meterRegistry.get("llm.cost.usd").tag("provider", "gemini").summary().totalAmount())
                .isCloseTo(0.0, offset(1e-9));
        // Real tokens were still consumed, even though the price is $0 - the tokens
        // counter must reflect that, not silently look identical to "unavailable".
        assertThat(meterRegistry.get("llm.tokens.consumed")
                .tag("provider", "gemini").tag("token_type", "prompt").counter().count()).isEqualTo(200_000.0);
    }

    @Test
    void incrementsUsageUnavailableInsteadOfRecordingAFalseZeroCostWhenNoTokensAreReported() {
        service.recordFallbackCall("openai", "gpt-4o-mini", 0, 0);

        assertThat(meterRegistry.find("llm.cost.usd").summary()).isNull();
        assertThat(meterRegistry.get("llm.cost.usage_unavailable").tag("provider", "openai")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void tracksTokensConsumedSeparatelyByTokenType() {
        service.recordFallbackCall("openai", "gpt-4o-mini", 300, 150);

        assertThat(meterRegistry.get("llm.tokens.consumed")
                .tag("provider", "openai").tag("token_type", "prompt").counter().count()).isEqualTo(300.0);
        assertThat(meterRegistry.get("llm.tokens.consumed")
                .tag("provider", "openai").tag("token_type", "completion").counter().count()).isEqualTo(150.0);
    }
}
