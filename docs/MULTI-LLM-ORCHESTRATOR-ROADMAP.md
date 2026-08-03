# Multi-LLM Orchestrator Roadmap

> This file is the single source of truth for the multi-LLM orchestrator
> initiative — readable on its own, without needing any prior chat session or
> plan file. Update the status marker and "what was done" notes as each phase
> lands. Same convention as
> [`docs/SECURITY-HARDENING-ROADMAP.md`](SECURITY-HARDENING-ROADMAP.md) — see
> that file if this structure looks unfamiliar.
>
> **Scope grew on 2026-07-29** beyond just "pick a model automatically": the
> user pasted a full AI-Engineer-hiring-checklist and asked whether this file
> (plus the security roadmap) already covered it. It didn't, fully — see
> "Gap-check against the AI Engineer checklist" below for exactly what was
> missing and Phases 8-15 for what got added as a result. The filename stays
> as-is (renaming would break existing links from the README and ADR 0025),
> but this is now the living roadmap for "everything that makes this project
> match a senior AI-engineer job description," not just multi-LLM routing.

## Status at a glance

| Phase | What | Status | Blocked by |
|---|---|---|---|
| 0 | "Automático" model selector | ✅ Done — [ADR 0025](adr/0025-auto-model-selection.md) | — |
| 1 | Single LLM + RAG | ✅ Already done (with Ollama, not literally OpenAI) | — |
| 2a | Fallback provider wiring: OpenAI + Gemini | ✅ Done, with one real caveat — [ADR 0036](adr/0036-fallback-provider-wiring-openai-gemini.md) | Gemini fully works; OpenAI's account has zero credits (user action needed) |
| 2b | Fallback trigger detection (local failed / insufficient) | ✅ Done — [ADR 0037](adr/0037-fallback-trigger-detection.md) | — |
| 2c | Confirmation gate + non-grounded response contract | ✅ Done — [ADR 0038](adr/0038-fallback-confirmation-gate-response-contract.md) | — |
| 2d | `web-ui`: confirmation dialog + provenance badge | ⬜ Not started | Depends on 2c |
| 2e | Fallback provider wiring: Anthropic | ⬜ Not started | Same pattern as 2a — do when the Anthropic key is provided |
| 3 | `PlannerAgent` (decides which specialist handles a request) | ⬜ Not started | Phase 2 |
| 4 | `ReflectionAgent` (compares/merges multiple models' answers) | ⬜ Not started | Phase 2/3 |
| 5 | Long-term memory beyond pgvector (Redis) | ⬜ Not started | A concrete "what does Redis add" answer |
| 6 | Tools via MCP | ⬜ Not started | Scope cut to 1-2 concrete tools; benefits from Phase 9 landing first |
| 7 | Observability (LangFuse + OpenTelemetry) | ⬜ Not started | A LangFuse account/hosting decision |
| 8 | RAG quality deep-dive (chunking strategies + formal eval metrics) | ✅ Done — [ADR 0034](adr/0034-rag-quality-chunking-and-evaluation-metrics.md) | — |
| 9 | Native tool/function calling (Spring AI `@Tool`) | ✅ Done — [ADR 0035](adr/0035-native-tool-calling.md) | — |
| 10 | Reframe agents around capability, not just LLM provider | ⬜ Not started | Phase 9 (and Phase 2/3 for real multi-provider value) |
| 11 | Event-driven architecture (Kafka/RabbitMQ, outbox pattern) | ⬜ Not started | A provisioning decision (new infra, no paid key needed) |
| 12 | AWS deployment target (ECS/EKS/Lambda/Bedrock/OpenSearch/...) | ⬜ Not started | An AWS account + explicit real-cost acceptance |
| 13 | Python + LangGraph AI layer | ⬜ Not started | User confirming they want a second language in this portfolio project |
| 14 | Software engineering polish (SonarQube, `docs/architecture.md` refresh) | 🟡 Mostly done — [ADR 0027](adr/0027-sonarcloud-jacoco-code-quality.md) | Waiting on the user to create the SonarCloud project + token |
| 15 | Go-based API Gateway / BFF | ⬜ Not started | A scope decision (routing + JWT pass-through only, or also rate limiting) |

**On "done" claims in this file**: don't trust a ✅ here on faith — re-verify
with `git log`, `ls`, and a fresh `./mvnw clean verify` before relying on a
status claim for anything that matters, same warning
`SECURITY-HARDENING-ROADMAP.md` already gives for the same reason (this
project has been caught overstating status without checking the repo before).

## Verification pattern (repeat for every phase)

1. `./mvnw clean verify` green across every affected module.
2. `docker compose up -d --build <changed-service>` — container comes up
   `healthy`.
3. A real manual test of the affected behavior via `curl`/browser against the
   running stack — not just the automated test suite.
4. An ADR for the phase, written and linked from both `README.md` and this
   file's status table.
5. Commit + push; update this file's status table and the phase's own section
   with a "what actually happened" note.

## Context: why this exists, and corrected premises

The user asked for an "AUTO" option in the model-selection dropdown, and
pasted a much larger vision alongside it: a full multi-LLM orchestrator —
ChatGPT/Claude/Gemini as parallel providers behind a shared `AIProvider`
interface, a `PlannerAgent` deciding which specialist(s) a question needs, a
`ReflectionAgent` comparing and merging their answers, tools reachable via MCP
(GitHub, Jira, Confluence, AWS, Kubernetes, ...), long-term memory via Redis
alongside the existing pgvector store, and observability via LangFuse +
OpenTelemetry.

Before writing this roadmap, every premise behind it was checked against the
actual code (two Explore agents), not assumed:

- **None of this infrastructure exists today.** No Redis, no Kafka, no MCP, no
  LangFuse, no OpenTelemetry/distributed tracing, no Anthropic/Claude, no
  Google/Gemini, and no *direct* OpenAI wiring — the already-present
  `spring-ai-starter-model-openai` dependency is only ever pointed at LM
  Studio's local server or Groq's OpenAI-compatible endpoint (ADR 0017/0020),
  never at `api.openai.com` itself. Every one of these would be genuinely
  new work, not a config change.
- **The user's own "Phase 1 — a single LLM with RAG, via OpenAI" is already
  done** — just with Ollama (local, free) as the model, not literally OpenAI.
  The retrieval/generation pipeline this phase describes is the entire
  existing `rag-service`.
- **`chat-service` has no model-selection mechanism of any kind** — it's
  hardcoded to one Ollama model and only ever calls `rag-service`'s
  retrieval-only endpoint (`/api/v1/retrieve`), never `/ask`/`/chat`/
  `/diagrams`. ADR 0017 explicitly scoped this out already. Any orchestrator/
  planner work that touches conversations starts from zero here, not from
  existing multi-model plumbing.
- **`platform-common` has no Spring AI dependency at all** — a shared
  `AIProvider` interface would be entirely new code, not an extension of
  something existing.
- **`RagQueryService.resolveModel(...)` is the only model-choice decision
  point in the whole codebase**, and until Phase 0 it was purely static
  (blank/unknown request → first configured entry, always). There was no
  dynamic, question-dependent, or load-dependent selection logic anywhere.

Given this, the plan below treats Phases 2-7 as real, separately-scoped
initiatives — each blocked on an explicit decision or resource only the user
can provide (API keys and their cost, whether Redis/Kafka are worth
provisioning, which MCP tools actually matter) — rather than a single
continuous sprint. Nothing past Phase 0 starts without that decision being
made explicitly first, the same discipline
`docs/SECURITY-HARDENING-ROADMAP.md` already established for its own
multi-phase rollout.

## Gap-check against the AI Engineer hiring checklist (2026-07-29)

The user pasted a detailed "what AI Engineer / Backend AI Engineer job
postings actually check for" list and asked whether this file (plus the
security roadmap) already covered it, or needed adjusting so it could be
implemented piece by piece. Checked category by category against both
roadmaps' actual content (not from memory of what they were "supposed" to
cover):

