# Security Hardening Roadmap

> This file is the single source of truth for the security hardening rollout —
> readable on its own, without needing any prior chat session or plan file.
> Update the status marker and "what was done" notes as each phase lands.

## Status at a glance

| Phase | What | Status |
|---|---|---|
| 0 | Baseline ADR | ✅ Done — [ADR 0021](adr/0021-security-hardening-baseline.md) |
| 1 | Upload content validation (magic bytes) | ✅ Done, one known gap open — [ADR 0022](adr/0022-upload-validation-hardening.md) |
| 2 | Rate limiting / abuse prevention | ✅ Done — [ADR 0028](adr/0028-rate-limiting.md) |
| 3 | Secrets, CORS, HTTP security headers | ✅ Done — [ADR 0029](adr/0029-secrets-cors-http-headers.md) |
| 4 | Tenants/invitations + persistent JWT key | ✅ Done — [ADR 0031](adr/0031-tenant-invitations-and-persistent-jwt-key.md) |
| 5 | Security audit logging + monitoring | ✅ Done — [ADR 0032](adr/0032-security-audit-logging-and-monitoring.md) |
| 6 | Public demo hardening | ✅ Done — [ADR 0033](adr/0033-public-demo-hardening.md) |
| 7 | Supply-chain security (secret scanning, dependency/CVE scanning) | ✅ Done — [ADR 0026](adr/0026-supply-chain-security-phase7.md) |

**On "done" claims in this file**: this project has been caught once already
overstating status without checking the actual repo (the corrected premises in
the Context section below). Don't trust a ✅ here on faith — the entries for
phases marked done link the exact ADR and list the exact files; re-verify with
`git log`, `ls`, and a fresh `./mvnw clean verify` before relying on a status
claim for anything that matters (an interview claim, a decision to build on
top of it, etc.). Re-verified for phases 0/1 on 2026-07-28, see the note under
Phase 1.

