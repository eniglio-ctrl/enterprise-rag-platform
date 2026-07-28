# ADR 0017: Per-request chat model picker (Ollama models + LM Studio)

## Status
Accepted

## Context
The original roadmap's Fase 7a called for switching LLM provider via a Spring
profile — pick one provider at deploy time, restart to change it. The user instead
wanted a runtime picker in the UI, the same shape as this conversation's own model
selector: a dropdown fed by a configured list, chosen per request, no restart. Two
scope decisions were made explicitly before implementing:
1. Local models only for v1 — Ollama models already pulled, plus LM Studio's local
   OpenAI-compatible server (the user has it installed, per earlier session notes) —
   not a cloud provider. No API key management, no cost, fully testable locally.
2. The picker only applies to `rag-service`'s single-turn `/api/v1/ask` (and its
   `/chat`/`/diagrams` siblings) — not `chat-service`'s multi-turn conversations,
   which aren't wired into `web-ui` yet anyway.

## Decision
- **Config-driven list, `rag.available-models`**: each entry is `{id, label,
  provider}`. The **first entry is the default** — no separate "which one is default"
  property to drift out of sync with it. `GET /api/v1/models` exposes the list as-is
  so the frontend never hardcodes a model name; `ChatRequest.model` lets a caller
  override the default for that one request. An unknown/blank id falls back to the
  default silently (`RagQueryService.resolveModel`) rather than rejecting the
  request — a stale dropdown value shouldn't break the question itself.
- **LM Studio via `spring-ai-starter-model-openai`, not a bespoke client**: LM
  Studio's local server speaks the OpenAI-compatible chat API, so the same starter
  that would talk to real OpenAI works unmodified — only `spring.ai.openai.base-url`
  points at `localhost:1234` (LM Studio's default) instead of `api.openai.com`.
  `spring.ai.openai.api-key` gets a harmless placeholder (`lm-studio`): the client
  requires a non-blank value, but LM Studio's server doesn't check it.
- **Two `ChatClient` beans, not one** (`ChatClientConfig`): with both the Ollama and
  OpenAI starters on the classpath, Spring AI's own auto-configured
  `ChatClient.Builder` backs off entirely once more than one `ChatModel` bean exists
  — it only activates for a single unambiguous candidate. This is the exact risk the
  original plan's own "achado" flagged for Fase 7a (`NoUniqueBeanDefinitionException`
  from Ollama+OpenAI coexisting), but that risk assumed picking one provider via
  `spring.autoconfigure.exclude`; this design needs **both active simultaneously**,
  selectable per request, so exclusion isn't an option. Each `ChatClient` bean is
  built explicitly from a `@Qualifier`-named `ChatModel` dependency
  (`"ollamaChatModel"`/`"openAiChatModel"`, Spring AI's own autoconfigured bean
  names) instead of relying on the ambiguous auto-configured builder.
- **`OpenAiEmbeddingAutoConfiguration` explicitly excluded**
  (`spring.autoconfigure.exclude`): adding the OpenAI starter would otherwise also
  autoconfigure an `OpenAiEmbeddingModel` bean, competing with Ollama's for anything
  that autowires `EmbeddingModel` by type — `PgVectorStore`'s own similarity search
  does this internally. Embeddings stay Ollama-only; LM Studio is never selected for
  embedding, only chat generation.
- **`LlmGateway` gets two methods, `callOllama`/`callLmStudio`, not one parametrized
  by provider name**: Resilience4j's `@CircuitBreaker`/`@Retry` instance name is a
  compile-time annotation attribute, so a single method can't pick the breaker at
  runtime. Routing both providers through the same named breaker would also be
  wrong on its own terms — an LM Studio outage tripping Ollama's circuit would be one
  unrelated dependency's failure blocking a different, healthy one. `RagQueryService`
  calls whichever method matches the resolved model's `provider`, always through the
  external `LlmGateway` bean (never `this.*`) so Resilience4j's proxy-based
  interception actually applies (ADR 0009's self-invocation gotcha, same rule as
  every other gateway in this codebase).
- **Rerank stays Ollama-only regardless of the selected chat model**
  (`LlmRerankService`, `@Qualifier("ollama")`): its own structured-output reliability
  concerns (ADR 0012's fallback-to-RRF behavior) are already handled separately and
  don't need multiplying by provider choice.
- **`ChatResponse`/`DiagramResponse`/`AskResponse` gained a `model` field** echoing
  back which model actually answered — small addition once the resolution logic
  already existed, useful transparency for a picker UI.
- **web-ui**: a `<select>` populated from `GET /api/v1/models` (behind the same JWT
  as everything else — no special-casing), defaulting to whichever entry the backend
  marks as default, sent as `model` in the `/api/v1/ask` body.

## Consequences

- **Real, non-hypothetical wiring bug found and fixed via `./mvnw verify`, not
  guessed**: `ChatQueryIT`'s `@MockitoBean private ChatModel chatModel` failed at
  context startup once two `ChatModel` beans existed
  (`IllegalStateException: Unable to select a bean to override`) — fixed with
  `@MockitoBean(name = "ollamaChatModel")`. A second, related bug followed
  immediately: `ChatClientConfig`'s bean methods originally depended on the
  *concrete* types `OllamaChatModel`/`OpenAiChatModel`, which a `ChatModel`-typed
  mock can't satisfy (`UnsatisfiedDependencyException`) — fixed by depending on the
  `ChatModel` interface with `@Qualifier("ollamaChatModel")`/`@Qualifier("openAiChatModel")`
  instead, which both resolves the real beans correctly at runtime and stays mockable
  in tests. Confirmed via a real `ApplicationContext` boot (`ChatQueryIT`), not just
  unit tests mocking the collaborators away.
- **Verified end to end against the real stack**: rebuilt `rag-service`, brought the
  full `docker-compose` stack back up, confirmed `GET /api/v1/models` returns the
  three configured entries, and exercised `/api/v1/ask` with an explicit `model`
  override against a real, already-pulled Ollama model.
- **LM Studio is opt-in and gracefully absent by default**: with no `LMSTUDIO_*` env
  vars set and LM Studio not running, `rag-service` still boots fine (the OpenAI
  client only needs a syntactically valid base-url/api-key at startup, not a
  reachable server) — only a request that explicitly selects the LM Studio entry
  hits a connection failure, handled the same way any other unreachable dependency
  is (Resilience4j retry, then a clear 503 once its circuit opens).
- **Real, unrelated bug found while investigating a user report** ("diagrams aren't
  working"): not a code bug — `SELECT metadata->>'tenantId', count(*) FROM
  vector_store GROUP BY 1` showed 19 chunks under tenant `default` (uploaded before
  ADR 0016's auth-service existed) and only 1 chunk under the user's real,
  now-authenticated tenant. Retrieval correctly found nothing relevant, which is
  exactly the isolation ADR 0016 built — the fix is re-uploading test documents under
  the real account being tested with, not a code change.
- **Known gaps, not addressed here**: no UI affordance yet showing which model
  actually answered (the `model` field is in the API response, unused by the
  frontend); `chat-service`'s conversations don't get a model picker (out of scope,
  matches the confirmed decision above); LM Studio's own model can't be hot-swapped
  mid-request the way Ollama's can — whatever's loaded in LM Studio's UI is what
  answers, regardless of the configured `id` label.
