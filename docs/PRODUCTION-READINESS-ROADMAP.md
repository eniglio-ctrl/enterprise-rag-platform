# Production Readiness Roadmap

> This file is the single source of truth for "what would it take to run this
> for real" — readable on its own, without needing any prior chat session.
> Same convention as
> [`docs/SECURITY-HARDENING-ROADMAP.md`](SECURITY-HARDENING-ROADMAP.md) and
> [`docs/MULTI-LLM-ORCHESTRATOR-ROADMAP.md`](MULTI-LLM-ORCHESTRATOR-ROADMAP.md):
> a status table, a phase-by-phase writeup with a concrete "done when," and
> an explicit warning not to trust a ✅ without re-verifying it.
>
> **Scope, and why this file exists separately from the other two**: this
> project already has a real, working vertical slice (ADR 0021's security
> rollout plus the Multi-LLM fallback work are both evidence of that). This
> file is deliberately about the *other* kind of maturity — the operational
> concerns that only show up under real production load, multiple replicas,
> or real failure — asked for directly on 2026-08-03 with the explicit
> framing "antes de adicionar mais features de IA, eu priorizaria estas
> evoluções para produção." Several phases below already had a partial home
> in the other two roadmap files (the gateway, the event queue, Redis,
> tracing, and the DOCX upload gap were all already tracked in
> `docs/ROADMAP.md` before this file existed) — this file doesn't duplicate
> those, it cross-references them and adds the production-operations framing
> and sequencing around them. Four phases here (secrets/config management,
> operational resilience hardening, resource-level authorization, and
> backups/recovery) are genuinely new, not tracked anywhere else yet.
>
> **Deliberately not urgent, and said so explicitly by the person who asked
> for this file**: none of this is a defect in what already exists — the
> current stack is a real, working, honestly-scoped portfolio project, not a
> production deployment, and doesn't need to become one to be complete (see
> `docs/ROADMAP.md`'s own "Portfolio-ready stopping point" section). This
> file exists so the *next* evolution, if and when it's wanted, has a
> thought-through order instead of an ungrounded technology list.

## Status at a glance

| Phase | What | Status | Depends on |
|---|---|---|---|
| 1 | Close the upload validation gap (zip-as-docx + zip bomb) | ✅ Done — `docs/ROADMAP.md` Tier 1 #13, [ADR 0022](adr/0022-upload-validation-hardening.md)'s "Update" section | — |
| 2 | Secrets and configuration management for production | ⬜ Not started | — |
| 3 | Async ingestion (queue) + separate file storage | ⬜ Not started — the queue half already tracked as `docs/ROADMAP.md` Tier 2 #23 | Phase 2 (storage credentials need real secrets management first) |
| 4 | Operational resilience hardening (timeouts, concurrency limits, readiness probes) | ✅ Done — [ADR 0043](adr/0043-operational-resilience-hardening.md) | — |
| 5 | API Gateway / BFF at the edge | ⬜ Not started — already tracked as `docs/ROADMAP.md` Tier 2 #24 | Phase 4 (the gateway is where centralized timeout/rate-limit policy would live) |
| 6 | Distributed tracing (OpenTelemetry) | ⬜ Not started — already tracked as the OpenTelemetry half of `docs/MULTI-LLM-ORCHESTRATOR-ROADMAP.md` Phase 7 | Phase 5 (a trace crossing the gateway is the whole point) |
| 7 | Redis (distributed rate limit, cache, sessions) | ⬜ Not started — already tracked as `docs/ROADMAP.md` Tier 2 #19 | Real multi-replica deployment or measured load — not before |
| 8 | Resource-level authorization (RBAC/ABAC) | ⬜ Not started | — |
| 9 | Backups and disaster recovery | ⬜ Not started | — |

**Recommended order, in the user's own words**: "fechar upload seguro → fila
assíncrona + storage → gateway → tracing → Redis quando houver escala." The
table above follows that shape, with the four new phases (2, 4, 8, 9) slotted
in at the points where they most naturally strengthen the phases already
named — not reordering what was explicitly asked for. Phase 8 (authorization)
and Phase 9 (backups) have no real technical dependency on anything else here
and could be done in any order relative to the rest; they're placed last
because they're feature/operational-maturity work rather than
infrastructure-hardening work, matching how they were the two items not
mentioned in the explicit sequence above.