| Checklist area | Already covered? | Where |
|---|---|---|
| LLM fundamentals, prompt engineering, memory, structured output | Mostly yes | Structured output already used (`LlmRerankService`, ADR 0012); memory already exists (`chat-service`, ADR 0013); prompting is used throughout, not a separate phase |
| Tool calling / function calling | **No** | Added — Phase 9 |
| Agents / conversational systems / MCP | Partially | Phases 3/4/6 exist, but framed narrowly as "which LLM provider," not "which specialist capability" — broadened in Phase 10 |
| Providers (OpenAI/Anthropic/Gemini/open source) | Yes, planned | Phase 2 |
| Multi-agent architectures (Supervisor + SQL/RAG/Security-style specialists) | Partially, narrowly | Phases 3/4 only cover "which LLM backend," not "which tool/capability" — Phase 10 addresses this directly |
| RAG chunking strategies (fixed/recursive/semantic/markdown/parent-child) | **No** | Added — Phase 8. Today's pipeline only uses fixed-size `TokenTextSplitter` |
| RAG evaluation metrics (recall, precision, faithfulness, hallucination, context relevance) | **No** | Added — Phase 8. The existing quality benchmark (Fase 7c of the original roadmap) only measures cosine similarity to an expected answer — a rough proxy, not these specific metrics |
| LangChain / LangGraph | **No** | Added — Phase 13. Structurally a Python-ecosystem concern; this project is 100% Java/Spring AI today |
| LangFuse | Yes, planned | Phase 7 |
| Python | **No** | Added — Phase 13 (the single biggest structural gap — a genuinely new service in a new language, not a library swap) |
| Go | **No** (was; now yes) | Originally flagged with no concrete use case. One emerged from a follow-up question about performance/memory: a lightweight API Gateway/BFF at the edge — Go's actual strength (tiny memory footprint, no JIT warm-up, cheap concurrency) against a real, already-experienced constraint this project hit (ADR 0020's OOM kill on a memory-limited free tier) — and it fills the still-unaddressed "API Gateway" microservices pattern from this same checklist. Added — Phase 15. |
| AWS (ECS/EKS/Lambda/API Gateway/SQS/SNS/EventBridge/DynamoDB/S3/Bedrock/OpenSearch/CloudWatch/IAM) | **No** | Added — Phase 12. Today's deployment targets are local `kind` (Kubernetes) and Render/Netlify/Neon (the free public demo) — no AWS target exists |
| Microservices patterns (API Gateway, Circuit Breaker, Retry, Saga, CQRS, Event Sourcing) | Partially | Circuit Breaker/Retry already done (ADR 0009); API Gateway now planned (Phase 15, Go); a real Saga implementation (vs. just being example *content* in the demo's seeded documents), CQRS, and Event Sourcing are still not implemented anywhere and not separately scheduled, since none of them has a concrete driving use case yet in this project; revisit if one emerges |
| Event-driven (Kafka/RabbitMQ, producer/topic/consumer/DLQ/outbox pattern) | **No** | Added — Phase 11. Present in the user's original "Enterprise Knowledge Platform" vision message but hadn't made it into this roadmap until now |
| Software engineering practices (GitHub Actions, SonarQube, Clean Architecture/SOLID/DDD, structured logs, Docker, Kubernetes) | Mostly yes, one gap | CI/tests/structured logs/Docker/K8s all already done; SonarQube is **not** present — added to Phase 14, alongside refreshing `docs/architecture.md` (confirmed stale — it still only documents `ingestion-service`+`rag-service`, predating both `auth-service` and `chat-service` entirely) |
| Supply-chain security (secret scanning, dependency/CVE scanning) | Yes, planned | `docs/SECURITY-HARDENING-ROADMAP.md` Phase 7 (a different roadmap file, security-specific) |
| A dedicated cybersecurity-focused AI project (agents analyzing CVEs/code/logs) | Out of scope here | This is a genuinely separate project idea ("Sentinel AI"), already noted as a future direction, not a phase of *this* platform — intentionally not folded in here |

---

## Phase 0 — "Automático" model selector ✅

**Done.** See [ADR 0025](adr/0025-auto-model-selection.md). Summary: `"auto"`
is a real, first-position sentinel entry in `rag.available-models`
(`provider: auto`), resolved by `RagQueryService.resolveModel(...)` to the
first genuinely callable ("concrete") entry in the list, once per request —
`clientFor`/`callLlm`/`modelOptions` and the response's `model` field never
see the literal string `"auto"`. No `web-ui` code changes were needed — the
dropdown is populated entirely from `GET /api/v1/models`, which already
renders whatever `rag.available-models` contains, first entry pre-selected.

Verified: `./mvnw -pl rag-service -am verify` green (44 tests, including two
new ones asserting the response's `model` field is always the concrete id);
the dropdown shows "Automático (recomendado)" first and pre-selected, both
locally and on the public demo after redeploy; a question asked with no model
chosen returns the concrete model id, never `"auto"`.

## Phase 1 — Single LLM + RAG ✅ (already existed)

**Done**, before this roadmap existed — this is simply what `rag-service`
already is: retrieval from pgvector, hybrid search (ADR 0012), generation via
a configurable local/self-hosted model (ADR 0017). The user's original phase
description named OpenAI specifically; the actual implementation uses Ollama
(and optionally LM Studio) instead — free and local rather than a paid cloud
API, a deliberate choice made earlier in this project (ADR 0003), not an
oversight relative to the pasted vision.

## Phase 2 — Public-LLM fallback for the AUTO selector ⬜

**Redesigned from the original plan, before any code was written** — worth
recording why. The original text above (still visible in git history)
imagined cloud providers as just more entries in the same dropdown,
selectable like Ollama or LM Studio. A real conversation with the user
produced a materially different, better-scoped design: cloud providers are
**not** a manually-selectable alternative — they're an explicit,
user-confirmed **fallback** for when the local path (Ollama/LM Studio) fails
or the retrieved context is insufficient, and the answer they produce is
**never grounded in this tenant's documents** (no company content is sent to
them at all — only the user's raw question), so it must be visibly and
explicitly distinguished from a normal, cited answer.

**Unblocked for 2 of 3 providers**: `OPENAI_API_KEY` and `GEMINI_API_KEY` are
real, verified working keys (`GET /v1/models` / `GET /v1beta/models` both
returned `200` with real model lists), already in
`credenciais/multi-llm-fallback.env`. `ANTHROPIC_API_KEY` is deliberately
deferred — the user will generate it "na hora de implementar" (when 2e
actually starts), not before. Explicitly confirmed: free/cheapest tiers only,
portfolio use, not production — a real spending cap should be set on the
OpenAI and Anthropic consoles before 2a/2e ship (Gemini's tier is free with no
card, no cap needed).

### Why this is a deliberate exception to ADR 0004, not a violation of it

ADR 0004 ("citations from retrieval, never from the LLM") assumes every
answer is grounded in this tenant's own indexed content. The fallback path
breaks that assumption on purpose: it exists specifically for when grounding
already failed. The design keeps the exception honest by making it
impossible to confuse with a normal answer — no citations array pretending to
be empty-by-coincidence, a distinct response field, and a visible UI badge
(Phase 2d). This must never become the default or silent path.

### Phase 2a — Fallback provider wiring: OpenAI + Gemini ✅

**Done**, with one real, external, honest caveat — see
[ADR 0036](adr/0036-fallback-provider-wiring-openai-gemini.md) for the full
account. Gemini is fully verified working end-to-end (a real
`generateContent` call against `gemini-flash-latest` returns real text).
OpenAI's key authenticates successfully but the account has **zero
credits** (`HTTP 429 insufficient_quota`) — a real, external, user-actionable
blocker (adding a payment method on the OpenAI console) that no amount of
code here can fix, and that this session explicitly did not attempt to
resolve itself (a financial transaction). Two real corrections to the plan
below surfaced during implementation, not assumed from this text: Spring AI
1.0.0 has no plain-API-key Gemini integration (only Vertex AI, a different
product), so `GeminiClient` is a plain REST client, not a Spring AI
`ChatModel`; and the OpenAI fallback bean needed a manually-constructed
`OpenAiApi`/`OpenAiChatModel` pair since Spring AI's OpenAI autoconfiguration
only supports one `spring.ai.openai.*` block, already claimed by LM Studio.
Original plan text kept below for the record:

- `spring-ai-starter-model-openai` is already on the classpath (used today
  for LM Studio); a **second**, separate `ChatModel`/`ChatClient` bean pair
  pointed at real `api.openai.com` (own base-url/key/model — LM Studio and
  real OpenAI are different providers under the same starter, so they need
  distinct `@Qualifier`-named beans, not a shared one).
- A new `spring-ai-starter-model-vertex-ai-gemini` (or the plain Gemini
  REST/Google GenAI starter, whichever Spring AI's current version ships as
  the non-Vertex, plain-API-key path — confirm at implementation time)
  dependency for Gemini, same qualified-bean treatment.
- Two new methods on `LlmGateway` (`callOpenAiFallback`, `callGeminiFallback`),
  each its own Resilience4j `@CircuitBreaker`/`@Retry` instance name,
  independent from `ollama`, `lm-studio`, and from each other — an outage or
  bad key on one must never trip another's breaker (ADR 0009).
- **Deliberately NOT added to `rag.available-models`** — unlike every prior
  provider (Phase 0/ADR 0017/ADR 0020), these must not be manually selectable
  from the normal dropdown, since picking one that way would silently skip
  the confirmation gate (Phase 2c) that makes this design honest. They get
  their own config section instead (e.g. `rag.fallback-providers`).

**Done when**: a direct, isolated call to each of `callOpenAiFallback` and
`callGeminiFallback` (a temporary test path is fine) returns a real answer
from the real API, and a deliberately invalid key for one doesn't affect the
other's circuit breaker state. **Verified for real** via
`FallbackProviderLiveTest` (opt-in, real API calls, gated like the Phase 8
benchmarks): `callGeminiFallback` returns a real answer; `callOpenAiFallback`
authenticates successfully (confirmed not to be an auth failure) but the
real account has zero credits, so full generation isn't confirmed working
yet; the circuit-breaker isolation criterion passed outright — 6 real,
failing OpenAI calls tripped only the `openai-fallback` breaker while
`gemini-fallback` stayed `CLOSED` and kept answering.

### Phase 2b — Fallback trigger detection ✅

**Done.** See [ADR 0037](adr/0037-fallback-trigger-detection.md) for the full
account. A real correction to the plan below, found before writing any code:
"reuse the existing score already on every citation" assumed that score was
still on a cosine-similarity scale — inspecting `HybridSearchService
.fuseWithRrf` showed it's actually the post-RRF-fusion score (`1/(60+rank)`
summed across legs), maxing out around 0.033, nowhere near the existing 0.5
`rag.similarity-threshold`. Since the vector leg already discards anything
below that threshold *before* fusion, an empty `retrieved` list turned out
to already be the correct, meaningful "nothing relevant found" signal — no
second, arbitrarily-calibrated RRF-scale threshold was introduced. Original
plan text kept below for the record:

- **Local infra failure**: the Ollama/LM Studio circuit breaker is already
  `OPEN`, or the call throws/times out.
- **Content insufficiency**: no citation's retrieval score clears a
  threshold (reuse the existing `score` already on every citation — no new
  LLM call needed to detect this).
- Both conditions produce the same downstream behavior (Phase 2c) — the
  reason for triggering doesn't change what the fallback does, only whether
  it's offered at all.

**Done when**: a unit test simulating each of the two trigger conditions
independently confirms the fallback gate is offered, and a normal
successful/grounded answer confirms it is *not* offered. **Verified for
real** via `FallbackTriggerEvaluatorTest` (5 tests, a real
`CircuitBreakerRegistry.ofDefaults()`, no mocking of circuit-breaker state):
an open `ollama` breaker triggers it; an open `lmstudio` breaker triggers it
without affecting `ollama`'s own check; an empty `retrieved` list triggers
it; a normal closed-breaker/non-empty-retrieval case does not. Not yet wired
into any real response — that's Phase 2c's job, since the trigger existing
in isolation doesn't yet mean anything client-visible happens when it fires.

### Phase 2c — Confirmation gate + non-grounded response contract ✅

**Done.** See [ADR 0038](adr/0038-fallback-confirmation-gate-response-contract.md)
for the full account. `ChatRequest` gained `useFallback`/`fallbackProvider`;
`ChatResponse`/`AskResponse` gained `fallbackAvailable`/`source`. A real,
previously-existing bug this phase incidentally fixed: an open local circuit
breaker used to have no proactive check anywhere in `doAnswer`, so a request
would reach the doomed local call and Resilience4j's `CallNotPermittedException`
would propagate as an unhandled 500 — `FallbackTriggerEvaluator`'s check now runs
before generation, turning that into a clean `fallbackAvailable: true` response.
A real, honest, **not** fixed-in-this-phase limitation: a confirmed fallback call
that itself fails (e.g. OpenAI's real zero-credits state) has no dedicated error
shape yet — it surfaces as a generic 500, same as every other unhandled provider
failure in this codebase; designing that error contract properly belongs with
Phase 2d, once `web-ui` needs to show *something* for it. Original plan text kept
below for the record:

- First request, local insufficient (2b triggered): response carries a new
  field (e.g. `fallbackAvailable: true`) **instead of** silently calling a
  public LLM — the caller must confirm before any external call happens.
- A follow-up request with an explicit confirm flag (e.g.
  `useFallback: true`) is what actually calls the public provider (2a) —
  **only the user's question is sent, never any retrieved chunk or document
  content** (this is what keeps company data from ever reaching a public
  API in this path).
- That response is shaped differently from a normal answer: no `citations`
  array (or an explicit, distinctly-named absence of one), a new field
  marking provenance (e.g. `source: "public-llm"` alongside which provider
  answered), so `web-ui` (2d) never has to guess from content whether an
  answer is grounded.

**Done when**: a real `curl` sequence — ask a question with no good local
context, get `fallbackAvailable: true` back, confirm, get a real answer from
OpenAI or Gemini with the distinct non-grounded shape — works end-to-end
against the running stack. **Verified for real**, against the actual local
`docker compose` stack, not just mocked tests: a fresh test tenant with no
matching documents got `fallbackAvailable: true`/`source: null` on the first
request; the identical question with `useFallback: true` (default provider)
returned a real, live `gemini-flash-latest` answer with `source: "public-llm"`
and empty citations; a normal question against a real uploaded document for the
same tenant returned `source: "local"` (retrieval succeeded — RRF score
`0.0328`, matching ADR 0037's math exactly — even though `llama3.1`'s own answer
quality on that one terse chunk was mediocre, an unrelated, pre-existing model
behavior).

### Phase 2d — `web-ui`: confirmation dialog + provenance badge ⬜

**Not started.** Depends on 2c. Two pieces of UI, both carrying the two
warnings from the same screen (cost + provenance), not staged separately:

- A confirmation prompt shown when `fallbackAvailable: true` comes back:
  something like "Não encontrei uma resposta nos seus documentos. Buscar em
  um modelo de IA público (OpenAI/Gemini)? A resposta não será baseada nos
  seus documentos, e isso usa uma API paga/com limite de uso."
- A visible, distinct badge/color on any answer whose `source` marks it as
  `public-llm` — e.g. "⚠️ Resposta de IA pública, não verificada com seus
  documentos" — different enough from the normal citation UI that it can't
  be mistaken for a grounded answer at a glance.

**Done when**: tested for real in the browser — a question with no local
match shows the confirmation prompt, confirming it shows a visibly
different-looking answer with no citations.

### Phase 2e — Fallback provider wiring: Anthropic ⬜

**Not started, deferred on purpose** — the user will generate
`ANTHROPIC_API_KEY` specifically when this sub-phase starts, not before
(unlike OpenAI/Gemini, already provisioned ahead of time in 2a). Otherwise
identical in shape to 2a: `spring-ai-starter-model-anthropic`, a qualified
bean pair, `callAnthropicFallback` with its own breaker, plumbed into the same
2b/2c/2d machinery already built by then — no new design work, just one more
provider.

**Done when**: same criterion as 2a, for Anthropic specifically.

### What changes elsewhere once Phase 2 exists

Phases 3 (`PlannerAgent`) and 4 (`ReflectionAgent`) still assume genuinely
*selectable*, always-available multiple providers — this fallback design
doesn't give them that (the public providers here are single-purpose,
gated, non-grounded). Whether 3/4 build on top of this fallback machinery or
need their own, separate provider wiring is an open question for whenever
those phases actually start, not decided here.

## Phase 3 — `PlannerAgent` ⬜

**Not started — depends on Phase 2.** With today's actual model roster (one
or two local/self-hosted models per environment), a planner deciding "which
specialist should answer this" has essentially nothing to choose between yet
— the practical payoff only exists once Phase 2 provides at least two
meaningfully different real providers (e.g. a fast/cheap one and a
higher-quality one, or providers with genuinely different strengths). Revisit
scope once that's true.