Execution order matters less between phases 2-7 than it did for phase 1 (which
had to come first, since it's the highest-value gap) — pick whichever is most
relevant next, but do them one at a time and verify each independently before
starting the next.

## Verification pattern (repeat for every phase)

1. `./mvnw clean verify` green across every module — run it fresh, don't
   assume a prior run still reflects the current tree.
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

## Context: why this rollout exists, and corrected premises

After the original 8-phase roadmap (Fase 0-7d) shipped in full — working
end-to-end pipeline, live public demo, 20 ADRs, a real quality benchmark — the
next request was a dedicated security hardening pass, so the project moves from
"has JWT" to "validates uploaded content, resists abuse, protects its own
secrets, audits what happens, and deliberately limits its public demo."

Before committing to the plan as originally proposed, every premise was checked
against the actual code (not assumed). Two turned out to be false and are
**not** part of this rollout:

- `./mvnw clean verify` was already green on this machine (the "Mockito is
  currently self-attaching..." line is a benign JVM-agent warning printed to
  stderr, not a test failure — confirmed by checking the actual exit code and
  the Surefire report counts, not just eyeballing console output. **This was
  re-checked again after a later review questioned it**: a fresh
  `./mvnw clean verify` run on 2026-07-28 exited `0`, and `auth-service`'s own
  Surefire reports show `Tests run: 5, Failures: 0, Errors: 0` and
  `Tests run: 3, Failures: 0, Errors: 0` for its two test classes. If a build
  environment genuinely fails here — a different JDK vendor/patch version can
  change whether the self-attach warning becomes a hard failure — that's a
  real, separate environment difference worth its own bug report with the
  exact JDK version and full stack trace, not an assumption carried into this
  file.
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

**Done.** `docs/adr/0021-security-hardening-baseline.md` registers the
layered rollout and the corrected premises above. Verify for yourself:
`ls docs/adr/0021-security-hardening-baseline.md` and `git log --oneline -3`
should show a commit titled "Add upload content validation via magic bytes
(Fase 0+1, ADR 0021/0022)".

## Phase 1 — Upload content validation ✅ (one known gap open)

**Done**, verified against this exact repository, not assumed: `git log`
shows commit `5e961e0` (message above) on `main`, already pushed and merged —
`git status` reports a clean tree with no local-only changes. The four new
classes exist and are non-empty:
`ingestion-service/src/main/java/com/eniglio/ragplatform/ingestion/service/{UploadValidationService,ValidatedUpload,DocumentKind}.java`
and
`ingestion-service/src/main/java/com/eniglio/ragplatform/ingestion/exception/InvalidUploadException.java`.
`DocumentReaderFactory.read(...)` takes a `ValidatedUpload`, not a
`MultipartFile` — confirmed by reading the method signature directly, not
inferred. GitHub Actions CI run for that commit: `success`.

See [ADR 0022](adr/0022-upload-validation-hardening.md) for full detail.
Summary: a new `UploadValidationService` in `ingestion-service` checks, before
any parser touches an upload — empty file → extension recognized → declared
MIME type allow-listed → declared MIME's implied kind matches the extension's
→ actual bytes match a hand-rolled magic-byte signature for that format. A new
`ValidatedUpload` record (bytes, filename, canonical MimeType, `DocumentKind`)
is the only input `DocumentReaderFactory` accepts now. A new
`InvalidUploadException` (422) is a sibling, not a subclass, of the existing
`UnsupportedDocumentTypeException` (415).

Verified: `./mvnw clean verify` green (29 new/updated tests), and manually
against the real running stack — genuine PDF/markdown/PNG/WAV uploads succeed
(including a real vision-model call for the PNG), a corrupted-content .pdf
returns 422, an unsupported .exe extension returns 415, and a declared MIME
type that's individually allow-listed but wrong for the extension (`.pdf` +
`audio/mpeg`) returns 422.

**Known gap, correctly flagged in review, not yet fixed**: the DOCX check only
confirms the file starts with a ZIP local-file-header signature
(`PK\x03\x04`). That proves it's *a* ZIP, not that it's a real DOCX — any
arbitrary ZIP renamed to `.docx` currently passes validation and reaches
`TikaDocumentReader`, which then either fails with an unhandled exception (a
generic 500, not the clean 422 this phase is supposed to guarantee) or, worse,
successfully parses whatever unexpected ZIP content Tika's auto-detection
finds inside. Two follow-ups needed here, tracked as unstarted work under this
phase rather than a new one (small enough to fold in): 1) after the ZIP
signature check, open the archive's central directory and confirm
`word/document.xml` (and ideally `[Content_Types].xml`) is present before
accepting it as DOCX; 2) guard against a zip-bomb-style upload (a small file
that decompresses to gigabytes, or an entry count high enough to exhaust
memory/CPU during Tika's parse) by capping total uncompressed size and entry
count read from the central directory *before* handing the archive to Tika —
the same reasoning already applied to the 25MB compressed-upload limit, just
also bounding the decompressed side, which that limit doesn't touch.

Files touched: `UploadValidationService.java`, `ValidatedUpload.java`,
`DocumentKind.java`, `InvalidUploadException.java` (all new),
`DocumentReaderFactory.java`, `DocumentIngestionService.java`,
`GlobalExceptionHandler.java`, `DocumentController.java`, `application.yml`
(all modified) — all under
`ingestion-service/src/main/java/com/eniglio/ragplatform/ingestion/`.

## Phase 2 — Rate limiting and abuse prevention ✅

**Done.** See [ADR 0028](adr/0028-rate-limiting.md) for the full decision
record — Bucket4j (not Resilience4j's own `RateLimiter`, wrong shape for
per-key buckets), a shared `RateLimitFilter` in `platform-common`, and a
real verification against the running stack (11 login attempts, the 11th
`429`; a forged `X-Forwarded-For` confirmed to have zero effect). The
original plan below is kept for the record; two of its bullets (request
body size cap, per-tenant concurrent-in-flight cap) were deliberately
scoped out of ADR 0028 as a separate follow-up, not part of "done when".

<details>
<summary>Original plan (kept for the record)</summary>



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
- **Document the limitation explicitly, don't hide it**: an in-memory limiter
  (e.g. Bucket4j's local buckets) is correct and sufficient for this project's
  actual deployment shape — a single instance per service, no horizontal
  scaling — but is *not* distributed. If a service is ever scaled to multiple
  replicas, each replica enforces its own independent limit, effectively
  multiplying the real ceiling by the replica count. State this in the phase's
  ADR as a known, accepted trade-off, not an oversight, exactly like ADR
  0002's shared-database simplification already does for a different
  component.
- **Never trust a client-supplied `X-Forwarded-For` directly for the
  public demo's per-IP limiting.** A visitor can set that header to anything,
  trivially defeating an IP-based limit if it's read naively. Only trust the
  IP forwarded by Render/Netlify's own edge (a known, trusted proxy hop) —
  either via the platform's dedicated header (e.g. Render's real client IP
  header) or by explicitly configuring Spring's
  `ForwardedHeaderFilter`/proxy-count settings so only the hop count matching
  the actual trusted proxy chain is honored, not an arbitrary chain length a
  client could forge by prepending fake entries.
- Add a request body size cap on the question/chat text itself (separate from
  the existing 25MB *file* upload cap) and a limit on concurrent in-flight
  calls to the LLM per tenant/instance — an attacker sending very long
  questions or many concurrent requests can exhaust Ollama/Groq capacity or
  memory even while staying under any per-minute rate limit.

**Done when**: repeated requests get `429` without taking down
Ollama/Whisper/Postgres; an integration test simulating N+1 requests from the
same IP/user confirms the block; a real `for i in {1..N}; do curl ...; done`
against the running stack shows the same thing; a forged
`X-Forwarded-For` header from a test client is confirmed to have zero effect
on which limit bucket a request lands in.

</details>

## Phase 3 — Secrets, CORS, HTTP security headers ✅

**Done.** See [ADR 0029](adr/0029-secrets-cors-http-headers.md) — verified
for real, including a live browser test of the diagram feature against the
new CSP (the exact risk this phase's original plan below called out).
Kept for the record:

- `docker-compose.yml`: remove the real default credentials
  (`ragplatform`/`ragplatform` DB creds repeated across 5 services; a literal
  `admin` Grafana password with anonymous Viewer access enabled) — require a
  local `.env` (`.env.example` already exists) instead of falling back to a
  real-looking default.
- **Update `README.md`'s local setup instructions to explicitly say
  `cp .env.example .env`** as the first step, before `docker compose up` —
  today the file exists but nothing in the README tells a new reader to copy
  it, so "no default credentials" would otherwise just mean "won't start" with
  no clear next action.
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
- **Test the CSP against the actual diagram feature before merging it**, not
  just the login/chat UI. `web-ui` renders Mermaid diagrams client-side —
  Mermaid's renderer has historically needed relaxed `script-src`/`style-src`
  (inline styles for SVG output, sometimes `unsafe-eval` depending on the
  version and render path) to work at all. A CSP tightened by copy-pasting a
  generic "secure defaults" policy without checking this can silently break
  diagram rendering with no visible error beyond a CSP violation in the
  browser console — verify by actually asking a diagram question against the
  running `web-ui` with the new CSP applied, in a real browser, checking the
  console for CSP violations, not just checking the headers exist via `curl`.

**Done when**: no real secret in git; `docker compose up` without a `.env`
fails with a clear message instead of silently using default credentials;
`curl -I` against `web-ui` shows the new security headers; a real diagram
question still renders correctly in the browser with zero CSP violations in
the console.

## Phase 4 — Tenants/invitations + persistent JWT signing key ✅

**Done.** See [ADR 0031](adr/0031-tenant-invitations-and-persistent-jwt-key.md)
— deliberately supersedes ADR 0016's caller-supplied free-text `tenantId` and
in-memory-only signing key, both explicitly flagged there as known
limitations. Kept for the record:

- Registering with no `invitationToken` now always creates a brand-new tenant
  with a non-guessable UUID id; there is no longer any way to join an
  existing tenant by typing its name. Registering with a token redeems it via
  the new `InvitationService`: single-use (enforced with an atomic
  `UPDATE ... RETURNING`, not a check-then-update a race could bypass), a
  7-day expiry checked at redemption time, and an exact email match.
- New `tenants`/`invitations` tables (`V2` migration). `tenants.id` stays
  `TEXT`, not `UUID` — every pre-existing free-text `tenant_id` value already
  in `users` gets backfilled into it as-is, which a native `UUID` column
  would have rejected outright.
- `JwtKeyProvider` now loads a persisted PKCS8 PEM RSA key from a mounted
  secret file (Kubernetes) or a Base64-encoded env var (docker-compose),
  falling back to the old ephemeral-key behavior only when neither is
  configured (tests). The JWKS `kid` is now a SHA-256 thumbprint of the key
  itself, not random, so it stays identical across restarts.
- `AuthSecurityConfig`'s `anyRequest().permitAll()` became an explicit
  allowlist; the new `POST /api/v1/auth/invitations` endpoint is the first
  one `auth-service` itself requires a bearer token for, validated in-process
  against the same key material that signs tokens (no self-HTTP-call to its
  own JWKS).

**Verified for real, not just by reasoning about the code**: against the live
docker-compose stack — registered a user, created an invitation, redeemed it
as a teammate (confirmed same `tenantId`), confirmed reusing the same
invitation returns 400, **restarted the real `auth-service` container**, and
reused the pre-restart token against the now-authenticated invitations
endpoint — `201`, not `401`. Confirmed via `psql` that the `V2` backfill left
zero orphaned users against 7 real pre-existing tenant IDs from earlier local
testing. Full account, including a browser-tooling caching gotcha hit while
verifying the new web-ui panel, in ADR 0031's Consequences section.

## Phase 5 — Security audit logging and monitoring ✅

**Done.** See [ADR 0032](adr/0032-security-audit-logging-and-monitoring.md). Kept
for the record:

- A new `CorrelationIdFilter` (`platform-common`), registered at the servlet
  container's highest precedence (not via `HttpSecurity`) so it runs before
  Spring Security entirely — every log line across every service now carries
  a `correlationId` field automatically via MDC, including ones Spring
  Security itself produces before this project's code runs. Propagated on
  the one inter-service HTTP call in the codebase (chat-service →
  rag-service's `/api/v1/retrieve`).
- New `AuditingAuthenticationEntryPoint`/`AuditingAccessDeniedHandler`:
  Spring Security's previously-silent default 401/403 now logs a structured
  audit line each (client IP via `ClientIpResolver`, made `public` for this).
- Login/registration success (email/tenantId/userId, never the password) and
  login failure (`security.authentication.failed`, deliberately untagged —
  a "reason" tag would leak the same unknown-email-vs-wrong-password
  distinction the response body already hides to resist enumeration) are
  logged in `auth-service`. Upload accept/reject (`security.upload.rejected`,
  tagged by reason) is logged in `ingestion-service`'s
  `UploadValidationService`.
- New "Segurança" row in the Grafana dashboard (3 panels: rate-limit-blocked
  by rule reusing Phase 2's existing metric, failed logins, rejected uploads
  by reason).

**A real, pre-existing bug found by this phase's own audit logging**:
`auth-service`'s allowlist from Phase 4 only covered `/actuator/health`, not
`/actuator/prometheus` — Prometheus had been silently failing to scrape
`auth-service` since Phase 4 shipped (confirmed via Prometheus's own
`/api/v1/targets` showing that target `down` with a 401). Invisible before
this phase because a rejected scrape logged nothing at all; the new
`AuditingAuthenticationEntryPoint` logging every 401 for real is what
surfaced it. Fixed by widening the allowlist to `/actuator/**`.

**Verified against the real docker-compose stack**: a real login failure and
a real rejected upload both produced non-zero counters at
`/actuator/prometheus`; the same `correlationId` appeared in both
chat-service's and rag-service's logs for one real chat message (crossing
the actual inter-service call in this codebase — the roadmap's own suggested
example, a cross-service rate-limit block, turned out not to be reproducible
since `/api/v1/retrieve` is deliberately excluded from rate limiting, already
documented in `rag-service`'s config); Grafana's dashboard renders the new
panels against real data, confirmed via a direct Prometheus query, not just
schema-correct JSON.

## Phase 6 — Public demo hardening ✅

**Done.** See [ADR 0033](adr/0033-public-demo-hardening.md) — the last phase in
this rollout. Kept for the record:

- **A real, live exposure check came first, not an assumption**: `curl` against
  the actual deployed URLs showed `/actuator/prometheus`, `/actuator/metrics`,
  `/v3/api-docs`, and Swagger UI all publicly reachable (200/302), alongside the
  intentionally-public `/actuator/health`. `rag-service/application-demo.yml` now
  overrides `management.endpoints.web.exposure.include` to `health` only and
  disables `springdoc.api-docs`/`springdoc.swagger-ui` entirely.
- Rate limit tightened from 30/min to 10/min per IP on the demo specifically — a
  public URL paying real per-question Groq/Mistral API cost gets a lower ceiling
  than the free local Ollama path.
- **`trusted-proxy-hops` was researched, not left at a guess**: Render's own
  community has an open, unresolved report of inconsistent `X-Forwarded-For`
  behavior on their platform. Decision: stays `0` — trusting a specific hop count
  on a disputed foundation would be a worse basis for a trust boundary than the
  conservative default already in place. This corrects the original plan's framing
  ("more aggressive... never trust raw X-Forwarded-For" implied a hop count would
  simply be confirmed) — the honest finding is that it isn't safely confirmable
  from public information today.
- New `web-ui/_headers` (Netlify's native header mechanism — `web-ui/nginx.conf`,
  ADR 0029, only applies to the docker-compose build and never runs on Netlify)
  gives the demo its own CSP, scoped to the one real backend it calls
  (`https://ag-service-demo.onrender.com`), not the local dev ports.
- Read-only reconfirmed explicitly in the new ADR, not just inherited from ADR
  0020's original context.
- **A real, unrelated bug found while updating this file**: the status table above
  still said "Not started" for Phase 5 after Phase 5's own commit landed — the
  phase's detail section was updated but this table row wasn't. Fixed here as
  well, a small reminder that "done" claims in this file need re-checking, per the
  warning below the table.

**Done when**: the public URL only ever answers questions about the seeded demo
documents, with real abuse limits, and no admin surface reachable — verified via
the same `curl` checks against the live URLs, re-run after this phase's changes
deployed.

## Phase 7 — Supply-chain security ✅

**Done.** See [ADR 0026](adr/0026-supply-chain-security-phase7.md) for the
full decision record, including a real config mistake found and fixed (a
redundant per-module Dependabot entry) and how the 33 real PRs it opened on
first run were triaged.

> **Not the same thing as SonarQube** — CodeQL/Dependabot below are
> security-focused (vulnerable dependencies, insecure code patterns);
> code-quality/maintainability/test-coverage analysis (SonarQube/SonarCloud)
> is tracked separately in
> [`docs/MULTI-LLM-ORCHESTRATOR-ROADMAP.md`](MULTI-LLM-ORCHESTRATOR-ROADMAP.md)'s
> Phase 14, to avoid the same tooling gap being tracked (and potentially
> implemented) twice in two different roadmap files.

- **Secret scanning** on the GitHub repo (GitHub's own built-in secret
  scanning, or `gitleaks` as a pre-commit hook / CI step) — catches an
  accidentally-committed API key or credential before or shortly after it
  lands in git history, complementing (not replacing) Phase 3's removal of
  hardcoded defaults.
- **Dependency vulnerability scanning**: enable Dependabot security updates
  and version-update PRs for Maven (`pom.xml` across all 5 modules) and for
  the GitHub Actions workflows themselves (`.github/workflows/ci.yml`'s pinned
  action versions).
- **CodeQL** added as a GitHub Actions workflow (`.github/workflows/codeql.yml`)
  for static analysis on every push/PR to `main`, using the `java` language
  pack given this is an all-Java backend.

**Done when** (all confirmed, not assumed): Dependabot opened at least one
real PR proving it's wired up — it opened 33 on the first run, 15 of which
passed CI for real and were merged (`./mvnw clean verify` green afterward
across all 5 modules), 14 were genuine major-version breaks closed with
`ignore` rules added so they don't reopen weekly, and 4 Docker JRE bumps
were deliberately left open pending a real `docker build` test since
`ci.yml` doesn't exercise Docker images at all. A CodeQL run completed and
is visible in the repo's Actions history. Secret scanning + push protection
were already `enabled` on the repo (checked via `gh api`, GitHub's default
for public repos) — no dummy-secret test was needed to prove a already-
verified GitHub platform default.
