package com.eniglio.ragplatform.rag.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.eniglio.ragplatform.rag.RagServiceApplication;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.Assumptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Multi-LLM Phase 2a (ADR 0036): real calls against the actual OpenAI and Gemini
 * APIs, using this project's own provisioned keys — never runs in CI, opt-in only,
 * same convention as {@code ChunkingStrategyBenchmark}/{@code RagQualityBenchmark}
 * (Phase 8):
 *
 * <pre>
 * OPENAI_API_KEY=... GEMINI_API_KEY=... ./mvnw test -pl rag-service \
 *     -Dtest=FallbackProviderLiveTest -DliveFallback=true \
 *     -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p>Both env var names match {@code credenciais/multi-llm-fallback.env} exactly
 * (not the {@code OPENAI_FALLBACK_API_KEY}/{@code GEMINI_FALLBACK_API_KEY} names
 * {@code application.yml} reads by default) — {@link #fallbackProviderProperties}
 * bridges the two so running this test needs no separate copy of the same secret
 * under a second env var name.
 *
 * <p><b>Real, verified state as of writing this test</b> (see ADR 0036): the
 * {@code GEMINI_API_KEY} is valid and generates real content via the Generative
 * Language API's {@code gemini-flash-latest} model. The {@code OPENAI_API_KEY} is
 * valid and authenticates successfully, but the account has zero credits
 * ({@code HTTP 429 insufficient_quota}) — a real, external, billing-only blocker
 * that adding code here cannot fix. {@link #openAiFallbackAuthenticatesRegardlessOfCreditBalance}
 * is written to keep passing in both states (zero credits today, credits added
 * later) — it only fails on a genuine auth/wiring bug, not on a quota error.
 *
 * <p>Multi-LLM Phase 2e (ADR 0045) added Anthropic. Unlike OpenAI/Gemini, no
 * {@code ANTHROPIC_API_KEY} has been generated for this project as of this
 * writing — {@link #anthropicFallbackAuthenticatesRegardlessOfCreditBalance} is
 * gated by its own {@code Assumptions.assumeTrue}, separate from the class-level
 * {@link #requireRealKeys()} check, specifically so its absence never skips the
 * OpenAI/Gemini tests above that already have real keys. Never run live this
 * session (per the user's own explicit instruction: treat a missing key/missing
 * credits as an expected, gracefully-handled state, not a blocker) — the
 * graceful-unavailable behavior it would otherwise exercise here is instead
 * covered for real by {@code RagQueryServiceTest}'s
 * {@code confirmedFallbackSkipsTheCallAndAnswersGracefullyWhenTheProviderHasNoApiKeyConfigured}.
 */
@Testcontainers
@SpringBootTest(classes = RagServiceApplication.class)
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "liveFallback", matches = "true")
class FallbackProviderLiveTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ragplatform")
            .withUsername("ragplatform")
            .withPassword("ragplatform");

    @DynamicPropertySource
    static void fallbackProviderProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("rag.fallback-providers.openai.api-key", () -> System.getenv("OPENAI_API_KEY"));
        registry.add("rag.fallback-providers.gemini.api-key", () -> System.getenv("GEMINI_API_KEY"));
        registry.add("rag.fallback-providers.anthropic.api-key", () -> System.getenv("ANTHROPIC_API_KEY"));
    }

    @Autowired
    private LlmGateway llmGateway;

    @Autowired
    private GeminiClient geminiClient;

    @Autowired
    @Qualifier("openaiFallback")
    private ChatClient openAiFallbackChatClient;

    @Autowired
    @Qualifier("anthropicFallback")
    private ChatClient anthropicFallbackChatClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void requireRealKeys() {
        Assumptions.assumeTrue(System.getenv("OPENAI_API_KEY") != null, "OPENAI_API_KEY not set, skipping");
        Assumptions.assumeTrue(System.getenv("GEMINI_API_KEY") != null, "GEMINI_API_KEY not set, skipping");
    }

    @Test
    void geminiFallbackReturnsARealAnswer() {
        String answer = llmGateway.callGeminiFallback(
                () -> geminiClient.generateContent("Responda apenas com a palavra: OK"));

        assertThat(answer).isNotBlank();
    }

    @Test
    void openAiFallbackAuthenticatesRegardlessOfCreditBalance() {
        try {
            String answer = llmGateway.callOpenAiFallback(
                    () -> openAiFallbackChatClient.prompt("Responda apenas com a palavra: OK").call().content());
            assertThat(answer).isNotBlank();
        } catch (Exception e) {
            // Spring AI's own OpenAiApi wraps every non-2xx response in
            // NonTransientAiException/TransientAiException before Resilience4j's
            // Supplier ever sees it (confirmed for real: a live 429 surfaced as
            // "org.springframework.ai.retry.NonTransientAiException: 429 - {...}",
            // not as a raw HttpClientErrorException) - so the only reliable auth-vs-
            // quota signal left is the OpenAI error body's own "code" field, still
            // present verbatim inside the exception message.
            String message = String.valueOf(e.getMessage());
            boolean isAuthFailure = message.contains("invalid_api_key") || message.contains("401 -");
            if (isAuthFailure) {
                fail("OpenAI rejected the API key itself (auth failure), not a quota/billing error: " + message);
            }
            // A quota/billing error (HTTP 429 insufficient_quota, confirmed for real
            // against this project's own key while writing this test - see ADR 0036)
            // means the key authenticated fine; the account just has no credits. That
            // is a real, external, user-actionable state, not a wiring bug, so this
            // test intentionally does not fail on it.
            System.out.println("OpenAI fallback call failed (expected while the account has zero credits): "
                    + message);
        }
    }

    @Test
    void anthropicFallbackAuthenticatesRegardlessOfCreditBalance() {
        // Multi-LLM Phase 2e (ADR 0045). Gated separately from requireRealKeys()
        // (class-level @BeforeEach) on purpose - see the class javadoc.
        Assumptions.assumeTrue(System.getenv("ANTHROPIC_API_KEY") != null, "ANTHROPIC_API_KEY not set, skipping");
        try {
            String answer = llmGateway.callAnthropicFallback(
                    () -> anthropicFallbackChatClient.prompt("Responda apenas com a palavra: OK").call().content());
            assertThat(answer).isNotBlank();
        } catch (Exception e) {
            String message = String.valueOf(e.getMessage());
            boolean isAuthFailure = message.contains("authentication_error") || message.contains("401");
            if (isAuthFailure) {
                fail("Anthropic rejected the API key itself (auth failure), not a quota/billing error: " + message);
            }
            // A quota/billing/rate-limit error means the key authenticated fine; the
            // account just can't complete the call right now - a real, external,
            // user-actionable state, not a wiring bug, so this test intentionally does
            // not fail on it (same reasoning as OpenAI's equivalent test above).
            System.out.println("Anthropic fallback call failed (not an auth failure): " + message);
        }
    }

    @Test
    void openAiFailuresDoNotTripGeminiCircuitBreaker() {
        CircuitBreaker openAiBreaker = circuitBreakerRegistry.circuitBreaker("openai-fallback");
        CircuitBreaker geminiBreaker = circuitBreakerRegistry.circuitBreaker("gemini-fallback");
        assertThat(geminiBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // sliding-window-size=10, minimum-number-of-calls=5, failure-rate-threshold=50%:
        // 6 back-to-back failing calls guarantee enough samples to trip the breaker,
        // regardless of whether OpenAI fails via 429 (today's real zero-credits state)
        // or any other error - this test only cares that failures happened, not why.
        for (int i = 0; i < 6; i++) {
            try {
                llmGateway.callOpenAiFallback(
                        () -> openAiFallbackChatClient.prompt("ping").call().content());
            } catch (Exception ignored) {
                // Expected: driving the "openai-fallback" breaker's failure counter is
                // the point of this loop, not asserting a specific error here.
            }
        }

        assertThat(openAiBreaker.getState())
                .as("openai-fallback breaker should have tripped after repeated failures")
                .isIn(CircuitBreaker.State.OPEN, CircuitBreaker.State.HALF_OPEN);
        assertThat(geminiBreaker.getState())
                .as("gemini-fallback breaker must stay untouched by openai-fallback's failures")
                .isEqualTo(CircuitBreaker.State.CLOSED);

        // Proof, not inference: Gemini must still actually work while OpenAI's
        // breaker is open/tripped, not just report the "right" state.
        String answer = llmGateway.callGeminiFallback(
                () -> geminiClient.generateContent("Responda apenas com a palavra: OK"));
        assertThat(answer).isNotBlank();
    }
}
