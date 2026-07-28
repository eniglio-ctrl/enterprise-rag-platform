# ADR 0020: Free public demo deployment (Groq + Mistral AI embeddings + Render + Neon)

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
- **Hosting**: Render (`rag-service`) + Neon (serverless Postgres with pgvector) +
  Netlify (`web-ui`, static hosting — see the update section on why not Render for
  this piece too).

**Live**: https://web-ui-rag.netlify.app (frontend),
https://ag-service-demo.onrender.com (API) — both free tier, verified working
end-to-end through the real browser UI, not just curl.

## Decision

- **Embeddings use Mistral AI's free-tier `mistral-embed` API (1024 dimensions), not
  Ollama.** Groq doesn't serve embeddings itself, so a second provider is needed
  regardless. Spring AI 1.0.0 GA has a first-party `spring-ai-starter-model-mistral-ai`
  starter covering both chat and embeddings via simple API-key auth — reused only for
  embeddings here (see the Update section below for why a local ONNX model was tried
  first and reverted).
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
  dimension is baked into the `CREATE TABLE` DDL (`vector(1024)` for `mistral-embed`
  vs. the regular schema's `vector(768)` for `nomic-embed-text`), and Flyway
  migrations are static SQL, not environment-parametrized. `spring.flyway.locations`
  points the "demo" profile at its own copy instead.
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
  service.** `ingestion-service` also gets the Mistral/demo-profile treatment
  (matching embedding dimensions matter for what gets read later), but only to be run
  once against the Neon connection string to index a handful of documents — it is
  never itself deployed publicly, keeping the lean-scope decision honest.
- **`MistralAiChatAutoConfiguration` and `MistralAiModerationAutoConfiguration` are
  excluded in every profile, in both services**, alongside whichever
  `EmbeddingModel` auto-configuration a given profile doesn't want. Mistral is only
  ever used here for its embedding model — Groq already covers cloud chat — and
  both of those auto-configurations were confirmed, by a real failing build, to
  fail fast at boot without an api-key set rather than quietly backing off, so they
  can't be left inactive-by-omission.

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

## Update: the local ONNX embedding model didn't fit Render's free tier; switched to
## Mistral AI's embedding API instead

The original decision above (a local `sentence-transformers/all-MiniLM-L6-v2` model
via `spring-ai-starter-model-transformers`) was implemented, verified locally
end-to-end, and deployed — twice. Both real deploys to Render's free tier (512MB
RAM) were killed by the platform itself, not a catchable JVM exception:

- **First attempt**: `java.lang.OutOfMemoryError: Java heap space`, thrown from
  inside `TransformersEmbeddingModel.afterPropertiesSet()` while reading the
  downloaded ONNX model file into a byte array. Tuning `-Xmx` alone didn't fix
  it — the failure mode just changed shape.
- **Second attempt**, after adding `-XX:+UseSerialGC`, a capped `-Xmx350m`,
  `-XX:MaxMetaspaceSize=96m`, `-Xss512k`, and a reduced Tomcat thread pool: the
  **container itself** was OOM-killed by Render (`Out of memory (used over
  512Mi)`) at the exact same step — downloading and caching `model.onnx` (~90MB).
  JVM heap flags only constrain the JVM's own heap; they don't change the total
  resident memory Tomcat + Spring + the JVM baseline + the ONNX/DJL native runtime
  + that download need all at once, and that total genuinely exceeds 512MB.

Given the user's explicit constraint — free, not "cheaply paid" — the fix wasn't
more JVM tuning (already exhausted, with real evidence from two separate deploys)
or upgrading Render's tier (contradicts the constraint), but replacing the
in-process model with **Mistral AI's `mistral-embed`** (1024 dimensions), one more
free-tier external API rather than a memory-heavy local runtime. `rag-service` and
`ingestion-service` both swap `spring-ai-starter-model-transformers` for
`spring-ai-starter-model-mistral-ai`; `db/migration-demo`'s `vector(384)` became
`vector(1024)`; the demo Neon database's `vector_store` table was dropped and
reseeded from scratch (a demo dataset, not real user data — no migration path
needed, a clean recreate was the right call).

**A second real bug found and fixed during this switch**: a Spring Boot subtlety
already suspected but not yet proven — a profile-specific `spring.autoconfigure
.exclude` list *replaces* the base `application.yml`'s list entirely, it doesn't
merge with it. The first version of `application-demo.yml` in both services listed
only the one exclude each profile actually meant to change (e.g. swapping
`OllamaEmbeddingAutoConfiguration` in), which silently un-excluded
`MistralAiChatAutoConfiguration` — Spring AI then had two competing, unqualified
`ChatModel` beans at boot (`NoUniqueBeanDefinitionException`), confirmed by a real
failing local boot before this was understood, not predicted in advance. Fixed by
spelling out the full exclude list again in every profile-specific file, not just
the one entry that changes — a real, easy-to-repeat trap worth flagging for any
future profile added to either service.

