# Multi-LLM Orchestrator Roadmap

> This file is the single source of truth for the multi-LLM orchestrator
> initiative — readable on its own, without needing any prior chat session or
> plan file. Update the status marker and "what was done" notes as each phase
> lands. Same convention as
> [`docs/SECURITY-HARDENING-ROADMAP.md`](SECURITY-HARDENING-ROADMAP.md) — see
> that file if this structure looks unfamiliar.

## Status at a glance

| Phase | What | Status |
|---|---|---|
| 0 | "Automático" model selector | ✅ Done — [ADR 0025](adr/0025-auto-model-selection.md) |
| 1 | Single LLM + RAG | ✅ Already done (with Ollama, not literally OpenAI) |
| 2 | Real cloud providers as a shared `AIProvider` abstraction | ⬜ Blocked — needs real, paid API keys the user must supply |
| 3 | `PlannerAgent` (decides which specialist handles a request) | ⬜ Not started — depends on Phase 2 |
| 4 | `ReflectionAgent` (compares/merges multiple models' answers) | ⬜ Not started — depends on Phase 2/3 |
| 5 | Long-term memory beyond pgvector (Redis) | ⬜ Not started — needs a concrete "what does Redis add" answer first |
| 6 | Tools via MCP | ⬜ Not started — needs scope cut to 1-2 concrete tools |
| 7 | Observability (LangFuse + OpenTelemetry) | ⬜ Not started — needs a LangFuse account/hosting decision |

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

## Phase 2 — Real cloud providers as a shared `AIProvider` abstraction ⬜

**Blocked — do not start without an explicit decision from the user first**:
which provider(s) (OpenAI, Anthropic, Google, or some subset), and
confirmation of the real, ongoing API cost this introduces (unlike Ollama/LM
Studio, these are paid, metered APIs). No code should be written before that.

Once unblocked, the implementation follows the exact pattern ADR 0017 already
established for adding a provider — not a new design:

- A new `spring-ai-starter-model-*` dependency per provider (`openai` is
  already present and can be pointed at `api.openai.com` directly for real
  ChatGPT access — a new, separate `ChatModel`/`ChatClient` bean pair from the
  existing LM-Studio-pointed one, since they're different base
  URLs/keys/models, not a shared bean; `anthropic` and a Google/Vertex AI
  starter would be genuinely new dependencies for Claude/Gemini).
- A new qualified `ChatClient` bean per provider in `ChatClientConfig`
  (`@Qualifier`-named, built from a `@Qualifier`-named `ChatModel` — the same
  reason two beans already exist: Spring AI's auto-configured single-candidate
  `ChatClient.Builder` backs off once more than one `ChatModel` bean exists).
- A new method per provider on `LlmGateway`, each with its own Resilience4j
  `@CircuitBreaker`/`@Retry` instance name — an outage in one provider must
  never trip another's breaker (ADR 0009's established reasoning).
- A new entry per provider in `rag.available-models` — nothing in
  `ModelsController`/`web-ui` needs to change, exactly like Phase 0.
- Only *then* would a shared `AIProvider` interface (`com.eniglio.ragplatform
  .common.ai` or similar, in `platform-common`) be worth introducing — as a
  refactor of the by-then-real `clientFor`/`callLlm`/`modelOptions` dispatch,
  not speculative scaffolding built before there's more than one real
  provider to abstract over.

**Done when**: at least one real cloud provider (not Ollama/LM Studio/Groq)
is selectable in the dropdown and answers a real question end-to-end, with
its own circuit breaker confirmed independent of the others (e.g. a
deliberately invalid API key for one provider doesn't affect another's
requests).

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
