# ADR 0038: Confirmation gate + non-grounded response contract (Multi-LLM Phase 2c)

## Status
Accepted, with one known, real, undecided-on-purpose limitation (see Consequences).

## Context
`docs/MULTI-LLM-ORCHESTRATOR-ROADMAP.md` Phase 2c. Depends on Phase 2a (ADR 0036,
both fallback providers wired) and Phase 2b (ADR 0037, `FallbackTriggerEvaluator`
proven correct in isolation but not yet called from any real response). This phase
wires the trigger into `/api/v1/ask`'s actual answer path and defines the two-step
API contract: a first request only ever *offers* the fallback, a second, explicitly
confirmed request is what actually calls a public LLM.

## Decision

### `ChatRequest` gains `useFallback`/`fallbackProvider`
Both nullable, both opt-in. `useFallback: true` is the caller's explicit
confirmation — without it, `FallbackTriggerEvaluator` firing never results in a
public-API call, no matter what. `fallbackProvider` picks `"openai"` or
`"gemini"`; anything else (including `null`) defaults to `"gemini"` — the only one
of the two verified working end-to-end as of ADR 0036 (OpenAI's key authenticates
but the account has zero credits).

### `ChatResponse`/`AskResponse` gain `fallbackAvailable`/`source`
- `fallbackAvailable: true` only on the *offer* response — no LLM, local or
  public, was called. `answer` is still populated with an explanatory message in
  that case (an older client that doesn't know about this field still shows
  something sensible), but `source` stays `null`: nothing was actually generated
  yet, so there's no provenance to report.
