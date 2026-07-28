# Security Hardening Roadmap

> This file is the single source of truth for the security hardening rollout —
> readable on its own, without needing any prior chat session or plan file.
> Update the status marker and "what was done" notes as each phase lands.

## Status at a glance

| Phase | What | Status |
|---|---|---|
| 0 | Baseline ADR | ✅ Done — [ADR 0021](adr/0021-security-hardening-baseline.md) |
| 1 | Upload content validation (magic bytes) | ✅ Done — [ADR 0022](adr/0022-upload-validation-hardening.md) |
| 2 | Rate limiting / abuse prevention | ⬜ Not started |
| 3 | Secrets, CORS, HTTP security headers | ⬜ Not started |
| 4 | Tenants/invitations + persistent JWT key | ⬜ Not started |
| 5 | Security audit logging + monitoring | ⬜ Not started |
| 6 | Public demo hardening | ⬜ Not started |

Execution order matters less between phases 2-6 than it did for phase 1 (which
had to come first, since it's the highest-value gap) — pick whichever is most
relevant next, but do them one at a time and verify each independently before
starting the next.

## Verification pattern (repeat for every phase)

1. `./mvnw clean verify` green across every module.
2. `docker compose up -d --build <changed-service>` — container comes up
   `healthy`.
3. A real manual test of the affected behavior via `curl`/browser against the
   running stack — not just the automated test suite.
4. An ADR for the phase, written and linked from both `README.md` and this
   file's status table.
5. Commit + push; update this file's status table and the phase's own section
   below with a one-paragraph "what actually happened" note (bugs found, any
   scope adjustment) — mirroring how every other ADR in this project has an
   "Update" section when reality diverged from the original plan.

## Context: why this rollout exists, and two corrected premises

After the original 8-phase roadmap (Fase 0-7d) shipped in full — working
end-to-end pipeline, live public demo, 20 ADRs, a real quality benchmark — the
next request was a dedicated security hardening pass, so the project moves from
"has JWT" to "validates uploaded content, resists abuse, protects its own
secrets, audits what happens, and deliberately limits its public demo."

Before committing to the plan as originally proposed, every premise was checked
against the actual code (not assumed). Two turned out to be false and are
**not** part of this rollout:

- `./mvnw clean verify` was already green (the "Mockito is currently
  self-attaching..." message is a benign warning, not a test failure).
- The `web-ui` Dockerfile healthcheck's use of `localhost` (vs `127.0.0.1`) has
  no demonstrated failure behind it.

Two more are deliberate, already-documented decisions being *superseded*, not
undiscovered bugs — see [ADR 0016](adr/0016-auth-service-jwt-oauth2.md) and
phase 4 below:

- The free-text `tenantId` at registration.
- `JwtKeyProvider`'s in-memory RSA signing key (regenerated on every restart).

Full detail on all of the above is in [ADR 0021](adr/0021-security-hardening-baseline.md).

---

## Phase 0 — Baseline ADR ✅

**Done.** `docs/adr/0021-security-hardening-baseline.md` registers the six-phase
layered rollout and the corrected premises above.

## Phase 1 — Upload content validation ✅

**Done.** See [ADR 0022](adr/0022-upload-validation-hardening.md) for full
detail. Summary: a new `UploadValidationService` in `ingestion-service` checks,
before any parser touches an upload — empty file → extension recognized →
declared MIME type allow-listed → declared MIME's implied kind matches the
extension's → actual bytes match a hand-rolled magic-byte signature for that
format. A new `ValidatedUpload` record (bytes, filename, canonical MimeType,
`DocumentKind`) is the only input `DocumentReaderFactory` accepts now. A new
`InvalidUploadException` (422) is a sibling, not a subclass, of the existing
`UnsupportedDocumentTypeException` (415).

Verified: `./mvnw clean verify` green (29 new/updated tests), and manually
against the real running stack — genuine PDF/markdown/PNG/WAV uploads succeed
(including a real vision-model call for the PNG), a corrupted-content .pdf
returns 422, an unsupported .exe extension returns 415, and a declared MIME
type that's individually allow-listed but wrong for the extension (`.pdf` +
`audio/mpeg`) returns 422.

Files touched: `UploadValidationService.java`, `ValidatedUpload.java`,
`DocumentKind.java`, `InvalidUploadException.java` (all new),
`DocumentReaderFactory.java`, `DocumentIngestionService.java`,
`GlobalExceptionHandler.java`, `DocumentController.java`, `application.yml`
(all modified) — all under
`ingestion-service/src/main/java/com/eniglio/ragplatform/ingestion/`.

## Phase 2 — Rate limiting and abuse prevention ⬜

**Not started.** Plan:

- New `platform-common/src/main/java/com/eniglio/ragplatform/common/security/RateLimitFilter.java`
  + `RateLimitProperties.java` — shared across `auth-service`,
  `ingestion-service`, `rag-service`/`chat-service`, since none of them
  currently have any rate limiting at all (confirmed: repo-wide grep for
  `RateLimit`/`Bucket4j`/`attempts`/`lockout`/`throttle`/`brute` returns zero
  auth-related hits).
- Differentiated rules per endpoint class:
  - Login/register (`auth-service`) — limit by IP.
  - Upload (`ingestion-service`) — limit by authenticated user.
  - `/ask`/chat/diagram (`rag-service`/`chat-service`) — limit by user/tenant.
  - Public demo — limit by IP (reused again, more aggressively, in Phase 6).
- `429 Too Many Requests` responses with a `Retry-After` header.
- A new Micrometer metric (`security.rate_limit.blocked`) and a Grafana panel
  entry (reused fully in Phase 5).

**Done when**: repeated requests get `429` without taking down
Ollama/Whisper/Postgres; an integration test simulating N+1 requests from the
same IP/user confirms the block; a real `for i in {1..N}; do curl ...; done`
against the running stack shows the same thing.

## Phase 3 — Secrets, CORS, HTTP security headers ⬜

**Not started.** Plan:

- `docker-compose.yml`: remove the real default credentials
  (`ragplatform`/`ragplatform` DB creds repeated across 5 services; a literal
  `admin` Grafana password with anonymous Viewer access enabled) — require a
  local `.env` (`.env.example` already exists) instead of falling back to a
  real-looking default.
- Grafana admin password via environment variable, anonymous access kept only
  for local/dev use.
- `platform-common/src/main/java/com/eniglio/ragplatform/common/web/CorsConfig.java`:
  tighten `allowedHeaders("*")` → `Authorization, Content-Type` (the one real
  gap — origin is already a single configurable value, not a wildcard, and
  methods are already limited to GET/POST).
- New `web-ui/nginx.conf` (none exists today — `web-ui/Dockerfile` runs stock
  `nginx:alpine` with no custom config) adding `Content-Security-Policy`,
  `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
  `Referrer-Policy`; wire it into `web-ui/Dockerfile`.

**Done when**: no real secret in git; `docker compose up` without a `.env`
fails with a clear message instead of silently using default credentials;
`curl -I` against `web-ui` shows the new security headers.

## Phase 4 — Tenants/invitations + persistent JWT signing key ⬜

**Not started.** This phase **deliberately supersedes** ADR 0016's
simplifications — the new ADR for this phase should say so explicitly, not
frame it as fixing a bug nobody noticed. Plan:

- `auth-service`'s `RegisterRequest`/`AuthService` stop accepting a free-text
  `tenantId` — first registration auto-creates an organization with a
  non-guessable ID (e.g. UUID); joining an existing tenant requires an
  invitation.
- New `TenantRepository`, `InvitationRepository`, Flyway migration
  `auth-service/src/main/resources/db/migration/V2__*.sql` (today only
  `V1__users.sql` exists — `tenant_id` is a free `TEXT` column, no FK, no
  `tenants` table).
- `JwtKeyProvider` (`auth-service/src/main/java/com/eniglio/ragplatform/auth/security/JwtKeyProvider.java`)
  stops generating an RSA keypair in memory at startup — reads a persistent
  key from configuration/secret instead. `AuthProperties` gains a field for it.
- `AuthSecurityConfig`'s `anyRequest().permitAll()` becomes an explicit
  allowlist (`/api/v1/auth/register`, `/api/v1/auth/login`,
  `/.well-known/jwks.json`, `/actuator/health`).

**Done when**: nobody joins an existing tenant just by typing its name; tokens
issued before an `auth-service` restart are still valid after one (verified by
actually restarting the container and reusing an old token).

## Phase 5 — Security audit logging and monitoring ⬜

**Not started.** Plan:

- Structured audit events (never logging passwords/tokens/document content):
  login success/failure, registration, upload accepted/rejected, rate limit
  triggered, access denied.
- New Micrometer metrics: `security.authentication.failed`,
  `security.upload.rejected`, `security.rate_limit.blocked` (the last one
  shared with Phase 2).
- New Grafana "Security" panel in
  `observability/grafana/dashboards/rag-platform-overview.json`.

**Done when**: the Grafana dashboard can show blocked attempts, invalid
uploads, and failed logins for a real demo/screenshot.

## Phase 6 — Public demo hardening ⬜

**Not started.** Plan:

- Reconfirm the demo (`web-ui-rag.netlify.app` + `ag-service-demo.onrender.com`)
  stays read-only — already true per ADR 0020, make it explicit in this
  phase's ADR too.
- More aggressive per-IP rate limit on the demo specifically (reuses Phase 2's
  filter).
- A tighter CSP specifically for the demo's static `config.js`.
- Confirm Swagger/actuator/metrics aren't publicly exposed on the Render
  deployment (check `management.endpoints.web.exposure` under the "demo"
  profile).
- Update `docs/DEMO-DEPLOYMENT.md` with this hardening.

**Done when**: the public URL only ever answers questions about the seeded demo
documents, with real abuse limits, and no admin surface reachable.