## Phase 4 — `ReflectionAgent` ⬜

**Not started — same dependency as Phase 3.** Comparing/merging multiple
models' answers only makes sense once multiple real providers actually run in
parallel for the same question (Phase 2/3). Note the extra cost: this doubles
(or triples+) the number of paid API calls per question compared to today's
single-model answer — a real, ongoing expense to weigh once providers exist.

## Phase 5 — Long-term memory beyond pgvector (Redis) ⬜

**Not started.** Before any code: define concretely what Redis would add that
Postgres-backed conversation memory (ADR 0013, `chat-service`'s existing
JDBC-backed `ChatMemoryRepository`) doesn't already provide — e.g. a
short-lived session cache, rate-limit counters (already planned in
`docs/SECURITY-HARDENING-ROADMAP.md`'s Phase 2, which could reuse the same
Redis instance if this phase lands first), or a fast key-value layer some
future agent needs. Introducing a new stateful dependency (deployment,
backup, another thing that can go down) needs a real justification, not "the
original vision mentioned it."

## Phase 6 — Tools via MCP ⬜

**Not started.** The original vision's tool list (GitHub, PostgreSQL, Redis,
RAG, Google Search, Jira, Confluence, AWS, Docker, Kubernetes, MCP itself) is
far too broad for one phase. Recommendation when this phase actually starts:
pick 1-2 concrete, genuinely useful tools first (e.g. GitHub — issues/PRs
already relevant to a dev-focused platform — or the project's own RAG
retrieval exposed as an MCP tool for an external client), ship and verify
those end-to-end, then expand — not build generic MCP plumbing for a dozen
tools speculatively.

