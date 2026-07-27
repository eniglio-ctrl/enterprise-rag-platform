# ADR 0013: chat-service — conversation memory on top of rag-service's retrieval

## Status
Accepted

## Context
Every RAG interaction so far has been single-turn: one question in, one answer out,
no memory of what was asked before. A real assistant needs multi-turn conversations
("and what about X?" referring to the previous answer). The plan's own note — Spring
AI's chat-memory JDBC artifacts (`spring-ai-starter-model-chat-memory-repository-jdbc`)
are resolved at GA (1.0.0), not milestone — meant this was buildable without any
version-compatibility guesswork.

**The one real design question the plan left open**: it says chat-service "calls
rag-service via HTTP, doesn't duplicate retrieval" and separately says to use
`MessageChatMemoryAdvisor`'s `maxMessages` window — but that advisor only does
anything useful when attached to a `ChatClient`'s own prompt pipeline. Those two
statements together only make sense if chat-service has its *own* `ChatClient`
(and thus its own Ollama connection) for generation, while treating rag-service purely
as a retrieval provider. That's the design below — not spelled out explicitly in the
plan, worked out from what would actually make `MessageChatMemoryAdvisor` meaningful.

## Decision
- **New module** `chat-service` (mirrors `rag-service`'s structure, depends on
  `platform-common`), port 8083.
- **rag-service gained one new endpoint**: `POST /api/v1/retrieve` — runs
  `HybridSearchService` (ADR 0012) and returns chunks, with **no generation call**.
  Calling the existing `/api/v1/chat` instead would have meant paying for (and
  discarding) a full Ollama generation on every chat-service turn, on top of the one
  chat-service itself needs to make — doubling latency for no reason. The response
  carries full chunk text (`RetrievedChunk`, new in `platform-common`), not the
  200-char `Citation.snippet()` used everywhere else — a caller using this as real
  generation context needs the whole chunk, not a display-sized preview.
- **chat-service's own `ChatClient` + `MessageChatMemoryAdvisor`** (backed by
  `MessageWindowChatMemory`, `chat.max-messages` configurable) does the actual
  conversation-aware generation: retrieve chunks from rag-service → build a system
  prompt from them (same shape as `RagQueryService`'s own) → call the local model with
  the memory advisor attached for this `conversationId`. Retrieval logic itself is
  never reimplemented — only rag-service ever talks to pgvector or runs full-text SQL.
- **`RagServiceGateway`** wraps the HTTP call to rag-service with its own
  `@CircuitBreaker`/`@Retry` instance (`"rag-service"`, separate from `"ollama"`,
  ADR 0009) — this fails for a different reason (rag-service down/slow) than an Ollama
  outage, so it shouldn't share failure-rate accounting with it.
- **Conversation ownership**: a small `conversations` table (`tenant_id`, `user_id`,
  ADR 0007) that Spring AI's own chat-memory schema knows nothing about — it only
  tracks `conversation_id` + messages. Every request checks the conversation belongs to
  the caller's tenant before touching memory or generating anything; unowned/unknown
  IDs return `404`, not `403` — no need to reveal a conversation exists for a
  different tenant.
- **Own logical Postgres schema (`chat`)**: `spring.flyway.schemas: chat` +
  `?currentSchema=chat` on the datasource URL, so this service's tables — and its own
  `flyway_schema_history` — never collide with `ingestion-service`'s `public` schema.
- **Endpoints**: `POST /api/v1/conversations`, `POST /api/v1/conversations/{id}/messages`,
  `GET /api/v1/conversations/{id}/messages` — exactly as planned.

## Consequences

- **Real, non-hypothetical schema captured, not guessed**: the `SPRING_AI_CHAT_MEMORY`
  table DDL in `V1__spring_ai_chat_memory.sql` was extracted directly from
  `spring-ai-model-chat-memory-repository-jdbc-1.0.0.jar`'s own bundled
  `schema-postgresql.sql`, the same discipline ADR 0011 used for the vector store —
  `spring.ai.chat.memory.repository.jdbc.initialize-schema` defaults to `EMBEDDED`
  (only auto-initializes for embedded databases), so it was never going to race Flyway
  the way PgVectorStore's `initialize-schema: true` did; still set explicitly to
  `never` for clarity.
- **Two real bugs found bringing this up, neither obvious from reading Spring AI's
  own docs**:
  1. Context startup failed with `Failed to load driver class org.postgresql.Driver`.
     `ingestion-service`/`rag-service` get the JDBC driver *transitively* through
     `spring-ai-starter-vector-store-pgvector` — chat-service has no reason to depend
     on that starter (it never touches pgvector), so the driver was simply never on
     its classpath. Fixed with an explicit `org.postgresql:postgresql` dependency.
  2. The integration test's dynamic datasource URL was built as
     `postgres.getJdbcUrl() + "?currentSchema=chat"` — but Testcontainers'
     `getJdbcUrl()` *already* returns `...?loggerLevel=OFF`, so this produced a
     malformed URL with two `?` characters. The PostgreSQL driver silently accepted it
     and just dropped `currentSchema` entirely rather than erroring — every query
     against the `chat` schema failed with "relation does not exist", while Flyway's
     migrations (which target the schema explicitly via its own config, not the
     connection's search_path) succeeded, making the symptom look like a Flyway/schema
     bug rather than a URL string bug. Fixed with `&` instead of a second `?`.
- **Every other Dockerfile needed updating, again**: adding `chat-service` as a fourth
  reactor module meant `ingestion-service/Dockerfile` and `rag-service/Dockerfile` both
  needed `COPY chat-service/pom.xml` added too — the same reactor-validation
  requirement ADR 0010 already documented for `platform-common`, now confirmed to
  apply to *every* module added to the root POM, not just that one.
- **Verified for real, not just via the test suite**: rebuilt all Docker images,
  brought up the full 6-service stack, created a real conversation and sent a real
  message through the actual `llama3.1` — got back a correct, cited answer
  (`~110s`, consistent with previous single-turn latency, since this is still one
  Ollama generation call plus the fast `/api/v1/retrieve` round trip, no extra LLM
  call beyond what a single-turn request already pays).
- **Real retrieval-quality limitation found on the very next turn, worth being honest
  about rather than glossing over**: asking a natural follow-up in the same
  conversation ("e o SAGA, como funciona?", after a first question about Circuit
  Breaker) came back with "não encontrei informações suficientes" even though the
  indexed content does cover SAGA (confirmed earlier this session, ADR 0012's own
  testing). The reason: `RagServiceGateway.retrieve()` sends only the *current*
  message's raw text to rag-service — a short, pronoun-heavy follow-up doesn't carry
  enough of its own signal for hybrid search to rank the right document highly,
  because the conversation history that would disambiguate it never reaches the
  retrieval step at all, only the generation step (via `MessageChatMemoryAdvisor`).
  The model's response was still *correct behavior* given what it was handed — it
  said it didn't have the information rather than inventing an answer — but the fix
  belongs in retrieval, not generation: rewrite/expand the retrieval query using
  recent conversation turns before calling `/api/v1/retrieve`, not just the current
  message in isolation. Left as a known, documented limitation rather than an
  in-scope fix for this phase — query rewriting is its own well-scoped follow-up, not
  something to bolt on without deciding its own design (a cheap heuristic vs. another
  LLM call, which would add yet another Ollama round trip on top of retrieval +
  generation).
