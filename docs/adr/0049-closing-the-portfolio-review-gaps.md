# ADR 0049: Closing the three gaps a portfolio review flagged, and declaring scope

## Status
Accepted

## Context
After the Vault/secrets-management phase (ADR 0048) closed the last open
item of `docs/PRODUCTION-READINESS-ROADMAP.md`, the user reviewed the
project and asked for three specific, concrete fixes before declaring it
finished — not a survey of everything that could theoretically be
improved, three named findings:

1. `AuthService.register()` isn't transactional — a failure partway
   through (the user insert failing after an invitation was already
   redeemed, or after a new tenant was already created) leaves that side
   effect permanently stuck with no user ever created.
2. `ChatRequest.question` and `SendMessageRequest.message` accept
   unbounded text — rate limiting (Security Phase 2) throttles request
   *volume*, not the size of any single request, so one oversized question
   could still pressure embeddings, the database, and the LLM regardless
   of how few requests a caller sends.
3. The public Netlify demo (`web-ui-rag.netlify.app`) still serves a stale
   build — a gap ADR 0033 already found and left explicitly unresolved for
   lack of dashboard access at the time.

The user was equally explicit about what's *not* in scope here: an async
ingestion queue + object storage, an API gateway, OpenTelemetry, Redis, and
any new agent/product work are all real, legitimate future evolution, not
defects in the RAG platform as it stands — closing this ADR does not mean
those stop being worth doing later, only that none of them block calling
this specific project done. The user also asked, explicitly, that the
project be described as **"production-minded"** or **"ready for
production evolution"** going forward, not **"production-ready complete"**
— ADR 0048 already said this about Vault specifically (dev mode, one
secret migrated); this makes it the project's general framing.

## Decision

### 1. `@Transactional` on `AuthService.register()`
One annotation. `UserRepository`, `TenantRepository`, and
`InvitationRepository` all share the same `JdbcTemplate`/`DataSource`
(auth-service has never used JPA), so Spring Boot's auto-configured
`DataSourceTransactionManager` wraps the whole method in one transaction
with zero other config needed — confirmed by grep that no
`@EnableTransactionManagement` or explicit transaction manager bean exists
anywhere in the project; none was needed.

**Verified for real, not just annotated**: a new
`AuthServiceTransactionIT` mocks only `UserRepository` (via
`@MockitoBean`) to throw on `create(...)`, while `TenantRepository` and
`InvitationRepository` stay real against a genuine Testcontainers
Postgres. Two cases: (a) an invitation seeded directly via SQL is
confirmed still `redeemed_at IS NULL` after the forced failure — the
invitation was never actually lost; (b) on the no-invitation path, the
total `tenants` row count is unchanged before and after the forced
failure — no orphaned tenant survives. Both assertions read real
committed state via a separate `JdbcTemplate` query after the HTTP call
returns, not in-memory state — a bug in the rollback boundary would show
up as a real row in the database, not just an uncaught exception.

### 2. `@Size(max = 8000)` on the two free-text fields
`ChatRequest.question` (rag-service, shared by `/api/v1/ask`,
`/api/v1/chat`, `/api/v1/diagrams`, `/api/v1/retrieve`) and
`SendMessageRequest.message` (chat-service) both get the same limit — one
consistent number across both places a user types a question, not two
different tunable knobs for what's conceptually the same kind of input.
8000 characters is the low end of the 8-16k range the user suggested:
generous for a real question (even one with a pasted code snippet), firm
enough to reject something document-sized. `spring-boot-starter-validation`
was already a dependency of both modules; both `GlobalExceptionHandler`s
already had a `MethodArgumentNotValidException` → 400 handler wired up
(exercised already for `@NotBlank`) — this is the same mechanism, one more
constraint on the same field, not a new validation pathway.

Deliberately **not** touched: `ChatController`'s multipart
`askWithImage` endpoint (`/api/v1/ask` with an attached image) takes its
`question` as a bare `@RequestParam`, not through `ChatRequest` — adding a
limit there would need `@Validated` on the controller class and a new
`ConstraintViolationException` handler (parameter-level Bean Validation
throws a different exception than body-level `@Valid` does), a second
pattern this codebase doesn't have anywhere yet. The user's own report
named `ChatRequest` and conversation messages specifically; out of scope
here, worth a follow-up on its own.

**Verified for real**: new tests in both existing Testcontainers ITs
(`ChatQueryIT.rejectsAQuestionLongerThanTheSizeLimitWith400`,
`ConversationIT.sendingAMessageLongerThanTheSizeLimitReturnsBadRequest`)
post an 8001-character string and assert `400`, via the real HTTP path
through real Bean Validation, not a unit test of the annotation in
isolation.

### 3. The Netlify demo: reported, not silently worked around
Checked live before assuming anything: `curl -D -` against
`https://web-ui-rag.netlify.app/` right now still returns the
pre-Security-Phase-4 `register-tenant` field in the HTML and none of
`_headers`'s CSP/`X-Content-Type-Options`/`X-Frame-Options`/
`Referrer-Policy` headers — the exact gap ADR 0033 already found, still
live, unresolved since. This machine has no Netlify CLI, no Node.js, and
no stored Netlify credentials of any kind — there is no technical path to
trigger the redeploy from here, and doing so would in any case be an
action against a live public site requiring the user's own explicit
action, not something to route around with a workaround. Presented the
exact dashboard steps (`Site settings > Build & deploy` → confirm GitHub
connection and Continuous Deployment are on → `Deploys` tab → "Trigger
deploy"); the user chose to do this themselves rather than hand over
credentials for no real benefit.

## Consequences

### What "production-minded" means for this project going forward
Per the user's explicit instruction: this project is described as
**production-minded** / **ready for production evolution**, not
**production-ready complete**. The distinction is real, not just a
softer word choice — Vault runs in dev mode (ADR 0048's own stated
limitation), only one secret is migrated to it, and a handful of Tier 3
roadmap items (async ingestion + object storage, an API gateway,
OpenTelemetry, Redis, further agent work) remain deliberately unstarted,
each requiring a real cost or commitment decision the user hasn't made.
None of that is a defect in the current scope; it's the honest boundary
of what "portfolio-complete" was ever meant to claim.

### Scope declared closed
With items 1 and 2 fixed and verified, and item 3 handed to the user with
exact steps (their own call, not a blocker on the code), this ADR closes
the punch list from this review. `docs/ROADMAP.md`'s "Portfolio-ready
stopping point" already said Tiers 1-2 make this a complete, deliverable
portfolio project; this ADR is the record of the last real findings raised
against that claim being addressed before it's treated as settled.
