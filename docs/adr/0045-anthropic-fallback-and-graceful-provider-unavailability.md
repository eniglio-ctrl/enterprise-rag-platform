# ADR 0045: Anthropic fallback wiring, and graceful handling of a missing key/no credits across every fallback provider

## Status
Accepted

## Context
`docs/ROADMAP.md` item #12 (Multi-LLM Phase 2e) had been deliberately deferred:
unlike OpenAI/Gemini's keys, obtained ahead of time (ADR 0036), no
`ANTHROPIC_API_KEY` was going to be generated until this item actually
started. The user's own instruction when starting this item was explicit and
broader than "just wire Anthropic": *"trate o fallback e ignore caso não
exista a chave de login ou não haja créditos para consulta, isso em todas as
conexões com as LLMs"* — handle it gracefully whenever a key is missing or an
account has no credits, across every LLM connection, not only the new one.

That second half mattered because a real, pre-existing gap was found while
scoping this: `RagQueryService.answerViaPublicLlmFallback` had no handling
at all for an auth failure or a quota/credits error from OpenAI or Gemini —
either would propagate as a raw, uncaught exception all the way to
`GlobalExceptionHandlerSupport`'s generic `Exception.class` handler, coming
back to the caller as an unhelpful `500 "An unexpected error occurred"`.
`FallbackProviderLiveTest` had already carefully distinguished this exact
failure mode for OpenAI's real zero-credits account in a *test* (ADR 0036) —
but nothing in the actual runtime path did anything with that distinction.
Adding a third provider with the same class of problem, without fixing the
underlying gap, would have tripled it instead of closing it.

## Decision

