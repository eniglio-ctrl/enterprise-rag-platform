# ADR 0021: Security hardening baseline

## Status
Accepted

## Context
With the functional roadmap complete (upload → indexing → answer/diagram,
multi-tenancy, observability, a live public demo — 20 ADRs and a real quality
benchmark), the next phase is a dedicated security hardening pass: turning the
project from "has JWT" into "validates uploaded content, resists abuse, protects
its own secrets, audits what happens, and deliberately limits its public demo."

This is a layered rollout across several small, independently-verifiable phases,
each landing as its own change with its own ADR where the decision is non-trivial:

1. Upload validation (magic-byte content verification, not just extension checks)
2. Rate limiting / abuse prevention
3. Secrets, CORS, and HTTP security headers
4. Tenant/invitation model and persistent JWT signing key
5. Security audit logging and monitoring
6. Public demo hardening

Before committing to this rollout, every premise behind it was checked against the
actual codebase rather than assumed, because two of the premises turned out to be
wrong and two more turned out to be intentional, already-documented decisions
rather than undiscovered bugs:

- **The build was not, in fact, broken.** `./mvnw clean verify` already passes
  cleanly across all four modules (11 test classes, 0 failures, 0 errors, per the
  existing Surefire reports). The "Mockito is currently self-attaching..." message
  seen in console output is a benign JVM-agent warning, not a test failure — no
  build fix is part of this rollout.
- **The `web-ui` Dockerfile healthcheck's use of `localhost`** (vs. `127.0.0.1`)
  has no demonstrated failure behind it — `nginx:alpine` resolves `localhost` via
  its own `/etc/hosts`, and the container listens on all interfaces. Not treated as
  a bug to fix, only an optional, non-blocking style tweak.
- **CORS (`platform-common`'s `CorsConfig`) is already reasonably scoped**: a
  single configurable origin (not a wildcard) and methods restricted to
  `GET`/`POST`. The one real gap is `allowedHeaders("*")`, a comparatively minor
  item folded into the secrets/headers phase rather than treated as a full CORS
  redesign.
- **The free-text `tenantId` at registration and `JwtKeyProvider`'s in-memory RSA
  keypair are deliberate, already-documented simplifications**, not bugs nobody
  noticed — see [ADR 0016](0016-auth-service-jwt-oauth2.md) and this project's
  README "known limitations" notes. The tenant/invitation phase of this rollout
  explicitly *supersedes* those decisions for a security-hardened posture; it does
  not "discover" a defect in them.

Everything else behind this rollout was independently confirmed true by reading
the code directly: uploads are validated by filename extension only (no content
verification), `IngestionProperties.allowedContentTypes` is configured but has
zero actual usages, there is no rate limiting anywhere in the codebase, the
`web-ui` nginx setup emits no security headers at all, and `docker-compose.yml`
ships real default credentials (`ragplatform`/`ragplatform`, and a literal
`admin` Grafana password with anonymous Viewer access enabled).

The previously discussed pivot toward a cybersecurity-content-focused assistant
("Sentinel AI" — MITRE ATT&CK/CVE/Sigma/YARA knowledge sources, Graph RAG over
CVE→CWE→technique relationships, specialized investigation/response agents) is
**explicitly out of scope for this rollout** — noted only as a possible future
direction, not planned or implemented here.

## Decision
Harden the existing platform in six independently-shippable phases, each with its
own "done when" criterion and, where the decision is non-trivial, its own ADR:

1. **Upload validation** — a hand-rolled magic-byte signature table (not Tika's
   own content-sniffing, which this codebase already documents as unreliable for
   plain-text formats) validates that uploaded bytes actually match their claimed
   extension and declared MIME type before any parser (Tika/PDFBox/Ollama/Whisper)
   ever touches them.
2. **Rate limiting** — a shared `platform-common` filter, with different limiting
   strategies per endpoint class (IP-based for login/register and the public demo,
   user/tenant-based for uploads and chat).
3. **Secrets, CORS, HTTP headers** — remove hardcoded default credentials from
   `docker-compose.yml`, tighten the one real CORS gap, add standard security
   headers (CSP, `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`)
   to `web-ui`'s nginx config.
4. **Tenants/invitations + persistent JWT key** — replace the free-text tenant
   model and in-memory signing key from ADR 0016 with a real `tenants` table,
   non-guessable tenant IDs, an invitation flow, and a configuration-backed
   signing key that survives restarts.
5. **Security audit logging + monitoring** — structured audit events (no
   secrets/document content) for auth and upload outcomes, new Micrometer
   metrics, a Grafana "Security" panel.
6. **Public demo hardening** — confirm the demo stays read-only, apply stricter
   rate limits and CSP, confirm no admin surface (Swagger/actuator) is exposed
   publicly.

## Consequences
- Each phase ships and is verified independently (`./mvnw clean verify` green,
  `docker compose up -d --build` healthy, a real manual test of the affected
  flow) rather than as one large, hard-to-review change.
- Phase 4 is a deliberate, documented supersession of ADR 0016's simplifications,
  not a retroactive claim that ADR 0016 was a mistake — the tenant model was the
  right amount of complexity for that stage of the project, and this phase is
  what "the next stage" looks like.
- This ADR intentionally does not claim to fix a broken build or a broken
  healthcheck — those premises were checked and found false before being written
  down anywhere as if they were real findings.
