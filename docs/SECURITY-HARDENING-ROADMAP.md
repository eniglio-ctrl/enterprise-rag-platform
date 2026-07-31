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
| 4 | Tenants/invitations + persistent JWT key | ⬜ Not started |
| 5 | Security audit logging + monitoring | ⬜ Not started |
| 6 | Public demo hardening | ⬜ Not started |
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

## Phase 4 — Tenants/invitations + persistent JWT signing key ⬜

**Not started.** This phase **deliberately supersedes** ADR 0016's
simplifications — the new ADR for this phase should say so explicitly, not
frame it as fixing a bug nobody noticed.

**Define the invitation model precisely before writing any code** (this was
originally underspecified here — a specific enough model to implement
directly, not "add an invitation flow"):

- An invitation is a row referencing a specific `tenantId` and a specific
  invited email address — not a generic, reusable "join code."
- It has an expiration (e.g. 7 days) checked at redemption time, not just at
  creation.
- It is single-use: redeeming it (successfully completing registration
  through it) invalidates it for any further use, enforced with a DB-level
  guard (a `redeemed_at`/status column checked and set atomically), not just
  application-level logic that a race condition could bypass.
- Registration through an invitation must use the exact invited email — a
  token that grants "join this tenant as anyone" instead of "join this tenant
  as this specific person" defeats the point of tying it to an email at all.

Rest of the plan:

- `auth-service`'s `RegisterRequest`/`AuthService` stop accepting a free-text
  `tenantId` — first registration (no invitation token) auto-creates an
  organization with a non-guessable ID (e.g. UUID); every subsequent user for
  that tenant must go through the invitation model above.
- New `TenantRepository`, `InvitationRepository`, Flyway migration
  `auth-service/src/main/resources/db/migration/V2__*.sql` (today only
  `V1__users.sql` exists — `tenant_id` is a free `TEXT` column, no FK, no
  `tenants` table).
- `JwtKeyProvider` (`auth-service/src/main/java/com/eniglio/ragplatform/auth/security/JwtKeyProvider.java`)
  stops generating an RSA keypair in memory at startup. **Prefer a key
  supplied as a mounted secret file or a Base64-encoded environment variable
  over generating one anywhere at deploy time** — generating "once, at first
  deploy" still needs somewhere durable to persist the result, and a
  mounted-secret/env-var approach sidesteps that entirely by treating key
  material the same way any other secret (DB password, API key) is already
  handled in this project, with the same `.env`/Kubernetes-secret mechanism
  Phase 3 and the existing `kubernetes/base/.env.secret` pattern already use.
  `AuthProperties` gains a field for the key material or its file path.
- `AuthSecurityConfig`'s `anyRequest().permitAll()` becomes an explicit
  allowlist (`/api/v1/auth/register`, `/api/v1/auth/login`,
  `/.well-known/jwks.json`, `/actuator/health`).

**Done when**: nobody joins an existing tenant just by typing its name; an
invitation can't be redeemed twice (verified by actually trying it twice) or
after it expires (verified with a manually-expired row, not just reasoning
about the code); tokens issued before an `auth-service` restart are still
valid after one (verified by actually restarting the container and reusing an
old token).

## Phase 5 — Security audit logging and monitoring ⬜

**Not started.** Plan:

- Structured audit events (never logging passwords/tokens/document content):
  login success/failure, registration, upload accepted/rejected, rate limit
  triggered, access denied.
- **Every audit event carries a request/correlation ID**, generated once per
  inbound request (a filter early in the chain, reused across services the
  same request touches, e.g. via a propagated header) and included in every
  structured log line and audit event for that request. This is what turns
  "we log security events" into "we can actually reconstruct what happened
  during an incident" — without it, correlating a rate-limit block in one
  service with the login failure that preceded it means grepping timestamps
  and hoping, which doesn't hold up under any real investigation.
- New Micrometer metrics: `security.authentication.failed`,
  `security.upload.rejected`, `security.rate_limit.blocked` (the last one
  shared with Phase 2).
- New Grafana "Security" panel in
  `observability/grafana/dashboards/rag-platform-overview.json`.

**Done when**: the Grafana dashboard can show blocked attempts, invalid
uploads, and failed logins for a real demo/screenshot; a single request ID
can be grepped across at least two services' logs for one real, deliberately
triggered failure (e.g. a blocked rate-limit request that also shows up in an
upstream service's log) and the full path is reconstructable from it alone.

## Phase 6 — Public demo hardening ⬜

**Not started.** Plan:

- Reconfirm the demo (`web-ui-rag.netlify.app` + `ag-service-demo.onrender.com`)
  stays read-only — already true per ADR 0020, make it explicit in this
  phase's ADR too.
- More aggressive per-IP rate limit on the demo specifically (reuses Phase 2's
  filter and its trusted-proxy IP resolution — never the public demo's raw
  `X-Forwarded-For`).
- A tighter CSP specifically for the demo's static `config.js`.
- Confirm Swagger/actuator/metrics aren't publicly exposed on the Render
  deployment (check `management.endpoints.web.exposure` under the "demo"
  profile).
- Update `docs/DEMO-DEPLOYMENT.md` with this hardening.

**Done when**: the public URL only ever answers questions about the seeded demo
documents, with real abuse limits, and no admin surface reachable.

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
