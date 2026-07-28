# ADR 0020: Free public demo deployment (Groq + local ONNX embeddings + Render + Neon)

## Status
Accepted

## Context
Fase 7b of the roadmap asks for a public, zero-cost deployment. The full local stack
depends on Ollama for both chat and embeddings — a real constraint even locally (this
Mac's Docker Desktop VM has been repeatedly OOM-killing `llama-server` under memory
pressure, see the ADR 0018/0019 update sections), and simply not viable on a free
hosting tier at all (multiple GB of resident model weights, no GPU).

Three scope decisions were confirmed with the user before implementing:
- **LLM provider**: Groq — a generous free tier, and its API is OpenAI-compatible, so
  it reuses ADR 0017's existing "openai" provider wiring instead of new code.
- **Scope**: a lean demo (`rag-service` + `web-ui` only, no login, a fixed pre-seeded
  document set) instead of the full four-service stack — running four separate JVMs
  continuously on free tiers is a much harder fit than one.
- **Hosting**: Render (web services) + Neon (serverless Postgres with pgvector).

## Decision

- **Embeddings switch to a local ONNX model (`spring-ai-starter-model-transformers`,
  `sentence-transformers/all-MiniLM-L6-v2`, 384 dimensions), not another cloud API.**
  Groq doesn't serve embeddings, and adding a fourth external account (for embeddings
  alone) was worse than running a small, already-Spring-AI-supported model in-process
  — no network call, no API key, no rate limit to hit mid-demo. Verified the
  dependency and its autoconfiguration exist and resolve cleanly in Spring AI 1.0.0
  GA before committing to this, not assumed.
- **A "demo" Spring profile, not a new deployment-only fork of the codebase.**
  `rag-service` and `ingestion-service` both gain `application-demo.yml`, flipping
  `spring.autoconfigure.exclude` to swap which `EmbeddingModel` auto-configuration
  wins (Transformers instead of Ollama's) — the same mechanism ADR 0017 already
  established for excluding OpenAI's embedding auto-config, applied a second time
  for a second reason.
- **Groq's base-url is `https://api.groq.com/openai`, not `.../openai/v1`** — Spring
  AI's `OpenAiApi` always appends `/v1/chat/completions` itself. Getting this wrong
  produced a real, confirmed `404 Unknown request URL: POST
  /openai/v1/v1/chat/completions`, caught during local testing before any deploy, not
  discovered after.
- **A separate Flyway migration location (`db/migration-demo`) for the demo schema**,
  not a new migration appended to the regular `db/migration` — the embedding column's
  dimension is baked into the `CREATE TABLE` DDL (`vector(384)` vs. the regular
  schema's `vector(768)`), and Flyway migrations are static SQL, not
  environment-parametrized. `spring.flyway.locations` points the "demo" profile at
  its own copy instead.
- **No login, via a demo-only `SecurityFilterChain`, not by disabling security
  annotations piecemeal.** `platform-common` gains `DemoSecurityConfig`
  (`@Profile("demo")`), replacing `ResourceServerSecurityConfig`
  (now `@Profile("!demo")`) — it `permitAll()`s every request and installs a
  synthetic `Jwt` (fixed `tenantId: "demo"`) into the `SecurityContext` via a filter.
  Every controller's existing `@AuthenticationPrincipal Jwt jwt` +
  `JwtClaims.tenantId(jwt)` call sites are completely unchanged; only where that
  principal comes from differs. Confirmed via Spring Security's own debug logging
  that exactly one filter chain is built per profile, not two competing ones.
- **`web-ui` gains a `demoMode` flag** (`window.RAG_PLATFORM_CONFIG.demoMode`,
  read by `app.js`) that skips the login gate entirely and hides the upload panel —
  there's nothing for a login form or an upload button to do against a backend with
  no auth-service and a fixed, pre-seeded document set.
- **Runtime config injection via `envsubst` at container start (`docker-entrypoint.sh`
  generates `config.js` from `config.js.template`)**, not a build-time constant —
  Render only assigns `rag-service`'s public URL after it's created, so `web-ui`'s
  image can't have it baked in at build time. Defaults
  (`DEMO_MODE=false`, `RAG_BASE_URL=http://localhost:8082`) exactly reproduce this
  project's existing local `docker-compose` behavior — verified side-by-side in the
  browser, not assumed unaffected.
- **Seeding the demo database is a one-time local operation, not a deployed
  service.** `ingestion-service` also gets the Transformers/demo-profile treatment
  (matching embedding dimensions matter for what gets read later), but only to be run
  once against the Neon connection string to index a handful of documents — it is
  never itself deployed publicly, keeping the lean-scope decision honest.

## Consequences

- **Verified locally end-to-end before touching any external account**: a throwaway
  `pgvector/pgvector:pg16` container, `ingestion-service` (demo profile) seeding a
  real document into it with the ONNX embedding model, `rag-service` (demo profile)
  retrieving that exact chunk via hybrid search with no `Authorization` header at
  all, and reaching Groq's real chat-completions endpoint (confirmed via the correct
  `401` from an intentionally invalid test key, then the base-url bug fix, before any
  real key was involved). Every piece proven independently before deploying.
- **`./mvnw clean verify` stays green across every module** — the demo profile is
  additive configuration, not a fork; every existing test (including
  `ChatQueryIT`/`DocumentIngestionIT`) runs unaffected under the default profile.
- **A fourth Flyway migration set to maintain going forward**: any future schema
  change to `vector_store` needs a matching migration added under both
  `db/migration` and `db/migration-demo` if it isn't dimension-specific. A real,
  ongoing cost of keeping two schemas, accepted for the sake of a genuinely free demo
  embedding path.
- **Groq's actual model catalog and free-tier limits can change** — `GROQ_MODEL`
  defaults to `llama-3.3-70b-versatile` but is fully overridable via environment
  variable without a redeploy of code, only a config change.
- **This demo has no write path and no persistence beyond the seeded documents** —
  by design (ADR scope decision); a visitor asking about a topic the seeded documents
  don't cover will correctly get "not enough information," the same well-understood
  behavior already documented for the tenant-isolation case (ADR 0007).
