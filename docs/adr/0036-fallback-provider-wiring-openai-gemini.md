# ADR 0036: Fallback provider wiring — OpenAI + Gemini (Multi-LLM Phase 2a)

## Status
Accepted, with one known, real, external blocker (see Consequences).

## Context
`docs/MULTI-LLM-ORCHESTRATOR-ROADMAP.md` Phase 2a, Tier 1 item #8 of
`docs/ROADMAP.md`. Real cloud providers as an explicit, user-confirmed
**fallback** for when local retrieval fails or is insufficient (Phase 2b/2c,
not yet implemented) — never a manually-selectable dropdown entry like Ollama
or LM Studio, and never grounded in tenant documents (only the raw question
ever reaches a public API, keeping company content from leaving the box
without explicit, per-call consent).

Two real, load-bearing corrections surfaced during design, both found by
inspecting the actual resolved dependencies/jars rather than assuming from
the roadmap text or Spring AI's own docs:

1. **No manual `ChatModel` construction existed anywhere in this codebase.**
   `ChatClientConfig` only wraps Spring AI's own *autoconfigured*
   `ollamaChatModel`/`openAiChatModel` beans into `ChatClient`s (ADR 0017).
   Spring AI's OpenAI autoconfiguration supports exactly one
   `spring.ai.openai.*` property block per application context — already
   claimed by LM Studio's entry. A second, simultaneous OpenAI-family
   provider (real `api.openai.com`, for fallback) has no autoconfigured slot
   left, so it needs a manually-constructed `OpenAiApi`/`OpenAiChatModel`
   pair — a deliberate, documented exception to ADR 0017's
   "always autoconfigured" convention, not a violation of its spirit (ADR
   0017 never anticipated needing two simultaneous OpenAI-family providers).

2. **Spring AI 1.0.0 has no plain-API-key Gemini integration.** Its only
   Gemini support is `spring-ai-starter-model-vertex-ai-gemini`, which wraps
   **Vertex AI** — a GCP project + service-account-credentials product,
   fundamentally different from a bare API key. This project's real
   `GEMINI_API_KEY` (from `https://aistudio.google.com/apikey`, free, no
   card) is for the **Generative Language API**
   (`generativelanguage.googleapis.com`) instead — confirmed by calling both
   APIs directly, not assumed from either product's documentation.

## Decision

### `GeminiClient`: a plain REST client, not a Spring AI `ChatModel`
Since Spring AI ships no compatible starter, `GeminiClient`
(`rag-service/.../gateway/GeminiClient.java`) is a direct Spring `RestClient`
call to the Generative Language API's `generateContent` endpoint. It builds
its **own independent** `RestClient.builder()...build()` rather than
injecting `ChatClientConfig`'s shared `RestClient.Builder` bean — that bean
is a singleton Spring auto-detects and reuses for Ollama/LM Studio's own
autoconfigured HTTP clients (a different base URL, timeout profile, no
Gemini-specific header); calling `.baseUrl()`/`.defaultHeader()` on it
directly would risk mutating shared state meant for local-model calls. Caught
during implementation, before any test ran, by reasoning about Spring's
mutate-and-return-`this` builder semantics — not discovered via a failing
test.

**Model name**: defaults to `gemini-flash-latest`, not a dated alias.
Confirmed for real: `gemini-1.5-flash` and `gemini-2.5-flash` both returned
404 against this project's own key (deprecated / "no longer available to new
users", despite `gemini-2.5-flash` being listed as `generateContent`-capable
by the API's own `ListModels` response). Only the `-latest` alias (currently
resolving to `gemini-3.6-flash`) worked. Availability by exact dated model
name isn't reliable enough to hardcode for a given account.

### `openAiFallbackChatClient`: a second, manually-built OpenAI bean
`ChatClientConfig` gained `@EnableConfigurationProperties(FallbackProvider
Properties.class)` and a new `@Bean @Qualifier("openaiFallback")` method that
builds `OpenAiApi.builder()...build()` → `OpenAiChatModel.builder()...
build()` directly from `spring-ai-starter-model-openai`'s own classes (no new
dependency needed — that starter is already on the classpath for LM Studio).
Exact builder API confirmed via `javap` decompilation of the resolved jars
before writing the code.

### `FallbackProviderProperties`: a separate config section, not two more `available-models` entries
`rag.available-models` is echoed verbatim by `ModelsController` into the
`web-ui` dropdown — anything added there becomes immediately, manually
selectable with zero gating. Putting the fallback providers there would let a
user silently pick a public-cloud model like any local one, skipping the
confirmation gate (Phase 2c) that keeps this feature honest about company
content never reaching a public API without explicit, per-call consent.
Instead, `rag.fallback-providers.{openai,gemini}.{api-key,model}` is its own
`@ConfigurationProperties` record. Both `api-key` fields default to blank
(never `:?required`) so the `test` profile — no real key, must never call a
real API — keeps working exactly as before this phase.

### `LlmGateway`: two more methods, two more independent breakers
`callOpenAiFallback`/`callGeminiFallback`, same `Supplier<T>` shape as the
existing `callOllama`/`callLmStudio` (ADR 0009/0017). Each gets its own named
Resilience4j `@CircuitBreaker`/`@Retry` instance (`openai-fallback`,
`gemini-fallback`) — independent from `ollama`, `lmstudio`, **and from each
other**: an invalid/exhausted OpenAI key must never trip Gemini's breaker (or
vice versa), matching the same reasoning ADR 0017 already established for
keeping the two local providers apart.