- `source` is always `"local"` on every normal, grounded answer, and
  `"public-llm"` only when a confirmed fallback call actually happened — never
  left for the caller to infer from the shape of the rest of the response
  (`citations` being empty could otherwise mean either "grounded, but no
  citations" or "not grounded at all"). `web-ui` (Phase 2d) reads this field
  directly instead of guessing.

### `RagQueryService.doAnswer`: the trigger check moved before generation
```java
if (imageDescription == null && fallbackTriggerEvaluator.shouldOfferFallback(resolvedModel.provider(), retrieved)) {
    if (useFallback) {
        return answerViaPublicLlmFallback(question, fallbackProvider);
    }
    return new ChatResponse(offerMessage, List.of(), null, null, true, null);
}
```
An attached image always skips the fallback check entirely — same precedent as
the pre-existing "image alone can answer" short-circuit this replaces: its
vision-description call already just succeeded through the local provider
moments earlier, and the roadmap's Phase 2b/2c text never asked for an
image-aware fallback path.

**A real, previously-existing bug this phase incidentally fixes**: before this
change, an open local circuit breaker had no proactive check anywhere in
`doAnswer` — a request would reach `llmGateway.callOllama(...)`, Resilience4j's
`@CircuitBreaker` would throw `CallNotPermittedException` immediately, and that
exception would propagate all the way up as an unhandled 500. `FallbackTrigger
Evaluator`'s pre-generation check (ADR 0037) now catches this case *before* the
doomed call, turning what used to be an opaque 500 into a `fallbackAvailable:
true` response with an explanation. Confirmed via a dedicated unit test
(`offersFallbackWithoutCallingTheLocalModelWhenItsCircuitBreakerIsOpen`) that the
local `ChatClient` is never even invoked once the breaker is open.

### `answerViaPublicLlmFallback`: only the raw question, ever
```java
private ChatResponse answerViaPublicLlmFallback(String question, String fallbackProvider) {
    boolean useOpenAi = "openai".equalsIgnoreCase(fallbackProvider);
    String answer = useOpenAi
            ? llmGateway.callOpenAiFallback(() -> openAiFallbackChatClient.prompt(question).call().content())
            : llmGateway.callGeminiFallback(() -> geminiClient.generateContent(question));
    ...
    return new ChatResponse(answer, List.of(), null, modelId, null, "public-llm");
}
```
No `context`, no `system` template, no `.tools(documentLookupTool)` — this is the
one boundary that keeps this tenant's document content (including anything
`DocumentLookupTool`, Phase 9's `@Tool`, could otherwise fetch mid-call) from ever
reaching a public API through this path. `citations` is always empty and
`groundedness` always `null`: neither concept applies to an answer that was never
grounded in this tenant's documents in the first place.

## Consequences

### Verified for real against the running local stack, exactly per this phase's "done when"
A real `curl` sequence, not just unit tests:

1. `POST /api/v1/ask` with a question guaranteed to miss the freshly-registered
   test tenant's empty corpus → `{"fallbackAvailable": true, "source": null,
   "citations": [], "model": null}`, real 200, no LLM called.
2. The same question, `"useFallback": true` (no `fallbackProvider`, defaulting to
   Gemini) → a real, genuinely generated answer from `gemini-flash-latest`
   (correctly noting it has no internal document to check, then answering from
   general knowledge), `"source": "public-llm"`, `"citations": []`.
3. A real document uploaded for that same tenant, then a normal question about
   it (no `useFallback`) → retrieval found the right chunk (RRF score
   `0.0328` — empirically confirms ADR 0037's math: rank 1 in both legs sums to
   `2/61`), `"source": "local"`, `"fallbackAvailable": null` — the normal path's
   provenance tagging works correctly even though `llama3.1`'s own answer
   quality on a single terse chunk was mediocre (a real, pre-existing model
   behavior unrelated to this phase, not something 2b/2c ever claimed to fix —
   the trigger is about whether *retrieval* found something, not whether the
   LLM's eventual answer is good).

### A real, honest, NOT-fixed-in-this-phase limitation: a failing fallback call surfaces as a generic 500
Confirmed for real: `"fallbackProvider": "openai"` against the same test tenant
returned `HTTP 500 {"error":"Internal Server Error","message":"An unexpected
error occurred"}` — OpenAI's real zero-credits state (ADR 0036) throws, and
`doAnswer` has no try/catch around `answerViaPublicLlmFallback`, so
`GlobalExceptionHandlerSupport`'s generic `Exception.class` handler catches it.
This is consistent with how every other unhandled provider failure already
behaves in this codebase (no per-call try/catch exists for the local
`ollama`/`lmstudio` generation call either), so it's not a regression — but it
does mean a real, distinguishable "the fallback provider itself failed" response
shape doesn't exist yet. Deliberately not fixed here: designing that error
contract belongs with Phase 2d (the `web-ui` needs to show *something* sensible
for it), not bolted on ad hoc in this phase.

### Regression-free, and the whole build still verified
`./mvnw clean verify` — green across all 5 modules. `RagQueryServiceTest` grew
from 22 to 25 tests: the pre-existing "nothing retrieved" test now also asserts
`fallbackAvailable: true`; three new tests cover the open-breaker-skips-generation
case and both fallback providers being called with only the raw question
(verified via `ArgumentCaptor`, not just return-value inspection); the
pre-existing "answers using retrieved documents" test now also asserts the
normal path's `source: "local"` tagging. `ChatQueryIT` (9 tests, JSON-path
assertions only) unaffected by the new response fields.

### `docker-compose.yml`/`.env.example` gained the two real env vars
`OPENAI_FALLBACK_API_KEY`/`GEMINI_FALLBACK_API_KEY`, blank-safe defaults —
without this, the confirmed-fallback path could never actually be exercised
against the running local stack at all (Phase 2a's own wiring never touched
`docker-compose.yml`, since it was backend-only and opt-in-test-only at the
time).

### Scope: the API contract only, not `chat-service` or `web-ui`
Both `/api/v1/ask` and `/api/v1/chat` (same `ChatRequest`) now support the
confirm flow; `/api/v1/diagrams` and the image-attachment multipart endpoint do
not (diagrams were never in scope; images always skip the fallback entirely, per
the design decision above). `chat-service`'s own conversation-memory layer
(ADR 0013) was not touched — it only ever calls `rag-service`'s
`/api/v1/retrieve`, never `/ask`/`/chat`, so this phase doesn't reach it. `web-ui`
still has no confirmation dialog or provenance badge — that is Phase 2d,
unstarted.
