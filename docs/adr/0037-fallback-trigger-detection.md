# ADR 0037: Fallback trigger detection (Multi-LLM Phase 2b)

## Status
Accepted

## Context
`docs/MULTI-LLM-ORCHESTRATOR-ROADMAP.md` Phase 2b. Depends on Phase 2a (ADR
0036, both fallback providers wired and independently callable). Defines,
structurally, when the local answer path counts as "failed or insufficient"
and the OpenAI/Gemini fallback should be offered — deliberately **not** via
keyword/string matching on the generated answer text, the exact mistake ADR
0024 already replaced once for `/api/v1/ask` routing.

## Decision

### `FallbackTriggerEvaluator`
A new, small, framework-independent `@Component`
(`rag-service/.../service/FallbackTriggerEvaluator.java`) with one public
method, `shouldOfferFallback(String provider, List<Document> retrieved)`,
true when either of two independent conditions holds:

1. **Local infra failure** — the resolved model's own Resilience4j circuit
   breaker (`ollama` or `lmstudio`, ADR 0009) is already `OPEN`. Checked via
   `CircuitBreakerRegistry.circuitBreaker(name).getState()` **before**
   attempting generation, not by catching a failure afterward — the point is
   to skip one more doomed call against a provider already known to be down,
   not to add a second retry mechanism.
2. **Content insufficiency** — `retrieved.isEmpty()`.

### A real correction to the roadmap's own text
The plan said to reuse "the existing score already on every citation"
against a threshold. Inspecting `HybridSearchService.fuseWithRrf` before
writing any code showed that score is the **post-RRF-fusion** score — the
sum of `1/(60+rank)` across whichever of the vector/full-text legs a
document appeared in — not the cosine-similarity scale the roadmap's
phrasing implied. With `RRF_K = 60`, even a document ranked #1 in *both*
legs scores only `2/61 ≈ 0.033`; reusing the existing `rag.similarity
-threshold: 0.5` against this scale would make every real answer look
"insufficient."

No second, arbitrarily-calibrated RRF-scale threshold was introduced to fix
this. The vector leg already discards anything below `rag.similarity
-threshold` **before** fusion (`HybridSearchService.search`, ADR 0012) — so
an empty `retrieved` list is already the correct, meaningful signal for
"nothing relevant was found." Only a full-text-only match with a very weak
`ts_rank` could theoretically survive fusion with a low RRF score while
`retrieved` stays non-empty; calibrating a second threshold for that narrow
case isn't justified by this phase's own "done when" criterion and was left
alone rather than guessed at.

## Consequences

### Verified for real, via a fast unit test — no Spring context needed
`FallbackTriggerEvaluatorTest` (5 tests) uses a real
`CircuitBreakerRegistry.ofDefaults()` (not a mock) so
`CircuitBreaker.transitionToOpenState()` drives genuine state transitions,
the same way production code observes them:

- Circuit breaker `OPEN` for `ollama` → fallback offered.
- Circuit breaker `OPEN` for `lmstudio` → fallback offered, **and**
  `ollama`'s own check is unaffected — confirms the same breaker-isolation
  principle ADR 0009/0017/0036 already established for local and fallback
  providers extends to this decision too.
- Empty `retrieved` list → fallback offered.
- **Negative case, per the roadmap's own "done when" wording**: a normal
  successful answer (closed breaker, non-empty `retrieved`) does **not**
  offer the fallback.
- A freshly-created, `CLOSED` circuit breaker alone isn't enough to trigger
  it either — confirms the check reads real state, not just "does a breaker
  exist for this name."

### Scope: detection only, not wired into any response yet
`FallbackTriggerEvaluator` is not yet called from `RagQueryService.doAnswer`
or any other request path — it exists and is proven correct in isolation,
matching this phase's own scope. Wiring it into the actual two-step
confirm/execute flow, and shaping the response differently when it fires
(`fallbackAvailable: true`, no citations, distinct non-grounded contract),
is Phase 2c's job, not this one's.

### Regression-free
`./mvnw -pl rag-service -am test` — 64 tests, 0 failures, 0 errors. The new
class and test add to the suite without touching any existing behavior.

## Update (2026-08-05): the deferred narrow case above happened for real, fixed with a second-stage trigger instead of a second RRF threshold

The "left alone rather than guessed at" case from the Decision section above
— a full-text-only match surviving fusion while `retrieved` stays non-empty
— hit for real: asking for a Java code example matched documents about SSO/
JWT and an unrelated personal PDF, purely because "código" (a common
Portuguese word, "code") appears in both, with nothing to do with the actual
question. `retrieved` came back non-empty, so `FallbackTriggerEvaluator`
correctly did **not** fire (it isn't wrong — the case it doesn't cover is
exactly the one this ADR already named), the local model generated an
answer from irrelevant context, and it said so honestly ("não encontrei
informações suficientes...") — but that text was returned as if it were a
normal, successful answer, with no fallback offered.

Fixed without calibrating the RRF-scale threshold this ADR already rejected
guessing at: `RagQueryService.doAnswer` now always runs the existing
groundedness check (ADR 0008's `checkGroundedness`, previously opt-in via
`grounded: true`) after generation, and treats a `NOT_SUPPORTED` verdict the
same as `FallbackTriggerEvaluator` firing — offering the fallback instead of
returning the answer. This is a genuinely better fix than a second
threshold would have been: it judges the actual generated answer against
its context (an LLM verdict, the same structural-not-textual principle this
ADR already committed to, just checking the *answer's relationship to its
context* instead of the user-facing *answer text* ADR 0024 already rejected
matching on), rather than trying to guess a fusion-score cutoff that
inherently can't distinguish "weakly-ranked but genuinely relevant" from
"weakly-ranked and coincidental."

**Cost, accepted deliberately**: every question now costs a second Ollama
round trip (the groundedness check), not just ones where a caller opts in —
real added latency on every request in exchange for not silently returning
ungrounded-and-unhelpful answers as if they'd succeeded. `FallbackTriggerEvaluator`
itself is unchanged; this is a second, later trigger point in `doAnswer`,
not a modification to the pre-generation one. Full detail on the response
contract this reuses is unchanged from ADR 0038.