One deliberate departure from the `ollama`/`lmstudio` retry config: their
`retry-exceptions` list is just `ResourceAccessException` (connection
refused, since local servers are either up or down). The two new fallback
instances add `HttpServerErrorException` but **not** `HttpClientErrorException`
— a real cloud API's 4xx (401 invalid key, 429 quota exhausted, both
confirmed for real against this project's own keys) won't resolve in a 500ms
retry window; retrying it just burns another call against the same rate
limit. Only genuinely transient failures (no response at all, or the
provider's own 5xx) are retry-worthy for a cloud dependency.

## Consequences

### Verified for real — Gemini fully works, OpenAI authenticates but has zero credits
Direct `curl` testing against both real APIs before writing any code (not
assumed from either provider's documentation):

- **Gemini**: `GET /v1beta/models` → 200, real model list.
  `POST /v1beta/models/gemini-flash-latest:generateContent` (both via
  `?key=` and via the `x-goog-api-key` header) → **200, real generated
  text**. The key is valid and working end-to-end.
- **OpenAI**: `POST /v1/chat/completions` with `gpt-4o-mini` → **HTTP 429**,
  `{"error":{"type":"insufficient_quota","code":"credit_balance_exhausted"}}`.
  This is a billing/quota error, not an auth failure — the key itself
  authenticates successfully. **The account has zero credits**, a real,
  external blocker requiring the user's own action (adding a payment method
  on the OpenAI console) that this session explicitly did not and should not
  perform itself (a financial transaction). Documented honestly rather than
  silently glossed over, matching the pattern already used for the Netlify
  auto-deploy gap (ADR 0033) and Render's `X-Forwarded-For` non-decision.

### `FallbackProviderLiveTest`: a real, opt-in, non-CI test (3 tests, all real API calls)
Gated the same way as `ChunkingStrategyBenchmark`/`RagQualityBenchmark`
(Phase 8):
```
OPENAI_API_KEY=... GEMINI_API_KEY=... ./mvnw test -pl rag-service \
    -Dtest=FallbackProviderLiveTest -DliveFallback=true \
    -Dsurefire.failIfNoSpecifiedTests=false
```
Run for real against both live APIs while writing this ADR — all 3 passed:

1. `geminiFallbackReturnsARealAnswer` — a real `callGeminiFallback` call
   returns real, non-blank generated text.
2. `openAiFallbackAuthenticatesRegardlessOfCreditBalance` — written to keep
   passing in both states (zero credits today, credits added later): it only
   fails on a genuine auth/wiring signal (OpenAI's `invalid_api_key` error
   code, or an HTTP 401 in the message), not on a quota error. Confirmed for
   real that Spring AI's `OpenAiApi` wraps every non-2xx response in its own
   `org.springframework.ai.retry.NonTransientAiException`/`TransientAiException`
   before Resilience4j's `Supplier` ever sees it — a raw
   `HttpClientErrorException` never surfaces, so the auth-vs-quota signal has
   to come from the OpenAI error body's own `code` field, still present
   verbatim inside the wrapped exception's message. With today's real zero
   credits, the call throws with `credit_balance_exhausted` — recognized as
   an acceptable, non-auth failure, so the test passes.
3. `openAiFailuresDoNotTripGeminiCircuitBreaker` — the "done when" isolation
   criterion. Drives the `openai-fallback` breaker into `OPEN`/`HALF_OPEN` via
   6 real, currently-failing OpenAI calls (`sliding-window-size: 10`,
   `minimum-number-of-calls: 5`, `failure-rate-threshold: 50`), then asserts
   `gemini-fallback`'s breaker is still `CLOSED` **and** makes one more real
   Gemini call that succeeds — proof, not inference, that one provider's
   real failures never touch the other's breaker.

### Regression-free
`./mvnw -pl rag-service -am test` — 59 tests, 0 failures, 0 errors, including
`ChatQueryIT` (9) and `RagQueryServiceTest` (22) unchanged: the new
`@EnableConfigurationProperties`/bean additions to `ChatClientConfig` and the
new `rag.fallback-providers.*`/`resilience4j.*.instances.{openai,gemini}
-fallback` blocks in `application.yml` don't disturb Spring context startup
under the `test` profile's blank-key defaults.

### Real, tracked, user-actionable gap: OpenAI has zero credits
The wiring and code are verified correct (auth succeeds), but full
end-to-end OpenAI generation cannot be confirmed working right now — that
requires the user to add a payment method/credits on the OpenAI console
themselves. Tracked as a pending item, not silently marked done.

### Scope: 2a only, not the rest of Phase 2
This phase wires the two providers and proves they're independently callable
and independently circuit-broken. It does **not** implement: fallback-trigger
detection (2b — when local retrieval is judged insufficient), the
confirmation gate or non-grounded response contract (2c), the `web-ui`
dialog/provenance badge (2d), or Anthropic wiring (2e — deliberately deferred
until the user generates that key). None of `LlmGateway.callOpenAiFallback`/
`callGeminiFallback` are called from any real request path yet; they exist
and are proven callable, awaiting Phase 2b/2c's trigger and consent logic.
