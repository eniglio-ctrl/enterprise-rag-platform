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
| 2 | Real cloud providers as a shared `AIProvider` abstraction | ⬜ Not started | Real, paid API keys the user must supply |
| 3 | `PlannerAgent` (decides which specialist handles a request) | ⬜ Not started | Phase 2 |
| 4 | `ReflectionAgent` (compares/merges multiple models' answers) | ⬜ Not started | Phase 2/3 |
| 5 | Long-term memory beyond pgvector (Redis) | ⬜ Not started | A concrete "what does Redis add" answer |
| 6 | Tools via MCP | ⬜ Not started | Scope cut to 1-2 concrete tools; benefits from Phase 9 landing first |
| 7 | Observability (LangFuse + OpenTelemetry) | ⬜ Not started | A LangFuse account/hosting decision |
| 8 | RAG quality deep-dive (chunking strategies + formal eval metrics) | ⬜ Not started | **Nothing — can start now** |
| 9 | Native tool/function calling (Spring AI `@Tool`) | ⬜ Not started | **Nothing — can start now** |
| 10 | Reframe agents around capability, not just LLM provider | ⬜ Not started | Phase 9 (and Phase 2/3 for real multi-provider value) |
| 11 | Event-driven architecture (Kafka/RabbitMQ, outbox pattern) | ⬜ Not started | A provisioning decision (new infra, no paid key needed) |
| 12 | AWS deployment target (ECS/EKS/Lambda/Bedrock/OpenSearch/...) | ⬜ Not started | An AWS account + explicit real-cost acceptance |
| 13 | Python + LangGraph AI layer | ⬜ Not started | User confirming they want a second language in this portfolio project |
| 14 | Software engineering polish (SonarQube, `docs/architecture.md` refresh) | ⬜ Not started | **Nothing — can start now** |
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

## Phase 8 — RAG quality deep-dive (chunking strategies + evaluation metrics) ⬜

**Not started — nothing blocks this, can start now.** Two related gaps found
during the checklist gap-check, both strengthening the existing RAG pipeline
rather than adding new infrastructure:

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

## Phase 9 — Native tool/function calling (Spring AI `@Tool`) ⬜

**Not started — nothing blocks this, can start now.** A real, distinct gap
from Phase 6 (MCP): Spring AI supports the LLM directly invoking a Java
method during a chat completion (`@Tool`-annotated methods, or the
`FunctionCallback` API) — no external protocol, no separate tool server, just
a method on an existing `@Component` the model can choose to call mid-answer.
This is the natural, much cheaper stepping stone before MCP (Phase 6), which
is genuinely more complex (a full client-server protocol, external tool
processes). Recommended first tool: something the model can call using data
already in this codebase — e.g. a `lookupDocumentById(String documentId)`
tool backed by the existing `VectorStoreGateway`, letting a question like
"summarize document X" work without needing X's content to already be in the
retrieved context.

**Done when**: a real question causes the model to actually invoke the tool
(confirmed via a log line or a debugger breakpoint showing the Java method
ran, not just inferred from the answer's content) and the tool's return value
demonstrably shaped the final answer.

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

## Phase 14 — Software engineering polish (SonarQube, architecture docs) ⬜

**Not started — nothing blocks this, can start now.** Two concrete, unblocked
items found during the gap-check:

- **SonarQube** (code quality/maintainability/test-coverage analysis) is
  genuinely absent — distinct from Phase 7 of
  `docs/SECURITY-HARDENING-ROADMAP.md`'s CodeQL (security-focused static
  analysis) and Dependabot (dependency vulnerabilities) — those two don't
  cover code quality/duplication/maintainability metrics at all. Either
  SonarCloud (free for public repos, matching this project's already-public
  GitHub repo) or a self-hosted SonarQube instance, wired into
  `.github/workflows/ci.yml` as an additional step.
- **`docs/architecture.md` is stale** — confirmed during this gap-check's
  research: it still documents only `ingestion-service` + `rag-service`,
  predating both `auth-service` and `chat-service` entirely. The README's own
  architecture diagram (`README.md` lines ~33-53) is the actually up-to-date
  one. This phase should refresh `docs/architecture.md` to match — including
  explicitly calling out where Clean Architecture/DDD/SOLID ideas already
  show up in the existing design (e.g. `platform-common`'s extraction, ADR
  0010; the `ValidatedUpload`/`DocumentKind` value-object pattern, ADR 0022)
  rather than writing new architecture from scratch — this project already
  practices a fair amount of this, it's just not documented as such anywhere
  a reviewer would look first.

**Done when**: `docs/architecture.md` accurately describes all 4 services and
matches the README's diagram; a SonarQube/SonarCloud badge is visible in the
README and reflects a real, current analysis run, not a stale one-time scan.

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
