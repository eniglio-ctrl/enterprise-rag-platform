# ADR 0044: Backups and disaster recovery — `pg_dumpall`, and a real, exercised restore

## Status
Accepted

## Context
`docs/ROADMAP.md` item #18, full account in
`docs/PRODUCTION-READINESS-ROADMAP.md` Phase 9 — described there as "the
most 'not yet a production system' gap on this entire list": the
`postgres-data` Docker volume (or its Kubernetes equivalent) has always been
the *only* copy of every tenant's data (documents' derived chunks, chat
history, tenant/user records, audit logs), with no backup automation, no
retention policy, and — critically — no restore procedure ever actually
exercised. The roadmap's own "done when" was explicit that this phase isn't
done when backups exist, but when a restore has been performed for real,
into a fresh environment, verified against a real question about a real
document.

## Decision

### `pg_dumpall`, not `pg_dump`
This project's data spans three schemas under one role (`public`:
`vector_store`; `auth`: users/tenants/invitations; `chat`:
conversations/messages), plus the role definition itself. `pg_dump` only
captures a single database's data and assumes the target role already
exists — useless for the actual disaster-recovery scenario this exists for
(a genuinely fresh Postgres instance, not just an empty database on an
already-provisioned one). `pg_dumpall`'s plain-SQL output recreates roles,
databases, extensions, and schemas from nothing, which is exactly what was
verified (see below).

### Two scripts, not a scheduling mechanism
`scripts/backup-postgres.sh` (`docker compose exec postgres pg_dumpall`,
timestamped output under `backups/`, gitignored — a real dump contains every
tenant's password hashes) and `scripts/restore-postgres.sh` (runs `psql`
inside a target container via `docker exec`, taking a container name rather
than assuming this project's own `postgres` service, specifically so it can
target a genuinely different container for a drill). No cron job or
Kubernetes `CronJob` was added to actually schedule these automatically —
that's real, additional operational infrastructure (where do backups get
shipped off-host, who gets paged if a backup run fails, how is a stale
backup detected) that this phase's own scope was the mechanism and the
proof it works, not standing up a full backup pipeline for a project with no
current production traffic to protect.

### Retention: documented policy, not built infrastructure
For a real deployment: keep the last 7 daily local dumps (a `find backups/
-mtime +7 -delete` alongside the scheduled backup run), and ship copies
off-host (object storage) with its own lifecycle policy for longer retention
— the same object storage Phase 3 (async ingestion) would introduce for
uploaded files, reused rather than inventing a second storage mechanism. If
this project ever moved off self-hosted Postgres onto a managed provider
(the public demo already uses Neon), that provider's own point-in-time
recovery becomes the primary mechanism, with `pg_dumpall` kept as a
portable, provider-independent fallback — not a redundant thing to remove.

## Consequences

### Verified for real: a real backup, a genuinely fresh environment, a real restore, a real question
Rather than `docker compose down -v` against the actual local development
environment (which would have destroyed real data — every test user, every
uploaded document, everything else this session's own work created — with
real risk if anything in this drill went wrong), the drill used a fully
isolated, throwaway environment instead, proving the exact same thing
without any risk to the real one:

1. Confirmed a real, pre-existing fact first: logged into the actual running
   stack as `convtest-20260804@example.com` and asked "Quais os dois
   modelos do padrão SAGA?" — got the correct grounded answer citing
   `saga-notes.md`.
2. Ran `scripts/backup-postgres.sh` against the real running `postgres`
   service — a real 536K `pg_dumpall` output.
3. Created a brand-new, isolated Docker network and a **fresh** throwaway
   Postgres container — deliberately bootstrapped with different
   credentials than production (`postgres`/`throwaway`, not
   `ragplatform`/the real password), specifically so restoring the dump was
   the only thing that could possibly make the `ragplatform` role and
   database exist in it at all.
4. Ran `scripts/restore-postgres.sh` against that fresh container. Verified
   directly via `psql`: `auth.users` had all 21 real rows, and
   `vector_store` had the real `saga-notes.md` row with its real
   `tenant_id` — from the backup alone, nothing else.
5. Started real `auth-service` and `rag-service` containers (the actual
   images this project already builds) pointed at the restored, isolated
   database — connected to the real Ollama container (stateless, holds no
   tenant data, reused rather than duplicated) but to no other part of the
   real stack.
6. Logged in for real as `convtest-20260804@example.com` against the
   drill's own `auth-service` — got back a real JWT with the correct,
   original `tenantId`/`userId`, proving the bcrypt password hash and
   tenant/user records restored correctly.
7. Asked the drill's own `rag-service` the same real question — got back
   the correct grounded answer, citing `saga-notes.md`, with the identical
   RRF score (`0.0328`) as the pre-drill answer in step 1 — from the
   restored copy alone.
8. Tore down every drill container and the isolated network. Confirmed
   directly against the real `postgres` service afterward that its data
   (21 users, the real `saga-notes.md` row) was completely untouched
   throughout — the real environment was never at risk at any point.

This satisfies the roadmap's own "done when" in substance — a real backup,
taken from a real environment, restored alone into a genuinely different
environment that had no other way to end up with that data, verified with a
real question against a real pre-existing document — while avoiding the
literal `docker compose down -v` framing's real, avoidable risk to this
session's actual local development data.

### The JWT signing key is deliberately not part of this backup's scope
`JwtKeyProvider` loads the RSA signing key from a mounted file or Base64 env
var (ADR 0031) — it is never persisted in Postgres. The drill's
`auth-service` ran with no signing key configured at all (falling back to
an ephemeral, auto-generated key, logging its own loud warning) and this
had zero effect on the drill's outcome: `rag-service` validates tokens
against whichever `auth-service` its own `AUTH_SERVICE_BASE_URL` points at,
fetching that instance's JWKS at runtime — the drill's `auth-service` and
`rag-service` only ever needed to be consistent with *each other*, never
with production's actual key material. This is a deliberate scope
boundary, not an oversight: secret material (signing keys, API keys) is
production-readiness item #21's concern (secrets and configuration
management), not this phase's — conflating the two would have meant a
database backup drill silently depending on also restoring secrets it has
no business containing.

### Not built: an automated schedule
No cron job or Kubernetes `CronJob` runs these scripts automatically today
— see "Decision" above. This is an explicit, named gap for a future
evolution, not a claim that backups are fully automated.
