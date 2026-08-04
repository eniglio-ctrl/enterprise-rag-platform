# ADR 0043: Operational resilience hardening — bulkheads, readiness/liveness split, timeout audit

## Status
Accepted

## Context
`docs/ROADMAP.md` item #17, full account in
`docs/PRODUCTION-READINESS-ROADMAP.md` Phase 4. Three gaps confirmed directly
before writing any code, not assumed:

1. No `Semaphore`/Resilience4j `@Bulkhead` anywhere bounded how many
   simultaneous requests could hit Ollama or the Whisper server — a real
   deployment with concurrent users could exhaust the local model server's
   own capacity with no backpressure at this project's own layer.
2. Every Kubernetes manifest's `readinessProbe` and `livenessProbe` hit the
   exact same `/actuator/health` endpoint on the same schedule, defeating
   the actual point of the distinction — a slow-but-recovering Postgres
   should fail readiness (stop receiving traffic) without also failing
   liveness (which restarts the pod).
3. Timeouts existed (ADR 0009) but had never been audited across every
   outbound call. The audit found two clients with **no timeout at all**:
   `GeminiClient` and the OpenAI-fallback `ChatClient`, plus one local-model
   client (`ingestion-service`'s `ChatClientConfig.restClientBuilder`) that
   had also been missing one this whole time, unlike its Whisper sibling.

## Decision

### Bulkheads: `SEMAPHORE` type, `max-wait-duration: 0`, same instance names already in use
Every local-model gateway that already carried `@CircuitBreaker`/`@Retry`
(ADR 0009) got `@Bulkhead` added alongside them, using the exact same
Resilience4j instance name — no new naming scheme:

- rag-service: `LlmGateway.callOllama`/`callLmStudio` (`"ollama"`/`"lmstudio"`)
- ingestion-service: `VectorStoreGateway.add`, `VisionGateway.call`
  (`"ollama"`), `AudioTranscriptionGateway.transcribe` (`"whisper"`)
- chat-service: a **new** `LlmGateway` (see below)

`max-wait-duration: 0` is deliberate, not an oversight: a caller must be
rejected the instant the limit is hit, not queued — queueing would just move
the pile-up from the model server into this application's own request
threads, defeating the entire point. Cloud fallback providers
(`openai-fallback`/`gemini-fallback`) deliberately got **no** bulkhead: this
is specifically about protecting a local, single-process model server with
genuinely limited capacity from this application's own traffic; a cloud
provider scales independently, and already has its own circuit breaker
guarding against its outages/quota exhaustion.

`BulkheadFullException` is handled in
`platform-common`'s `GlobalExceptionHandlerSupport` (shared by every
service's `@RestControllerAdvice`), returning a 503 with a distinct message
from the existing `CallNotPermittedException` (circuit open) handler — same
HTTP status from the caller's point of view, but logged distinctly so the
two failure modes stay distinguishable in practice. Required adding
`resilience4j-bulkhead` as an explicit `platform-common` dependency (it only
had `resilience4j-circuitbreaker` before).

### A real, previously-unflagged gap found while scoping this: chat-service's own Ollama call had no resilience wrapping at all
`ConversationService`'s `chatClient.prompt()...call()` — a direct local-model
call — had no `@CircuitBreaker`/`@Retry`/`@Bulkhead` before this phase,
unlike rag-service's and ingestion-service's equivalent calls. Fixed by
adding a new `chat-service`-local `LlmGateway` (same
wrap-in-a-separate-`@Component`-taking-a-`Supplier` pattern as rag-service's,
required because Resilience4j's annotations only intercept calls made
through the Spring proxy — self-invocation would silently do nothing) and a
new `"ollama"` instance in `chat-service`'s own `resilience4j.*` config
(previously only `"rag-service"` existed there, for the HTTP call to
rag-service — never for this service's own direct model call).

### Readiness/liveness: Spring Boot's own health groups, not a custom endpoint
`management.health.probes.enabled: true` plus
`management.endpoint.health.group.readiness.include: readinessState, db` /
`.liveness.include: livenessState` in all four services'
`application.yml`. `readinessState`/`livenessState` are Spring's own
`ApplicationAvailability` signals; adding `db` to readiness only (not
liveness) is the one deliberate choice here — Postgres being slow should
hold a pod out of the load balancer, not restart it. Kubernetes manifests
updated to point `readinessProbe` at `/actuator/health/readiness` and
`livenessProbe` at `/actuator/health/liveness` instead of both hitting the
same aggregate `/actuator/health`. `startupProbe` (where present) was left
untouched — its job (absorbing a slow first-boot Ollama model pull) is
unrelated to this distinction.

### Timeout audit: three real gaps closed
- `GeminiClient` had zero timeout configuration — fixed with a new shared
  `rag.fallback-providers.connect-timeout`/`read-timeout` pair
  (`FallbackProviderProperties`), `.detect()` (not `.simple()`: this is a
  plain JSON POST, no multipart, so no h2c-corrupts-the-body risk the
  Ollama/Whisper clients are pinned against).
- The OpenAI-fallback `ChatClient` (`ChatClientConfig.openAiFallbackChatClient`)
  had no timeout either — `OpenAiApi.Builder.restClientBuilder(...)` now
  passes a timeout-configured `RestClient.Builder`, same shared
  `rag.fallback-providers.*` properties.
- `ingestion-service`'s `ChatClientConfig.restClientBuilder` bean (backing
  the vision-description Ollama calls) had **no** timeout settings at all —
  the one local-model client in the whole codebase missing one. Fixed with a
  new `ingestion.ollama.connect-timeout`/`read-timeout` pair, same values
  and same reasoning as rag-service's `rag.ollama.*` (ADR 0009).

Cloud timeouts (5s connect / 30s read) are deliberately shorter than local
inference's (5s / 180s): a cloud API hanging for as long as local CPU-bound
inference legitimately can is itself a signal something is wrong, not normal
latency.

## Consequences

### Bulkhead verified twice: a deterministic automated test, and a real load test against the actual running stack
`ChatQueryIT` gained `bulkheadRejectsAConcurrentOllamaCallWhenTheLimitIsAlreadySaturated`:
the class's own `@DynamicPropertySource` overrides `resilience4j.bulkhead
.instances.ollama.max-concurrent-calls` down to `1` for this test class only
(harmless to every other test in it — none of them issue concurrent
requests), a mocked `ChatModel` blocks on a `CountDownLatch` to hold the
bulkhead's one permit, and a second concurrent request is asserted to come
back `503` in milliseconds, not after waiting for the first to finish.
Passed against a real Postgres/pgvector Testcontainer.

Then, against the real running local stack with the real `llama3.1` model on
real Ollama (`bulkhead.instances.ollama.max-concurrent-calls: 4` in
production config): fired 8 concurrent real `/api/v1/ask` requests. The
first 4 to arrive occupied the bulkhead's 4 permits; the remaining 4 were
rejected in ~155ms with the bulkhead's own distinct message ("sob alta
demanda"), confirmed via `rag-service`'s own logs
(`GlobalExceptionHandlerSupport`'s bulkhead-specific log line). **An
unplanned, genuinely real finding**: this machine's local Ollama couldn't
reliably complete all 4 truly-concurrent `llama3.1` inferences — enough of
them failed for real that the `"ollama"` circuit breaker itself tripped
`OPEN` mid-test, causing the 4 permit-holding requests to *also* eventually
come back `503`, but with the circuit-breaker's own distinct message, not
the bulkhead's. The circuit breaker recovered on its own
(`wait-duration-in-open-state: 30s`) — a follow-up request afterward
answered normally in ~11s. This is not a defect in this phase's own change:
the bulkhead did exactly what it was supposed to (reject excess callers
instantly), and the circuit breaker did exactly what *it* was supposed to
(protect against a dependency failing for real) — it's evidence the two
mechanisms compose correctly under real, unscripted failure, not just in a
controlled unit test. Whether `4` is the right concurrency ceiling for a
given machine's Ollama is a hardware/workload question this bulkhead makes
tunable via one property, not something this phase tried to find the
"correct" number for.

### Readiness/liveness split verified for real, including the failure case
Rebuilt and restarted all four services. Confirmed via `curl` that
`/actuator/health/readiness` includes `db` and `/actuator/health/liveness`
does not, on all four ports. Then simulated the exact scenario this item's
"done when" describes: `docker compose pause postgres`. `/actuator/health
/liveness` stayed `200 UP` throughout, answering in 5ms. `/actuator/health
/readiness` hung until HikariCP's own connection-timeout elapsed (~30s),
then returned a real `503 DOWN` with
`CannotGetJdbcConnectionException` in the body — the exact "readiness fails
without touching liveness" behavior the roadmap asked for, demonstrated
against a real paused Postgres, not simulated with `pg_sleep` as originally
suggested (pausing the whole container was simpler and at least as
convincing: it produces genuine connection failures, not just query
latency). `docker compose unpause postgres` restored `200 UP` immediately.
This was a local-actuator-level verification, not a real Kubernetes cluster
cycle (no `kind` cluster was spun up this session) — but it verifies the
exact same signal (the two health-group paths) a real kubelet would read
from these same probes.

### Timeout fixes verified by compilation and the existing test suite, not independently re-tested against live cloud APIs
`RagQueryServiceTest`'s `FallbackProviderProperties` fixture updated for the
two new fields; full `./mvnw clean verify` green across all 5 modules. The
Gemini/OpenAI-fallback timeout wiring was not independently re-verified
against the real Gemini/OpenAI APIs this phase (that would mean spending
real fallback-provider quota purely to prove a timeout value applies,
without a real slow/hung response to trigger it) — the existing
`FallbackProviderLiveTest`'s already-passing live checks (Gemini working,
OpenAI zero-credits, both pre-existing findings from ADR 0036) are
unaffected by this change, confirming the new `RestClient.Builder` wiring
didn't break the request path itself.