Verified for real, end-to-end, after both fixes: the demo Neon database reseeded
with `mistral-embed` vectors, `rag-service` (demo profile) retrieving them and
answering via Groq — a genuine cloud-to-cloud-to-cloud round trip (Neon, Mistral,
Groq), not simulated.

## Update: `OllamaChatModel` isn't lazy — a real deploy failure, and a real gap in
## how this was tested locally

Redeploying to Render after the Mistral switch above failed at boot with
`Connection refused` to `http://localhost:11434/api/tags`, even though the "demo"
profile's `rag.available-models` never lists an Ollama entry and nothing in the
request path calls it. Root cause: `OllamaChatAutoConfiguration`'s `ollamaChatModel`
bean isn't a passive wrapper the way this ADR had assumed — Spring AI's
`OllamaChatModel` constructor calls `initializeModel()`, which calls
`OllamaModelManager.pullModel()`, which calls `isModelAvailable()` — a real `GET
/api/tags` — *before* the object even finishes constructing, to decide whether the
configured model needs pulling. With no Ollama reachable in this deployment, that
call throws, and Spring's `UnsatisfiedDependencyException` chain takes down the
entire application context: `ChatController` → `RagQueryService` → `LlmRerankService`
→ the `ollamaChatClient` bean → `ollamaChatModel` → the failed HTTP call, five beans
deep, none of them ever actually used by anything the "demo" profile does.

**Why local testing never caught this**: every local run of the "demo" profile up to
this point (seeding the database, the first end-to-end retrieval test, the Mistral
switch's own verification) happened with the real local `docker-compose` stack's
Ollama container still running and reachable at `localhost:11434` — the default
`OLLAMA_BASE_URL`. The eager check quietly succeeded every time, so the bug was
invisible until the first deploy to an environment where Ollama genuinely doesn't
exist. Fixed properly the second time: rebuilt and reran locally with
`OLLAMA_BASE_URL` pointed at a deliberately unreachable host
(`http://ollama-does-not-exist.invalid:11434`) to actually reproduce Render's
condition, not just its absence-of-config.

**Fix**: `spring.ai.ollama.init.pull-model-strategy: never` added to
`application-demo.yml`, which skips that eager availability check/pull entirely —
the (unused) `ollamaChatModel` bean now constructs successfully regardless of
whether Ollama is reachable, and only the fixed-Groq-only `available-models` list
ever gets used at request time anyway. Verified twice: booting cleanly with a
genuinely unreachable `OLLAMA_BASE_URL`, and a real question still answering
correctly end-to-end (Neon retrieval, Mistral embeddings, Groq generation) under
that same condition.

## Update: `web-ui` ended up on Netlify, not Render — and two more real gotchas
## found deploying it

Creating a *second* Render web service (for `web-ui`) prompted for a credit card
(a common anti-abuse verification step on free tiers, with a refunded $1 hold —
not a real charge, but still card data this project won't collect). Given the
"free, not just cheap" constraint, `web-ui` moved to **Netlify** instead — genuinely
free, no card, and since `web-ui` is plain static files (`index.html`/`style.css`/
`app.js`), it doesn't need Render's Docker build pipeline at all.

- **The Docker-based `envsubst` config injection (`config.js.template` +
  `docker-entrypoint.sh`) doesn't apply to plain static hosting** — Netlify just
  serves files as-is, no container, no entrypoint script. A real, committed
  `web-ui/config.js` (not a template) was added specifically for this path, with
  `demoMode: true` and the real deployed `rag-service` URL hardcoded. The
  Docker-based deployment is unaffected: its `Dockerfile` only ever `COPY`s
  `config.js.template`, never this file, so the entrypoint's generated version
  still wins there.
- **A real, initially confusing access-control gotcha**: the new Netlify site
  returned `401` with a "Login Redirect" page even after the team's
  "Default project visibility" was set to Public. That default, per Netlify's own
  UI copy, only applies to *new* projects created after the setting changes —
  "existing projects keep their current visibility." The already-created site
  needed its own, separate, per-project visibility toggle (on the project's own
  overview page, not the team settings page) switched to Public.
- **CORS**: `rag-service`'s `web-ui.allowed-origin` (`WEB_UI_ORIGIN` env var)
  defaults to `http://localhost:3000` — correct for local dev, but it would have
  silently blocked every request from `https://web-ui-rag.netlify.app` in the
  browser (curl doesn't enforce CORS, so this wouldn't have shown up in any of the
  curl-based verification done so far). Caught before the user hit it, not after:
  `WEB_UI_ORIGIN` set to the real Netlify URL on `rag-service`'s Render environment
  before ever testing the deployed frontend in a real browser.

Verified for real in a browser, not just via curl this time: the deployed
`web-ui` on Netlify calling the deployed `rag-service` on Render, model dropdown
populated, a real question asked and answered correctly with citations, no login
screen, demo banner visible.