### Anthropic: a real Spring AI starter, unlike Gemini
Spring AI 1.0.0 ships `spring-ai-starter-model-anthropic` (confirmed by
resolving it from Maven Central before adding it — Gemini has no equivalent,
which is why `GeminiClient` is a hand-rolled `RestClient`). Built the same
way as the OpenAI-fallback `ChatClient`
(`ChatClientConfig.anthropicFallbackChatClient`): a manually-constructed
`AnthropicApi`/`AnthropicChatModel`, not relying on the starter's own
autoconfiguration, because the fallback's api-key/model live under
`rag.fallback-providers.anthropic.*` (keeping this provider out of
`rag.available-models` for the same reason documented on
`FallbackProviderProperties` since ADR 0036 — every entry there is
immediately user-selectable in `web-ui`'s dropdown, which would silently
skip the confirmation gate ADR 0038 exists to enforce), never
`spring.ai.anthropic.*`. `AnthropicChatAutoConfiguration` is excluded
(`application.yml`/`application-demo.yml`, same treatment as Mistral's) —
its own `@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue =
"anthropic", matchIfMissing = true)` would otherwise activate it anyway
(confirmed by inspecting the annotation's actual bytecode before assuming),
building a second, entirely unconfigured `anthropicChatModel` bean nothing
uses. `AnthropicChatOptions.maxTokens(1024)` is set explicitly — Anthropic's
Messages API rejects a request without it outright, unlike OpenAI/Ollama
where it's optional.

`LlmGateway.callAnthropicFallback` (`@CircuitBreaker`/`@Retry`, instance
name `"anthropic-fallback"`) mirrors the two existing fallback methods
exactly — no bulkhead, same reasoning as ADR 0043: this protects a cloud
provider's own outages/quota, not a local server's limited concurrent
capacity.

### The real fix: a three-way dispatch with a pre-flight key check and a narrow rescue catch
`answerViaPublicLlmFallback` changed from a boolean (`openai`/`gemini`) to a
proper three-way dispatch (`normalizeFallbackProvider` /
`apiKeyFor` / `modelIdFor` / `callFallbackProvider`, all switching on the
same normalized provider string), with two new behaviors applied uniformly
to all three providers:

1. **Pre-flight**: before ever attempting a call, check whether the
   resolved provider's API key is blank. If so, skip the call entirely and
   return a graceful response immediately — no network round trip wasted on
   a provider that was never going to work.
2. **Rescue catch**: the actual call is wrapped in a `try`/`catch` that
   re-throws `CallNotPermittedException`, `BulkheadFullException`, and
   `ResourceAccessException` completely unchanged — these are genuine
   infrastructure signals (circuit open, bulkhead full, no response after
   retries) with their own correct, already-tested global handling (ADR
   0017/0043), and must never be silently swallowed into an always-200
   response. Anything else — `NonTransientAiException`, a raw
   `HttpClientErrorException`, or any other `RuntimeException` the
   provider's own client throws for a business reason (invalid key, no
   credits, rate-limited) — is caught, logged as a warning, and turned into
   the same graceful response as the pre-flight case.

Both paths return a `ChatResponse` with a new `source` value,
`"public-llm-unavailable"` (added alongside the existing `"local"`/
`"public-llm"`, `ChatResponse`'s own javadoc updated), and a plain-language
`answer` naming the provider and stating it's unavailable — never a raw
exception message, and never silently mislabeled as if a real answer had
been generated.

### Why a broad `catch (RuntimeException e)`, not a provider-specific error-code parser
`FallbackProviderLiveTest`'s existing OpenAI test already shows real proof
that precise error-code detection here is fragile: Spring AI's own
`OpenAiApi` wraps every non-2xx response in
`NonTransientAiException`/`TransientAiException` before this code ever sees
it, so the only reliable signal left is grep-ing the exception's own message
string for a provider-specific substring (`"invalid_api_key"`, `"401 -"`).
Three providers means three different wrapping exception types and three
different error-body shapes to keep in sync by hand, for a runtime code path
whose only actual job is "stop this from crashing the request" — not to
distinguish auth failures from quota failures for the *end user* (that
distinction matters for `FallbackProviderLiveTest`'s own test assertions,
which must fail loudly on a real auth bug; it doesn't matter for the person
who just asked a question and needs a clear answer either way). A broad
catch, scoped narrowly by explicitly re-throwing the three infrastructure
exception types first, gets the behavior every real caller needs without
that fragility.

## Consequences

### Verified for real against the actual running stack, in the actual state this project is really in
No `ANTHROPIC_API_KEY` was generated for this — deliberately, matching the
user's own framing that missing keys/credits are an expected condition to
handle, not a blocker to work around by acquiring one. Registered a fresh,
empty tenant on the real running stack, triggered the fallback offer for
real (empty retrieval), then confirmed it three ways:
- **Anthropic** (`fallbackProvider: "anthropic"`, genuinely no key
  configured): `HTTP 200`, `source: "public-llm-unavailable"`, a clear
  message naming Anthropic.
- **OpenAI** (`fallbackProvider: "openai"`, the real zero-credits account
  from ADR 0036 — unchanged): previously would have surfaced as a raw `500`;
  now the same `HTTP 200`/`"public-llm-unavailable"` shape, naming OpenAI.
  This is the concrete, real proof that the pre-existing gap is actually
  closed, not just Anthropic's new path working.
- **Gemini** (default, no provider specified): unaffected — a real answer
  about the Eiffel Tower, `source: "public-llm"`, exactly as before this
  change.

### Automated tests: the graceful path, the rescue path, and the re-throw path all covered
`RagQueryServiceTest` gained four tests:
`confirmedFallbackCallsAnthropicWhenExplicitlyRequested` (the happy path, an
Anthropic model mock returning a real answer);
`confirmedFallbackSkipsTheCallAndAnswersGracefullyWhenTheProviderHasNoApiKeyConfigured`
(a blank Anthropic key, asserting the underlying `ChatModel` mock is never
even invoked); `confirmedFallbackAnswersGracefullyWhenTheProviderRejectsTheRequest`
(OpenAI's mock throwing a 429/insufficient_quota-shaped exception, asserting
a graceful response, not a propagated exception);
`confirmedFallbackReThrowsACircuitBreakerOpenSignalInsteadOfSwallowingIt`
(a `CallNotPermittedException` thrown from the mock, asserting it still
propagates out of the service — proving the rescue catch's explicit
re-throw list actually works, not just that it was written). rag-service:
71 → 76 tests, all green; full `./mvnw clean verify` green across all 5
modules.

### `FallbackProviderLiveTest` extended, not run live for Anthropic
Added `anthropicFallbackAuthenticatesRegardlessOfCreditBalance`, gated by
its own `Assumptions.assumeTrue(ANTHROPIC_API_KEY != null)` separate from
the class-level check, so its absence never skips the OpenAI/Gemini tests
that do have real keys. Never executed this session — there is no
`ANTHROPIC_API_KEY` to run it with, exactly the state this ADR's whole
graceful-handling change exists to make safe to leave as-is.
