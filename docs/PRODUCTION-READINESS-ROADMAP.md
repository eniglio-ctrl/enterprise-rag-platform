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
| 2 | Secrets and configuration management for production | ✅ Done — Vault (dev mode) — [ADR 0048](adr/0048-vault-for-the-jwt-signing-key.md) | — |
| 3 | Async ingestion (queue) + separate file storage | ⬜ Not started — the queue half already tracked as `docs/ROADMAP.md` Tier 2 #23 | Phase 2 (storage credentials need real secrets management first) |
| 4 | Operational resilience hardening (timeouts, concurrency limits, readiness probes) | ✅ Done — [ADR 0043](adr/0043-operational-resilience-hardening.md) | — |
| 5 | API Gateway / BFF at the edge | ⬜ Not started — already tracked as `docs/ROADMAP.md` Tier 2 #24 | Phase 4 (the gateway is where centralized timeout/rate-limit policy would live) |
| 6 | Distributed tracing (OpenTelemetry) | ⬜ Not started — already tracked as the OpenTelemetry half of `docs/MULTI-LLM-ORCHESTRATOR-ROADMAP.md` Phase 7 | Phase 5 (a trace crossing the gateway is the whole point) |
| 7 | Redis (distributed rate limit, cache, sessions) | ⬜ Not started — already tracked as `docs/ROADMAP.md` Tier 2 #19 | Real multi-replica deployment or measured load — not before |
| 8 | Resource-level authorization (RBAC/ABAC) | ✅ Done — ABAC — [ADR 0046](adr/0046-resource-level-authorization-abac.md); extended with a tenant `ADMIN` role — [ADR 0047](adr/0047-tenant-admin-role.md) | — |
| 9 | Backups and disaster recovery | ✅ Done — [ADR 0044](adr/0044-backups-and-disaster-recovery.md) | — |

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

## Phase 2 — Secrets and configuration management for production ✅

**Done.** Decision: HashiCorp Vault, dev mode, run locally via
docker-compose — free, no cloud account, verified for real via a
Testcontainers-backed integration test (`VaultKeyRotationIT`) that rotates
the JWT signing key through a real Vault and confirms the running process
picks it up via `POST /actuator/refresh`, with no restart. Full account:
[ADR 0048](adr/0048-vault-for-the-jwt-signing-key.md).

Before this phase's account (kept below for the original framing), today's
secrets story (Security Phase 3, ADR 0029) is real but
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

