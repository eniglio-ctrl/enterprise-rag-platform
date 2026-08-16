# ADR 0032: Security audit logging and monitoring

## Status
Accepted

## Context
`docs/SECURITY-HARDENING-ROADMAP.md` Phase 5. Every prior security phase (rate
limiting, upload validation, tenant invitations) already produces real events worth
knowing about — a blocked request, a rejected upload, a failed login — but nothing
tied them together. Correlating "this login failed" in `auth-service`'s log with "this
request also got rate-limited" in the same trace meant grepping timestamps and hoping,
which doesn't hold up under a real incident investigation. This phase closes that gap:
a shared correlation ID across every service, structured audit log lines for the
events that matter, two new Micrometer metrics, and a Grafana panel to see them.

## Decision

### Correlation ID
- New `CorrelationIdFilter` (`platform-common`, package `common.logging`): reads an
  inbound `X-Request-Id` header if the caller already set one, otherwise generates a
  UUID, puts it in SLF4J's `MDC`, echoes it back as a response header, and clears MDC
  in a `finally` block. Every service's `logging.structured.format.console: ecs`
  setting (already configured everywhere, unrelated to this phase) automatically
  serializes current MDC entries into every structured log line — no per-call
  plumbing needed once the filter runs.
- **Registered directly with the servlet container at `Ordered.HIGHEST_PRECEDENCE`
  via a `FilterRegistrationBean`, not added to `HttpSecurity`** like every other
  filter in this project (`RateLimitFilter`). This is deliberate: it needs to run
  before Spring Security's entire `FilterChainProxy`, in every profile (real JWT
  validation or the demo's synthetic tenant), so the ID is already in MDC even for
  requests Spring Security itself rejects before this project's own filters ever run
  (a missing or expired bearer token, for instance).
- **Propagated on the one inter-service HTTP call in the whole codebase**:
  `RagServiceGateway.retrieve` (chat-service → rag-service) now forwards the current
  MDC value as `X-Request-Id`, guarded against `null` (a unit test calling the
  gateway directly, without a request having gone through the filter first, would
  otherwise risk passing a null header value). This is what makes "grep one request
  ID across two services' logs" possible at all — every other request in this system
  stays within a single service.

### Access-denied auditing
- New `AuditingAuthenticationEntryPoint`/`AuditingAccessDeniedHandler`
  (`platform-common`), wired into `ResourceServerSecurityConfig` (every
  resource-server-validating service) and auth-service's own `AuthSecurityConfig` via
  `.exceptionHandling(...)`. Spring Security's default behavior for a rejected
  request was already a bare 401/403 with zero logging — these keep that exact
  response shape (`response.sendError(status)`, no message body, so the underlying
  `AuthenticationException`/`AccessDeniedException` message never leaks to the
  caller) and add one structured `log.warn` line each, with the resolved client IP
  via `ClientIpResolver` (made `public` for this — it previously only served
  `RateLimitFilter` in the same package).
- Not wired into `DemoSecurityConfig`: its chain is `anyRequest().permitAll()`, so
  neither handler could ever fire there — wiring them in would be dead code.
- `AuditingAccessDeniedHandler` covers a case (403, valid principal but insufficient
  authority) that essentially never fires today, since this project has no RBAC (ADR
  0031 keeps the tenant model deliberately flat). Added anyway, alongside the 401
  handler that does fire regularly, so both paths are audited consistently rather
  than only the one that happens to be reachable today.

### Login/registration/upload audit events
- `AuthService.register`/`login` log a success line (email, tenantId, userId — never
  the password) at the one place both paths converge, right before issuing a token.
- `InvalidCredentialsException` now carries the attempted email as a field, purely
  for this log line — the client-facing message stays the deliberately generic
  "Invalid email or password" it always was, so a failed login still can't be used to
  enumerate which emails are registered. `GlobalExceptionHandler.handleInvalidCredentials`
  logs it and increments `security.authentication.failed` — **no tag distinguishing
  unknown-email from wrong-password**, for the same enumeration-resistance reason: a
  tagged metric would leak through a side channel what the response body already
  deliberately doesn't.
- `UploadValidationService.validate`'s five rejection sites (empty file, unsupported
  extension, unsupported content type, content-type/extension mismatch, signature
  mismatch) all converge on one new private `reject(reason, filename, message,
  exceptionFactory)` helper: one `log.warn` line, one `security.upload.rejected`
  increment tagged by `reason`, then builds whichever of the two existing exception
  types (`InvalidUploadException`/`UnsupportedDocumentTypeException`) the call site
  needs via a passed-in constructor reference — never logs file content, only the
  filename and the specific reason. Acceptance also gets a log line, distinct from
  `DocumentIngestionService`'s existing "ingested" line later in the pipeline (a
  different concern: the validation decision, not the ingestion outcome).

