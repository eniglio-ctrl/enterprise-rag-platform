# ADR 0056: Real-dollar cost metering for public-LLM fallback calls

## Status
Accepted

## Context
Prometheus + Grafana already track real usage (documents ingested, answers
generated, etc.), but no metric anywhere converts an LLM call into an actual
cost figure — confirmed directly in the code: no `cost`/`tokenUsage`
tracking exists in `rag-service` or `chat-service` beyond prose comments
about latency "cost," not money. Every model reachable through
`LlmGateway`'s local providers (Ollama/LM Studio) is genuinely free to run;
only the three public-LLM fallback providers (OpenAI/Gemini/Anthropic, ADR
0038/0045) have a real per-token dollar cost, and that call path is already
fully isolated to `RagQueryService.callFallbackProvider` — the ~7 other
`callLlm(...)` call sites in that file (answering, groundedness checking,
diagrams, summaries, FAQs) always resolve through `clientFor(resolvedModel)`,
which only ever returns the Ollama or LM Studio client.

## Decision

### Only the fallback call path is instrumented
`callFallbackProvider`'s three branches (OpenAI, Anthropic, Gemini) are the
only place a real dollar cost is ever incurred in this codebase. Every other
`callLlm` call site is untouched — no new instrumentation, no behavior
change, and critically, no vision-model-style symmetry attempt at recording
a "known $0.00" sample for local calls: they simply never reach a cost
metric, so their true cost (zero) is never even computed.

### `.call().content()` → `.call().chatResponse()` for OpenAI/Anthropic
Spring AI's `ChatResponse` (not this app's own DTO of the same name) exposes
token usage via `getMetadata().getUsage()`, discarded before this phase by
always using `.content()`. Both Spring-AI-backed branches now capture the
full `ChatResponse` and extract both the answer text and the usage.

### A real finding: Spring AI's `Usage` defaults to zero, never `null`
Confirmed by reading `spring-ai-model-1.0.0`'s own source
(`ChatResponseMetadata`'s default `usage` field is `new EmptyUsage()`, whose
`getPromptTokens()`/`getCompletionTokens()` both return `0`, never `null`).
A null-check for "did this provider report usage" would therefore never
fire — the actual signal is `promptTokens + completionTokens == 0`, since a
real, successful chat completion never has literally zero tokens on both
sides. Getting this backwards would have silently treated every "usage
unknown" call as a real $0.00 sample, quietly understating actual spend
forever.

### `GeminiClient` gains real usage extraction
`GeminiClient` is a raw REST client (Spring AI 1.0.0 has no plain-API-key
Gemini integration, ADR 0036), so it never had a `Usage` object to begin
with. Its `GeminiResponse` DTO only ever deserialized `candidates`, silently
dropping the `usageMetadata` field the Generative Language API's real
`generateContent` response already returns. Without extending it, Gemini —
the default fallback provider — would have its cost permanently
unmeasurable. `generateContent` now returns a new `GenerationResult(String
text, int promptTokens, int completionTokens)` record instead of a plain
`String`; its one call site (`RagQueryService`) was updated accordingly.

### New `CostMeteringService`
A small, focused `@Component` (same shape as `ScannedPageVisionFallbackService`,
ADR 0055) with one method,
`recordFallbackCall(provider, model, promptTokens, completionTokens)`:
- If usage is unavailable (`promptTokens + completionTokens == 0`): logs a
  warning and increments `llm.cost.usage_unavailable` (tagged `provider`) —
  visible as its own distinct signal, never silently folded into "free."
- Otherwise: computes `cost = promptTokens/1e6 * inputPrice +
  completionTokens/1e6 * outputPrice` using the provider's configured price,
  records it to a `DistributionSummary` named `llm.cost.usd` (tags
  `provider`, `model` — this project's first use of `DistributionSummary`;
  Counter/Timer were the only Micrometer types used before this), and
  increments a `llm.tokens.consumed` `Counter` twice (tagged `token_type` =
  `prompt`/`completion`) for a complementary tokens-over-time view.

### Pricing lives on the existing provider config records, illustratively
`FallbackProviderProperties.OpenAi`/`Gemini`/`Anthropic` each gain
`inputPricePerMillionTokens`/`outputPricePerMillionTokens` (flat fields, no
new nested `Pricing` type). Defaults in `application.yml` are illustrative
public list prices for each provider's own default model (`gpt-4o-mini`,
`gemini-flash-latest`, `claude-haiku-4-5`) — not a maintained, authoritative
price table. An operator running a different model, or with a negotiated
rate, must update these to their real contracted price. Gemini defaults to
`0.0`/`0.0` deliberately: this project's real key (ADR 0036) is on Gemini's
genuinely free tier, so `$0.00` is the correct number, not a placeholder
waiting to be filled in.

### Grafana: one new row on the existing dashboard
`observability/grafana/dashboards/rag-platform-overview.json` is
file-provisioned (`allowUiUpdates: false`, reloads every 30s) — no
`docker-compose.yml`/provisioning change needed. New "Custo (LLM fallback)"
row with two panels: estimated USD spend by provider
(`sum(rate(llm_cost_usd_sum[5m])) by (provider)`) and tokens consumed by
provider/type (`sum(rate(llm_tokens_consumed_total[5m])) by (provider,
token_type)`) — naming confirmed against this project's own existing panels
(`Counter` → `_total`, `Timer`/`DistributionSummary` → `_sum`/`_count`).

## Consequences

### Verified
- Full `./mvnw -pl rag-service test` suite green (102 tests; the one
  pre-existing failure, `RagQualityBenchmark`'s real-Ollama quality-score
  threshold, is unrelated to this phase and already a documented finding
  from before it — a real-model quality benchmark, not a cost-metering
  regression).
- New `CostMeteringServiceTest`: cost computed correctly per provider from
  its own configured price, Gemini's free-tier pricing correctly yields
  `$0.00` while still counting real tokens, and the "usage unavailable"
  path is exercised explicitly (no false `$0.00` sample recorded).
- `RagQueryServiceTest` extended: a fallback call with known usage records
  the exact expected cost and token counts in an inspectable
  `SimpleMeterRegistry`; the existing Gemini fallback test (whose stub
  never populated usage) now doubles as proof the "unavailable" path is
  exercised for real, not just synthetically; a new test confirms a
  Spring AI `ChatResponse` with an empty generations list (a real edge
  case surfaced only by reading `ChatResponse.getResult()`'s own source,
  which returns `null` rather than throwing) still falls back to the
  existing graceful "provider unavailable" response, not an unhandled
  exception.

### Local answers never touch cost metrics
Because instrumentation lives only inside `callFallbackProvider`, a
question answered entirely locally (the common case) never calls
`CostMeteringService` at all — `llm.cost.usd`/`llm.tokens.consumed` simply
have no samples for that request, which is the correct behavior, not an
oversight.