**Don't trust a ✅ here on faith** — re-verify with `git log`, `ls`, and a
fresh `./mvnw clean verify` before relying on a status claim for anything
that matters, same warning the other two roadmap files already carry.

## Phase 1 — Close the upload validation gap (zip-as-docx + zip bomb) ✅

**Done**, closed the same day this file was written. Full account in
`docs/ROADMAP.md` Tier 1 #13 and [ADR 0022](adr/0022-upload-validation-hardening.md)'s
own "Update" section — not repeated here in full. Placed first in this file
for the same reason the user placed it first: it was the most concrete,
already-real security risk of everything on this list, and needed zero new
infrastructure to fix (a code change to `UploadValidationService`, not a new
dependency) — exactly the kind of item worth closing before anything else
that does need a design decision or new infrastructure.

## Phase 2 — Secrets and configuration management for production ⬜

**Not started. Genuinely new — no existing tracking anywhere in this
project.** Today's secrets story (Security Phase 3, ADR 0029) is real but
explicitly dev/demo-shaped: a gitignored `.env` file `docker-compose.yml`
requires and fails fast without, `.env.example` documenting every variable,
and (Security Phase 4, ADR 0031) a JWT signing key that can be mounted as a
file or passed as a Base64 env var in Kubernetes. That's the right shape for
local/demo use — it is explicitly **not** what a real deployment should run
on operationally, and this project has never claimed otherwise.

What "production" actually needs, that `.env` structurally can't provide:
- **Rotation without a restart-and-redeploy cycle** — today, changing any
  secret (DB password, JWT signing key, a fallback provider's API key) means
  editing `.env` and recreating containers. A real secrets manager (Vault,
  AWS/GCP/Azure's managed secret stores, or even just Kubernetes `Secret`
  objects synced from one) can rotate a credential and have it picked up
  without a full redeploy, depending on how the app reads it.
- **An audit trail of who read/changed which secret, when** — `.env` has
  none; a real secrets manager does, by design.
- **Strict separation between dev/demo/prod configuration** — today's
  `application.yml`/`application-demo.yml` Spring profile split already
  does this reasonably well for *application* config (ADR 0020's own
  pattern); the gap is specifically that *secrets* still flow through the
  same `.env`-shaped mechanism regardless of profile, so there's no
  structural barrier stopping a demo credential from ending up in a
  prod-shaped config by copy-paste error.

**Done when**: a concrete decision on which secrets backend (Vault vs. a
cloud-managed store vs. plain Kubernetes `Secret`s with an external-secrets
operator) is made — this phase is blocked on that decision more than on
implementation effort — followed by at least one real secret (the JWT
signing key is the highest-value candidate, given ADR 0016's own
already-named "no rotation" limitation) actually sourced from it instead of
an env var, verified by rotating it once without restarting the service that
consumes it.

## Phase 3 — Async ingestion (queue) + separate file storage ⬜

Two needs the user explicitly bundled together, for a real reason: an async
queue without durable storage just moves the "where did the bytes go"
problem, and durable storage without an async queue doesn't fix the
"upload blocks on parsing a 25MB PDF" problem. The queue half is already
tracked as `docs/ROADMAP.md` Tier 2 #23 (Kafka/RabbitMQ, blocked on a
concrete driving use case and a provisioning decision) — this phase adds the
storage half, which wasn't tracked anywhere before, and states plainly why
they belong together.

- **Today**: `POST /api/v1/documents` reads the whole upload into memory
  (`UploadValidationService`), extracts/chunks/embeds synchronously, and only
  then returns — a genuinely large PDF holds the HTTP connection open for the
  entire pipeline. The file's bytes are never persisted anywhere; only the
  derived chunks reach pgvector. A restart mid-processing loses the original
  document permanently, and there's no way to re-run extraction with a
  different chunking strategy later (relevant given ADR 0034's own two new
  splitters, not yet wired into the real pipeline) without asking the user
  to re-upload.
