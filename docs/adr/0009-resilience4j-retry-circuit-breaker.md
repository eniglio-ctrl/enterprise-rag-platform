# ADR 0009: Retry + circuit breaker around every Ollama call

## Status
Accepted

## Context
Groundedness (ADR 0008) already doubled the number of Ollama calls a single request
can make; hybrid search re-ranking (planned next) adds another. Every one of these
calls can fail the same way — Ollama is down, restarting, or unreachable — and today
that failure surfaces as a raw, unhandled exception turned into a generic 500 by
`GlobalExceptionHandler`. The right point to add resilience is before more calls stack
up, not after.

## Decision
- Added `resilience4j-spring-boot3` + `spring-boot-starter-aop` to both services, with
  a shared `resilience4j-bom` import in the root `pom.xml`.
- **Every** Ollama-dependent call is annotated with `@CircuitBreaker(name = "ollama")`
  and `@Retry(name = "ollama")`: the two chat calls in `rag-service`
  (`RagQueryService.answer()`/`diagram()`/`checkGroundedness()`, all funneled through
  one call site) and the retrieval call (`similaritySearch` also embeds the query via
  Ollama, so it's in scope too), plus `vectorStore.add(...)` in `ingestion-service`
  (embeds every chunk before inserting).
- **[achado]** Resilience4j's annotations only take effect on calls made *through* the
  Spring proxy. Annotating a method and then calling it via `this.method(...)` from
  another method in the *same* class (self-invocation) silently does nothing — no
  error, the annotation is just ignored, which is a well-known Spring AOP trap (the
  same one `@Transactional` has). `RagQueryService` and `DocumentIngestionService`
  both call `chatClient`/`vectorStore` inline from several methods, so annotating
  those methods directly would have shipped a resilience feature that quietly never
  ran. Fixed by extracting two small gateway beans instead —
  `rag-service/.../gateway/LlmGateway.java` (takes a `Supplier<String>` so each call
  site's own prompt-building code is unchanged) and
  `.../gateway/VectorStoreGateway.java` in both services — injected into the existing
  services and called through the proxy.
- One shared circuit breaker/retry instance named `"ollama"` per service, not separate
  ones per call type. All these calls fail for the same underlying reason (the local
  Ollama container being down), so they should share failure-rate accounting and trip
  together.
- Failures are mapped to a clean `503` instead of a raw `500`: both
  `GlobalExceptionHandler`s gained handlers for
  `io.github.resilience4j.circuitbreaker.CallNotPermittedException` (circuit open —
  fails immediately without even attempting the call) and
  `org.springframework.web.client.ResourceAccessException` (retries exhausted against
  an unreachable/refused connection — the last attempt's exception, since no fallback
  method was configured). `ResourceAccessException` is safe to map generically to "AI
  service unavailable" here because it is, today, only ever thrown by the Ollama HTTP
  calls — there is no other outbound HTTP client in either service.
- Retry is only useful against *fast*-failing errors (connection refused when the
  Ollama container is actually stopped) — the read-timeout from ADR (Fase 0) already
  bounds how long a *hanging* call can take, and retrying a call that takes up to 180s
  to fail would be far worse than just failing once. This is why retry uses a short,
  fixed backoff rather than trying to cover both failure modes.

## Consequences
- Same duplication this decision explicitly relies on avoiding (`GlobalExceptionHandler`
  copy-pasted per service) still exists for now — this ADR adds two more near-identical
  `@ExceptionHandler` methods to both copies. This is exactly the duplication Fase 1.5
  (`platform-common` extraction, already flagged in ADR 0007) is queued up to resolve;
  not worth doing early just for these two methods.
- **Verification status**: `./mvnw clean verify` is green on both modules (all existing
  and previously-added tests pass unmodified — Resilience4j annotations are no-ops
  outside a Spring AOP context, so plain unit tests that construct `LlmGateway`/
  `VectorStoreGateway` directly exercise the same code path as before). The manual
  end-to-end check this ADR's "pronto quando" calls for — stop the `ollama` container
  mid-request, confirm a clean `503` — was **not completed** in this session: rebuilding
  the Docker images hit a sustained network failure reaching both Maven Central and
  Docker Hub from inside the build sandbox (confirmed independent of any code or
  Dockerfile change — the exact same `dependency:go-offline` step failed differently on
  each of five consecutive attempts, and a plain `docker pull`-equivalent request to
  Docker Hub's auth endpoint also failed with a reset connection). This is an
  environment/network issue, not a defect in this change. **Next session**: rebuild
  (`docker compose up -d --build ingestion-service rag-service`), then
  `docker stop enterprise-rag-platform-ollama-1` mid-request and confirm the `503`
  before considering this ADR's implementation fully verified.