**Done when, verified for real**: `JwtKeyProvider` (`auth-service`) now
sources `auth.signing-key.value` from Vault's KV backend (same property
name `${JWT_SIGNING_KEY:}` used to populate directly). It reads
`Environment` directly and re-resolves on an `EnvironmentChangeEvent` -
the third design tried, after two that looked correct on paper and were
disproved only by actually running them: `@RefreshScope` never
re-triggered the constructor in this config-data-import setup, and
re-fetching `AuthProperties` via `ObjectProvider` raced
`ConfigurationPropertiesRebinder` listening to the same event on a
different bean (full account, including how each attempt was caught, in
[ADR 0048](adr/0048-vault-for-the-jwt-signing-key.md)). A new
`VaultKeyRotationIT` proves rotation end-to-end against a real
Testcontainers Vault: seed key A, issue a token, verify it against the
real JWKS *and* that the kid matches key A's own exact thumbprint (the
stricter check that caught the second attempt's bug); overwrite the
secret with key B; `POST /actuator/refresh`; issue a new token and
confirm it verifies against key B's exact thumbprint, in the same
process — and that the pre-rotation token no longer verifies
post-rotation (a real, deliberate hard-cutover, not a grace-period
rotation — see the ADR's limitations section). Manually re-verified
against the actual docker-compose stack too, confirming via `docker
inspect`'s `StartedAt` that `auth-service` was never restarted. Two more
real bugs found only by running things for real, not by reasoning about
the YAML: `spring.cloud.vault.enabled: false` in the test profile didn't
stop Spring Cloud Vault from eagerly building its `TOKEN` authentication
and throwing on a blank token before `optional:`/fail-fast could help
(fixed with a non-blank placeholder default); and `@DynamicPropertySource`
resolves too late for `spring.config.import: vault://` to see it, causing
every automated run to silently fall back to an ephemeral key and pass
for the wrong reason (fixed by setting JVM system properties in a static
initializer instead).

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

**Update (2026-08-05)**: this phase is now also a named prerequisite in
[`docs/EXTERNAL-DATA-INTEGRATION-ROADMAP.md`](EXTERNAL-DATA-INTEGRATION-ROADMAP.md)
Phase 5 (cross-referenced there, not duplicated) — a cloud-drive import
(that roadmap's Phase 6) is exactly the kind of slow, rate-limited fetch
this phase's async queue exists to decouple from the HTTP request cycle.

**Update (2026-08-06)**: also a soft dependency (worth having first, not
strictly required) for
[`docs/PRODUCT-DIFFERENTIATION-ROADMAP.md`](PRODUCT-DIFFERENTIATION-ROADMAP.md)
Phase 1 (citation highlighting/source viewer, which needs original file
bytes this phase would persist) and Phase 5 (usage/cost dashboards).

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

## Phase 8 — Resource-level authorization (RBAC/ABAC) ✅

**Done.** Decision: ABAC (owner + visibility + explicit-share-list), not
RBAC — matches this phase's own framing exactly: the "done when" below is
a per-document sharing question ("can this specific user see this specific
document"), a shape RBAC only answers by degrading into a role per
document-user pair. Full account:
[ADR 0046](adr/0046-resource-level-authorization-abac.md).

No new `vector_store` schema/migration needed: the model reuses the
`"userId"` metadata key `DocumentIngestionService` already stamped on every
chunk as the owner, and adds `"visibility"`/`"sharedWith"` keys to that same
existing metadata JSON — a Java-level check
(`DocumentVisibility.isVisibleTo`, `platform-common`, shared between
`ingestion-service` and `rag-service` so both agree on the exact same
string values) filters every retrieval path (hybrid search's vector and
full-text legs, and `DocumentLookupTool`'s exact-match lookup, Phase 9)
before RRF fusion runs. A new `PATCH /api/v1/documents/{documentId}/sharing`
endpoint (`ingestion-service`, owner-only) is the one write path — every
document still starts `TENANT`-visible at upload, unchanged from before
this phase.

**Done when, verified for real**: registered two real users into the
*same* tenant via the real invitation flow (ADR 0031), uploaded a document
as one, restricted it via the new endpoint, and confirmed against the
actual running stack that the other user's questions never retrieved a
citation for it while the owner still saw it — then shared it explicitly
and confirmed the other user could now see it too. Two real bugs found and
fixed by writing real tests, not assumed away: a missing `@PathVariable`
name (this build has no `-parameters` flag, so Spring couldn't infer it)
and a Postgres `uuid = character varying` type mismatch in the sharing
repository's `UPDATE` (`vector_store.id` is `uuid`, V1 migration).

**Extended (ADR 0047, `docs/ROADMAP.md` item #29)**: a per-tenant `ADMIN`
role (bootstrapped automatically — whoever creates a tenant becomes its
first ADMIN, with a Flyway backfill for tenants that already existed) that
may override the owner-only check above for any document in its own
tenant, list the tenant's members, and promote/demote them. `GET
/api/v1/documents` (admin-only) and a `web-ui` admin panel close the
"no listing/management UI" gap this phase originally left open.

## Phase 9 — Backups and disaster recovery ✅

**Done.** Full account: [ADR 0044](adr/0044-backups-and-disaster-recovery.md).
`scripts/backup-postgres.sh` (`pg_dumpall`, not `pg_dump`/`pg_basebackup` —
the only tool that recreates roles/databases/extensions from nothing, the
real disaster-recovery scenario, not just a same-instance snapshot) and
`scripts/restore-postgres.sh`. A retention policy is documented (last 7
daily local dumps, shipped off-host to whatever object storage Phase 3
introduces, reusing rather than inventing a second storage mechanism) but
not built into an automated schedule — no cron job or Kubernetes `CronJob`
runs these yet, a named remaining gap, not a claim of full automation.

**Verified for real, not just scripts that exist**: rather than a real
`docker compose down -v` against this session's own actual local
development environment (real risk — every test user and document created
this session would have been gone if the restore had failed), used a fully
isolated throwaway environment that proves the identical thing without that
risk:

1. Confirmed a real fact first: logged into the real running stack, asked a
   real question, got a real grounded answer citing a real document
   (`saga-notes.md`).
2. Took a real `pg_dumpall` backup of the real running Postgres (536K).
3. Created a **brand-new**, isolated Docker network and a fresh Postgres
   container, deliberately bootstrapped with *different* credentials than
   production — so only restoring the dump could make the real
   `ragplatform` role/database exist in it at all.
4. Restored the backup into it. Verified directly via `psql`: all 21 real
   `auth.users` rows and the real `saga-notes.md` `vector_store` row were
   present, from the backup alone.
5. Ran real `auth-service`/`rag-service` containers (the actual images this
   project builds) against that restored, isolated database.
6. Logged in for real against the drill's own `auth-service` as the same
   pre-existing user — got back a real JWT with the correct, original
   tenant/user IDs.
7. Asked the drill's own `rag-service` the identical real question as step
   1 — got back the identical grounded answer, same RRF score, citing
   `saga-notes.md` — from the restored copy alone.
8. Tore down every drill resource and confirmed the real Postgres was
   completely untouched throughout (same 21 users, same document, still
   there).

This satisfies the original "done when" in substance — a real backup,
restored alone into a genuinely different environment with no other way to
have that data, verified with a real question against a real pre-existing
document — while avoiding the literal `docker compose down -v` framing's
real, avoidable risk to this session's own local development data.

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