### Grafana
- New "Segurança" row in `observability/grafana/dashboards/rag-platform-overview.json`
  with 3 panels, matching the existing panels' exact style (plain `timeseries`, no
  explicit `datasource`, `sum(rate(...[5m])) by (tag)` PromQL): rate-limit-blocked by
  rule (`security_rate_limit_blocked_total`, the Phase 2 metric this phase explicitly
  reuses rather than duplicating), failed logins, and rejected uploads by reason.

## Consequences
- **A real, pre-existing bug found by this phase's own audit logging, not by reading
  code**: `AuthSecurityConfig`'s allowlist (Security Phase 4) only covered
  `/actuator/health`, not `/actuator/prometheus` — Prometheus had been silently
  failing to scrape `auth-service` since Phase 4 shipped, confirmed via Prometheus's
  own `/api/v1/targets` showing that target `down` with a 401. Invisible before this
  phase because a rejected scrape request logged nothing at all; `AuditingAuthenticationEntryPoint`
  logging every 401 for real (triggered by an unrelated manual check against
  `/actuator/prometheus`) is what surfaced it immediately. Fixed by widening the
  allowlist to `/actuator/**`, matching `ResourceServerSecurityConfig`'s existing,
  already-broader allowlist for the other three services. Re-verified via
  `/api/v1/targets`: all four services' scrape targets show `up`.
- **Verified for real against the live docker-compose stack, not just the unit
  suite**:
  - A real login failure (wrong password against a real registered user) produced
    `security_authentication_failed_total{application="auth-service"} 1` at
    `/actuator/prometheus`, and the exact audit log line (`Login failed for
    email=... from ...`) with a `correlationId` field automatically attached via MDC.
  - A real rejected upload (a `.pdf`-named file whose bytes don't start with
    `%PDF-`) produced `security_upload_rejected_total{application="ingestion-service",
    reason="signature_mismatch"} 1`.
  - A real unauthenticated request against a protected endpoint on
    `ingestion-service` (`POST /api/v1/documents` with no bearer token) logged
    `Access denied (unauthenticated): POST /api/v1/documents from ... - Full
    authentication is required to access this resource` — confirming
    `AuditingAuthenticationEntryPoint` fires identically across services, not just
    the one where the actuator bug above was found.
  - **The correlation ID crossing two services' logs for one real request**: created
    a real conversation and sent a real message through chat-service, which called
    rag-service's `/api/v1/retrieve` internally. The exact same `correlationId`
    (`a74b9d3d-dc1d-4542-a5c6-0afe45014456`) appeared in chat-service's "Answered
    message in conversation..." line and in rag-service's "Hybrid search: ..." line
    for that request — greppable end to end across both services' logs. The
    roadmap's own illustrative example for this ("a blocked rate-limit request that
    also shows up in an upstream service's log") turned out not to be reproducible
    with this specific pair of endpoints: `/api/v1/retrieve` is deliberately excluded
    from rate limiting (documented in `rag-service/application.yml` — it's already
    bounded by chat-service's own conversation limit, and rate-limiting it too would
    double-count the same user action). The actual required capability — one ID,
    two services, real request, fully reconstructable — was verified directly
    instead of forcing that specific example to fit.
  - Confirmed via Grafana's own dashboard (`localhost:3001`) that the new
    "Segurança" row renders with the three expected panel titles, and via a direct
    Prometheus query (`/api/v1/query?query=security_authentication_failed_total`
    etc.) that the underlying data the panels query is real, not just
    schema-correct JSON.
- **`./mvnw clean verify` green across all 5 modules**: `platform-common` gained 6 new
  tests (`CorrelationIdFilterTest`, `AuditingAuthenticationEntryPointTest`,
  `AuditingAccessDeniedHandlerTest`); `auth-service` gained
  `GlobalExceptionHandlerTest` (2 tests, the counter's only direct coverage);
  `ingestion-service`'s `UploadValidationServiceTest` gained 3 tests for the new
  `security.upload.rejected` metric. No other module's tests needed changing —
  `RagServiceGateway`'s header addition is guarded against a null MDC value
  specifically so `ConversationServiceTest`'s mocked gateway and any future direct
  unit test of the real one don't need Correlation-ID-filter scaffolding just to
  compile.
- **`ClientIpResolver` and its `resolve` method both became `public`** — the only
  breaking-adjacent change in this phase, needed so the two new audit handlers
  (different package: `AuditingAuthenticationEntryPoint`/`AuditingAccessDeniedHandler`
  are in the same package, actually, so this was mechanical, not a real visibility
  widening beyond what was already needed).
- **Deliberately excluded from this phase's metrics**: a "reason" tag on
  `security.authentication.failed`, and any metric at all for access-denied
  (401/403) — both would either leak enumeration-relevant detail or, for
  access-denied specifically, weren't asked for by the roadmap's plan (only an audit
  *event*, not a named counter, was listed for that category).