- **What this phase would add**: `POST /api/v1/documents` writes the raw
  file to object storage (S3, or MinIO for a self-hosted-friendly local
  equivalent) and a `documents` row with `status: PENDING`, then returns
  `202 Accepted` immediately with the document id. A worker (consuming from
  whichever queue Tier 2 #23 lands on) picks up the job, moves status to
  `PROCESSING`, runs the existing extraction/chunking/embedding pipeline
  unchanged, and sets `READY` or `FAILED` (with a reason) at the end. Retry
  and a dead-letter queue for `FAILED` jobs come from the queue technology
  itself, not custom code.
- **What doesn't change**: the actual parsing/chunking/embedding logic in
  `DocumentIngestionService` — this phase moves *when* and *how* it's
  triggered, not what it does.

**Done when**: uploading a real, large document returns `202` immediately
(not after full processing), a real client can poll the document's status
through `PENDING → PROCESSING → READY`, a deliberately-killed worker mid-job
leaves the document retriable (not silently lost), and the original file
bytes are recoverable from object storage independent of what's in pgvector.

## Phase 4 — Operational resilience hardening ✅

**Done.** All three gaps below, confirmed for real before writing any code
rather than assumed, are closed. Full account:
[ADR 0043](adr/0043-operational-resilience-hardening.md).

- **No concurrency limit anywhere on LLM/Whisper calls** — closed:
  `@Bulkhead` (Resilience4j, `SEMAPHORE`, `max-wait-duration: 0` — fail fast,
  never queue) added to every local-model gateway across all three services
  (rag-service's `LlmGateway`, ingestion-service's `VectorStoreGateway`/
  `VisionGateway`/`AudioTranscriptionGateway`, and a **new** `LlmGateway` in
  chat-service — scoping this surfaced that chat-service's own direct Ollama
  call had no resilience wrapping *at all* before this, a real gap beyond
  what this phase originally set out to find).
- **Readiness and liveness probes point at the exact same endpoint** —
  closed: `management.health.probes.enabled: true` plus
  `management.endpoint.health.group.readiness.include: readinessState, db` /
  `.liveness.include: livenessState` in all four services;
  `kubernetes/base/*.yaml` now point `readinessProbe` at
  `/actuator/health/readiness` and `livenessProbe` at
  `/actuator/health/liveness`.
- **Timeouts exist but aren't uniformly audited** — closed: the audit found
  and fixed three real gaps with **zero** timeout configured before this —
  `GeminiClient`, the OpenAI-fallback `ChatClient`, and (unexpectedly)
  ingestion-service's own vision-model `RestClient.Builder`, the one
  local-model client in the whole codebase missing one, unlike its Whisper
  sibling in the same service.

**Verified for real, not just in automated tests**: fired 8 real concurrent
requests against the actual running stack's real Ollama — the bulkhead
rejected the excess 4 in ~155ms with a clean, distinct 503; the 4 that got
through then genuinely failed for real (this machine's Ollama couldn't
reliably complete 4 truly-concurrent `llama3.1` calls), tripping the circuit
breaker too, which recovered on its own 30s later — real evidence the two
mechanisms compose correctly under an actual failure, not a scripted one.
`docker compose pause postgres`'d the real local Postgres:
`/actuator/health/liveness` stayed `200 UP` throughout;
`/actuator/health/readiness` correctly went `503 DOWN` once HikariCP's own
connection-timeout elapsed, and recovered immediately on unpause.

## Phase 5 — API Gateway / BFF at the edge ⬜

Already tracked as `docs/ROADMAP.md` Tier 2 #24 ("Go-based API Gateway/BFF"),
including the real performance/memory rationale for Go specifically (see
that file's "Where Go, Java, and Python actually fit" section) — not
repeated here. Placed after Phase 4 in this file's sequence because the
gateway is the natural place centralized timeout/rate-limit policy from that
phase would actually live, once it exists — building the policy first and
centralizing it second avoids designing the gateway around a policy that
doesn't exist yet.

## Phase 6 — Distributed tracing (OpenTelemetry) ⬜

Already tracked as the OpenTelemetry half of
`docs/MULTI-LLM-ORCHESTRATOR-ROADMAP.md` Phase 7 (deliberately separated
there from LangFuse, which is LLM-specific tracing, not general distributed
tracing) — not repeated here. [ADR 0015](adr/0015-observability-stack.md)
already named this exact gap explicitly when Prometheus + Grafana were
chosen for metrics instead. Placed after the gateway in this file's sequence
because a trace that actually crosses the gateway (`web-ui` → gateway →
`rag-service` → Postgres/Ollama) is a meaningfully more complete story than
one that starts at `rag-service`, which is all that's possible before Phase
5 exists.

## Phase 7 — Redis (distributed rate limit, cache, sessions) ⬜

Already tracked as `docs/ROADMAP.md` Tier 2 #19 — not repeated here. Placed
**last** among the infrastructure phases in this file, per the user's own
explicit framing: "Redis quando houver escala" (only once there's more than
one replica or real measured load). Today's in-memory, per-instance
`RateLimitFilter` (Security Phase 2, ADR 0028) is correct and sufficient for
a single-replica deployment; Redis-backed distributed rate limiting, a JWKS/
model-list cache, or ephemeral session storage only start earning their
operational complexity once a second replica actually exists. Building this
before that point would be exactly the "collection of technologies without
real necessity" the user explicitly said this sequence exists to avoid.

## Phase 8 — Resource-level authorization (RBAC/ABAC) ⬜

**Not started. Genuinely new.** Today's authorization model (ADR 0007) is
tenant-only: any authenticated user within a tenant can read/write every
document that tenant owns, with no finer-grained concept of ownership,
groups, or per-document sharing. That's a reasonable, honestly-scoped
choice for a portfolio project demonstrating multi-tenant isolation — it
would not be reasonable for a real deployment where "everyone in the same
company sees every document" is rarely the actual requirement.

What a real version of this would need: a `documentId` → `{owner, group,
visibility}` model (extending the existing `vector_store`/`documents`
schema, not replacing it), an authorization check in `HybridSearchService`/
`DocumentLookupTool` (the Phase 9 `@Tool`, ADR 0035, is a second place this
would need to apply — it already enforces tenant isolation via
`ToolContext`, the same mechanism would need to carry finer-grained
permission data too) alongside the existing tenant filter, and a decision
between RBAC (roles determine access) and ABAC (attributes/policies
determine access) — the two solve different real shapes of "who can share
what with whom" and shouldn't be conflated as one design question.

**Done when**: two users in the same tenant, one of whom is explicitly not
granted access to a specific document, can be shown for real — via the
actual running stack, not just a unit test — that the restricted user's
questions never retrieve chunks from that document, while an explicitly
shared or public document remains visible to both.

## Phase 9 — Backups and disaster recovery ⬜

**Not started. Genuinely new — and the most "not yet a production system"
gap on this entire list.** Today, `docker-compose.yml`'s `postgres-data`
volume and the Kubernetes manifests' equivalent persistent storage are the
*only* copies of every tenant's data (documents' derived chunks, chat
history, tenant/user records, audit logs). There is no backup automation,
no retention policy, and — critically — no restore procedure that has ever
actually been exercised. An architecture that only accounts for the normal-
operation path, never the "what happens when this breaks" path, isn't a
production architecture yet regardless of how well everything else here is
built.

What this would need, concretely:
- **Automated, scheduled Postgres backups** (`pg_dump`/`pg_basebackup`, or a
  managed database's own backup feature if this ever moved off self-hosted
  Postgres) — not a one-time manual snapshot.
- **A retention policy** for both the database backups and any object
  storage introduced by Phase 3 (how long are old versions/deleted
  documents actually kept, and does that answer satisfy whatever real
  compliance requirement would apply — this project doesn't have one today,
  but a real deployment likely would).
- **An actually-tested restore** — the single most commonly-skipped step in
  real incidents: a backup nobody has ever restored from is a hope, not a
  guarantee. This phase isn't done when backups exist; it's done when a
  restore has been performed for real, into a fresh environment, and
  verified to produce a working system.

**Done when**: a real backup is taken, the environment it came from is
destroyed (a fresh `docker compose down -v` or an equivalent clean slate),
and a real restore from that backup alone brings the system back to a
working state — verified by asking a real question against a real document
that only existed before the destruction, not just checking that Postgres
starts.

## How this file relates to the other two roadmaps

- [`docs/ROADMAP.md`](ROADMAP.md) still owns execution order across
  *everything* pending in the project, including the items this file
  cross-references (Tier 1 #13, Tier 2 #19/#23/#24) — this file doesn't
  supersede that ordering, it explains the production-operations reasoning
  behind a subset of it and adds the phases that weren't tracked anywhere
  before.
- [`docs/MULTI-LLM-ORCHESTRATOR-ROADMAP.md`](MULTI-LLM-ORCHESTRATOR-ROADMAP.md)'s
  Phase 7 still owns the LangFuse half of observability — genuinely
  LLM-specific (prompt/token/cost tracing), not a production-operations
  concern the way distributed tracing is.
- None of the four genuinely-new phases here (2, 4, 8, 9) have an ADR yet —
  they're planning only, exactly as asked for on 2026-08-03. An ADR gets
  written if and when one of them is actually implemented, matching the
  convention every other phase in this project already follows.