## Phase 7 — Observability (LangFuse + OpenTelemetry) ⬜

**Not started.** Two separate needs bundled in the original vision:

- **LangFuse** (LLM-specific tracing: prompts, token usage, cost, quality
  scoring per call) needs its own account/hosting decision before
  integration — it's a new external dependency, not a config flag.
- **OpenTelemetry** (distributed tracing across services) is a real,
  already-acknowledged gap — [ADR 0015](adr/0015-observability-stack.md)
  explicitly named it as a deliberate scope cut when Prometheus + Grafana
  were chosen for metrics (*"Distributed tracing across ingestion-service/
  rag-service/chat-service/Ollama is a real, separate gap worth its own future
  item, not folded into this one"*) — this phase is that future item, not a
  new idea.

**Done when**: a real multi-model question shows per-provider latency/cost/
token usage in LangFuse, and a single request's trace is visible end-to-end
across at least two services in whatever tracing backend OpenTelemetry is
configured to export to.

## Phase 8 — RAG quality deep-dive (chunking strategies + evaluation metrics) ✅

**Done.** See [ADR 0034](adr/0034-rag-quality-chunking-and-evaluation-metrics.md)
for the full account, including the real measured numbers (both structure-aware
splitters beat the production baseline by ~0.10 average similarity on a real
document; faithfulness 10/10 on the existing QA corpus; context-relevance
0.20 average, explained by the benchmark's shared-tenant seeding, not a bug).
Semantic and parent-child splitting were **not** added — the plan's own
condition for skipping them ("only add these two if the simpler ones don't
move the needle enough") wasn't met, since recursive and markdown-aware both
already showed a clear, real improvement. Recall/precision and a dedicated
hallucination-rate metric were also not added — both would need new labeled
test-set infrastructure (`relevantChunkIds`-style fields) this phase's
"done when" didn't require. Kept below for the record:

- **Chunking strategies.** Today's pipeline uses only `TokenTextSplitter`
  (fixed token-count chunks, `ingestion.chunk-size-tokens`, no awareness of
  document structure). Worth adding, compared against the current baseline
  with the same benchmark from Fase 7c (`RagQueryService`'s benchmark
  infrastructure, `rag-service/src/test/.../RagQualityBenchmark.java`):
  - **Recursive** splitting (paragraph → sentence → fixed-size fallback,
    instead of blindly cutting at a token count).
  - **Markdown-aware** splitting (respect heading boundaries — most of this
    project's own seeded demo content is Markdown, so this is directly
    testable against real documents already in the repo).
  - **Semantic** splitting (split where embedding similarity between
    consecutive sentences drops, rather than at a fixed size) and
    **parent-child** (index small chunks for precise retrieval, but return
    the larger parent chunk for generation context) are worth a comparison
    but are meaningfully more complex — do fixed vs. recursive vs.
    markdown-aware first, only add these two if the simpler ones don't move
    the needle enough.
- **Formal evaluation metrics**, extending (not replacing) the existing
  cosine-similarity benchmark:
  - **Faithfulness** — is the answer actually supported by the retrieved
    context? This project already has almost exactly this: the groundedness
    check (ADR 0008, `RagQueryService.checkGroundedness`) is a faithfulness
    check in all but name. This phase should reuse it as the faithfulness
    metric in the benchmark rather than building a second, parallel
    implementation.
  - **Context relevance** — are the retrieved chunks actually relevant to the
    question, independent of whether the final answer is any good? Not
    currently measured anywhere; would need a small LLM-as-judge prompt
    scoring each retrieved chunk against the question.
  - **Recall/precision** — needs a labeled test set (which chunks *should*
    have been retrieved for each benchmark question) that doesn't exist yet;
    the existing `benchmark/qa-pairs.json` would need a `relevantChunkIds`-style
    field added.
  - **Hallucination rate** — the inverse framing of faithfulness; likely
    reuses the same groundedness-check machinery rather than needing new code.

**Done when**: at least two chunking strategies are compared against the
existing `RagQualityBenchmark` on the same question set, with a documented
number showing which won and by how much; the benchmark reports faithfulness
(reusing the groundedness check) and context relevance as real, measured
numbers per question, not just the existing single cosine-similarity score.

## Phase 9 — Native tool/function calling (Spring AI `@Tool`) ✅

**Done.** See [ADR 0035](adr/0035-native-tool-calling.md). The plan's suggested
`lookupDocumentById(String documentId)` was refined to a lookup **by exact source
filename** instead — a chat user never sees or types the internal UUID this
project generates per upload, but every citation already shows the filename, so
that's what a real question actually gives the model to reference. Kept below
for the record:

Recommended first tool: something the model can call using data already in this
codebase — e.g. a `lookupDocumentById(String documentId)` tool backed by the
existing `VectorStoreGateway`, letting a question like "summarize document X"
work without needing X's content to already be in the retrieved context.

**Done when**: a real question causes the model to actually invoke the tool
(confirmed via a log line or a debugger breakpoint showing the Java method
ran, not just inferred from the answer's content) and the tool's return value
demonstrably shaped the final answer. **Verified exactly this way**: uploaded a
real document, asked a real question against it by filename, and confirmed via
the actual `rag-service` log (`Tool lookupDocumentBySource invoked:
source=report-2026.md tenantId=<real-uuid> chunksFound=1`) that `llama3.1`
genuinely called the tool with the correct filename and the caller's real
tenant id — not inferred from the (also correct) final answer.

## Phase 10 — Reframe agents around capability, not just LLM provider ⬜

**Not started — depends on Phase 9, and benefits from Phase 2/3 landing
first.** The checklist's own example (a Supervisor routing between a SQL
agent, a RAG agent, and a Security agent) is a different, broader idea than
what Phases 3/4 currently describe: those two are framed as "which *LLM
backend* answers," not "which *capability/tool* answers." Once Phase 9 (tool
calling) exists, this project can meaningfully have agents that differ by
what they can *do*, not just which model answers:

- **RAG agent** — this already exists: it's the entire current `rag-service`.
- **SQL agent** — a genuinely new capability: text-to-SQL against Postgres
  directly (not pgvector — the relational data, if/when there is any worth
  querying this way), returning structured rows instead of retrieved text
  chunks. Needs a concrete, real use case to justify (this project doesn't
  have an obvious relational-query use case yet — don't build this
  speculatively without one).
- **Tool-using agents** (GitHub, etc.) — become real once Phase 6's MCP tools
  (or Phase 9's simpler function-calling tools) exist to hand them.

This phase is intentionally a *reframing*, not new infrastructure by itself —
revisit Phases 3/4's own text once Phase 9 lands, rather than writing a
parallel "Phase 10 implementation" from scratch.

## Phase 11 — Event-driven architecture (Kafka/RabbitMQ) ⬜

**Not started.** Present in the user's original "Enterprise Knowledge
Platform" vision message (which named Kafka explicitly) but hadn't made it
into this roadmap until this gap-check. Needs a provisioning decision (a new
`docker-compose.yml` service — Kafka or RabbitMQ, no paid API key required,
just infrastructure) and, more importantly, **a concrete driving use case** —
don't add messaging infrastructure with nothing real flowing through it.
Candidate first use case: making document ingestion asynchronous — today
`POST /api/v1/documents` blocks the HTTP request for the full
parse→chunk→embed→store pipeline (see `DocumentIngestionService`); a
producer/consumer split (`ingestion-service` publishes a "document uploaded"
event, a consumer does the actual processing) would demonstrate the standard
producer → topic → consumer → DLQ → retry pattern the checklist calls out,
end to end, on infrastructure this project already has a real reason to want
(faster upload response times). The outbox pattern specifically matters if
publishing the event and writing to Postgres need to be atomic — worth
implementing deliberately, not skipped, if this phase happens.

**Done when**: a document upload returns immediately (not after full
processing), a message visibly flows through a real topic, a deliberately
failing consumer message ends up in a dead-letter queue instead of being
silently dropped or retried forever, and the outbox pattern (if used) is
verified against a real Postgres-transaction-then-crash scenario, not just
reasoned about.

## Phase 12 — AWS deployment target ⬜

**Not started — blocked on an AWS account and explicit acceptance of real,
ongoing cost** (unlike the free-tier-friendly Render/Netlify/Neon demo
deployment, ADR 0020 — several of the services in the checklist's list,
Bedrock and OpenSearch in particular, are not meaningfully free). Scope this
narrowly when it starts, not the whole list at once:

- **First slice**: containerized services on ECS (reusing the existing
  Dockerfiles per service, no rewrite needed) or EKS (reusing the existing
  `kubernetes/base/` manifests, which would need the same auth-service
  Deployment+Service gap the README already tracks as a separate "what's
  next" item filled in first) — pick one, not both, to avoid spreading this
  phase across two container orchestrators simultaneously.
- **S3** for any object storage need that emerges (e.g. if uploaded
  originals ever need to be retained — they currently aren't, by design,
  ADR 0018/0019).
- **Bedrock** as a genuine alternative/addition to Phase 2's direct
  OpenAI/Anthropic/Google provider wiring — Spring AI has a Bedrock starter,
  so this could piggyback on Phase 2's `AIProvider` pattern once that exists,
  rather than being a separate integration.
- **CloudWatch** as an alternative/addition to the existing
  Prometheus/Grafana stack (ADR 0015) if the deployment target is AWS-native
  enough to want AWS-native observability instead of/alongside it.
- **API Gateway, SQS, SNS, EventBridge, DynamoDB, IAM** — pull in only the
  ones a concrete piece of this phase actually needs (e.g. SQS/EventBridge
  overlap significantly with Phase 11's event-driven work — if both phases
  happen, do the event-driven design once and pick the concrete
  implementation, AWS-native or self-hosted Kafka, rather than building it
  twice).

**Done when**: at least one service is genuinely running on AWS (not just
manifests written) and reachable end-to-end for a real request, with the
real monthly cost this incurs documented plainly (not hidden in "should be
mostly free tier").

## Phase 13 — Python + LangGraph AI layer ⬜

**Not started — the single biggest structural gap this gap-check found, and
explicitly named as such by the user's own checklist** ("o único ponto da
vaga em que você teria uma lacuna maior"). This is not a library swap — it's
a new service in a new language, a real, ongoing maintenance surface, and a
genuine trade-off: this portfolio project would go from "one language,
consistently" to polyglot. **Confirm that trade-off is wanted before writing
any code.**

If confirmed, the recommended shape (reuse, don't duplicate): a new Python
service (e.g. `agent-service/`) using LangGraph for the planner/executor/tool
loop the checklist describes, calling into the *existing* Java services over
their already-documented REST APIs (`rag-service`'s `/api/v1/ask`,
`ingestion-service`'s `/api/v1/documents`) rather than reimplementing
retrieval or ingestion in Python. This mirrors exactly how `chat-service`
already treats `rag-service` as a retrieval provider (ADR 0013) — the new
Python service becomes another client of the same APIs, not a parallel stack.
LangFuse (Phase 7) instruments this service too, for the same reason it
instruments the Java side — the two aren't separate observability problems.

**This phase is deliberately not framed as a performance/memory decision,
unlike Phase 15 (Go) below** — asked directly, the honest answer is that
Python doesn't make requests faster or lighter than the existing Java
services; a Python process doing real agent-orchestration work (especially
once any ML/NLP library is involved) has its own real memory footprint. The
reason for Python here is purely that LangGraph/LangChain/LlamaIndex/CrewAI/
DSPy are Python-first tools with no real Java equivalent — "the tools I need
only exist here," which is a legitimate reason on its own, just a different
one than Phase 15's.

**Done when**: a real question answered by the Python/LangGraph service
demonstrably called at least one of the existing Java services' real HTTP
APIs (confirmed via that service's own access logs, not assumed), and the
whole interaction shows up in whatever observability this phase is verified
against (LangFuse and/or the existing Prometheus/Grafana stack).

## Phase 14 — Software engineering polish (SonarQube, architecture docs) 🟡

**`docs/architecture.md` refresh: done** — turned out to already have
happened in an earlier, unrelated commit (found, not written, while starting
this phase) — it now covers all 4 services and matches the README's diagram.

**SonarCloud + JaCoCo: code done, waiting on one external step only the user
can do.** See [ADR 0027](adr/0027-sonarcloud-jacoco-code-quality.md) for the
full decision record. What's actually in place:

- JaCoCo wired into all 4 tested modules (`auth-service`, `ingestion-service`,
  `rag-service`, `chat-service`) — real coverage confirmed by running
  `./mvnw -B verify` and reading the generated `jacoco.xml`, not guessed:
  91.2%, 90.7%, 84.8%, 93.7% instruction coverage respectively.
- `.github/workflows/ci.yml` has a guarded SonarCloud analysis step
  (`if: secrets.SONAR_TOKEN != ''`) — it stays a no-op, keeping CI green,
  until the token exists as a repo secret.
- `pom.xml` has `sonar.organization`/`sonar.projectKey` set to SonarCloud's
  default naming guess (`eniglio-ctrl` / `eniglio-ctrl_enterprise-rag-platform`)
  — verify these against whatever SonarCloud actually assigns once the
  project is imported.

**What's left, and it's not something this assistant can do**: creating a
SonarCloud account (via GitHub OAuth), importing this repo as a project, and
generating a token — account creation and granting OAuth access are both
outside what an assistant does on a user's behalf. Once `SONAR_TOKEN` exists
as a GitHub repo secret, the guarded CI step activates on the next push with
no further code change needed.

**Done when**: `docs/architecture.md` accurately describes all 4 services and
matches the README's diagram (✅ already true); a SonarQube/SonarCloud badge
is visible in the README and reflects a real, current analysis run — this
last part is what's still open.

## Phase 15 — Go-based API Gateway / BFF ⬜

**Not started.** Added after a direct follow-up question about where Go,
Java, and Python each genuinely help with performance/memory — not from the
original pasted vision, which didn't mention Go at all (the checklist's own
"what's next" text only flagged Go as a general market-relevant skill, with
no concrete fit for this project identified at the time). The concrete fit
that emerged: a lightweight API Gateway/BFF sitting in front of the four Java
services, which does two real things at once —

- **Fills a genuine, still-open gap**: "API Gateway" is one of the
  microservices patterns the checklist names that nothing in this project
  implements today (Circuit Breaker/Retry are done, ADR 0009; API Gateway
  is not).
- **Is an honest performance/memory choice, not a speculative one**: this
  project has *already* hit a real memory-footprint wall from the JVM on
  constrained infra (ADR 0020 — an OOM kill on Render's 512MB free tier,
  JVM baseline plus a local embedding model together). A Go binary's
  baseline memory footprint (single-digit-to-low-double-digit MB) is
  roughly a tenth of a JVM's before either does any real work, with
  near-instant cold start (no JIT warm-up) and cheap per-connection
  concurrency via goroutines — exactly the profile that matters for
  something sitting in front of every request into the platform, and
  exactly the kind of constraint this project has real, lived experience
  with, not a hypothetical one.

**Scope to decide before starting** (keep it narrow for a first version):
routing + JWT validation pass-through only (verify the bearer token against
`auth-service`'s JWKS, same as every Java service already does via
`platform-common`'s `ResourceServerSecurityConfig`, then proxy to the right
backend), versus also taking over Security Phase 2's rate limiting at the
edge instead of (or alongside) per-service Java filters — the latter is
arguably the more natural home for rate limiting architecturally (one place,
not four), but changes Security Phase 2's own design if decided before that
phase starts, so this decision should happen before both, not independently.

**Done when**: a real request to any of the four backend services can be
routed through the Go gateway instead of hitting the service directly, with
JWT validation happening at the gateway (confirmed by sending a request with
no/invalid token and getting rejected at the gateway, before it ever reaches
the Java service); the gateway's own memory footprint is measured and
documented against the Java services' for a real, side-by-side comparison —
not asserted from general knowledge about Go vs. JVM footprints.
