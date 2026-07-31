# ADR 0028: Rate limiting and abuse prevention

## Status
Accepted

## Context
`docs/SECURITY-HARDENING-ROADMAP.md` Phase 2 / `docs/ROADMAP.md` Tier 1 #4.
None of the four services had any request-rate protection before this — a
repo-wide grep for `RateLimit`/`Bucket4j`/`attempts`/`lockout`/`throttle`
returned zero hits. Without it, `auth-service`'s login is open to unlimited
password-guessing, and `ingestion-service`/`rag-service`/`chat-service`'s
upload/ask/chat/diagram/conversation endpoints have no defense against a
single caller exhausting Ollama/Whisper/Postgres capacity for every tenant.

## Decision
- **Bucket4j, not Resilience4j's own `RateLimiter`**, despite Resilience4j
  already being this project's resilience library (ADR 0009). The two solve
  different shapes of problem: Resilience4j's `RateLimiter` protects one
  outbound call site with a single shared limiter; this phase needs many
  independent per-key buckets (per-IP for unauthenticated endpoints,
  per-tenant for authenticated ones) — Bucket4j is built for exactly that.
  `com.bucket4j:bucket4j_jdk17-core:8.19.0` (Maven Central relocated the
  plain `bucket4j-core` coordinate starting with the 8.x line — confirmed via
  search before adding the dependency, not guessed).
- **One shared `RateLimitFilter` in `platform-common`**, not four copies —
  same rationale as `ResourceServerSecurityConfig`/`CorsConfig` (ADR 0010).
  Registered via `.addFilterAfter(rateLimitFilter, AuthorizationFilter.class)`
  in all three `SecurityFilterChain`s that exist across the platform
  (`auth-service`'s own, `ResourceServerSecurityConfig`, `DemoSecurityConfig`)
  — deliberately *after* both authentication and authorization run, so a
  tenant-keyed rule always sees the real, already-resolved
  `JwtAuthenticationToken` rather than racing it.
- **Config-driven rules** (`RateLimitProperties`, `security.rate-limit.*`):
  each service lists its own `Rule`s (name, Ant path pattern, `keyByIp`,
  capacity, refill period) — evaluated in order, first match wins, no match
  means no limiting. In-memory buckets in a `ConcurrentHashMap` keyed by
  `rule-name:key` — a deliberate, documented non-distributed limitation
  (single instance per service today, no horizontal scaling), same shape as
  ADR 0002's shared-database simplification: if a service is ever scaled to
  multiple replicas, each one enforces its own independent limit,
  effectively multiplying the real ceiling by the replica count.
- **Rules configured**: `auth-service`'s `/api/v1/auth/*` (register+login
  share one budget, keyed by IP — there's no principal yet before a JWT
  exists), 10/minute. `ingestion-service`'s `/api/v1/documents`, keyed by
  tenant, 20/minute. `rag-service`'s `/api/v1/ask`+`/api/v1/chat`+
  `/api/v1/diagrams`, all sharing one **`rag-query`**-named rule (three
  `Rule` entries, same name) so a tenant can't get 3x the effective rate by
  spreading requests across the equivalent endpoints — deliberately
  excludes `/api/v1/retrieve`, since that's only ever called
  server-to-server by `chat-service` (ADR 0013) and is already bounded by
  `chat-service`'s own limit; limiting it too would double-count the same
  user action. `chat-service`'s `/api/v1/conversations`
  +`/api/v1/conversations/**`, keyed by tenant, 30/minute.
- **The public demo profile overrides `rag-service`'s rules to `keyByIp:
  true`**, not the base's tenant-keyed ones. Every demo request
  authenticates as the exact same synthetic `DEMO_TENANT_ID`
  (`DemoSecurityConfig`) — a tenant-keyed rule there would let one
  aggressive visitor's traffic exhaust the shared bucket for every other
  concurrent visitor. Confirmed a profile-specific YAML list *replaces* the
  base list entirely rather than merging (the exact gotcha
  `application-demo.yml`'s own `spring.autoconfigure.exclude` comment
  already documents) — every rule needed restating there, not just the
  changed field.
- **`trustedProxyHops: 0` everywhere, including the demo profile** — a
  deliberate, honest non-decision, not an oversight. `X-Forwarded-For` is a
  plain client-settable header; trusting it safely requires knowing exactly
  how many real reverse-proxy hops sit in front of a service, so only the
  rightmost N entries (appended by trusted hops, immune to a client
  prepending forged entries on the left) get read. Searched for Render's
  exact edge hop count before deciding whether to configure a real value for
  the demo — found no authoritative answer, and guessing wrong here would be
  worse than the status quo (a wrong hop count lets an attacker forge
  whatever IP they want; `0` at least falls back to the real socket peer
  address every time). Left for Security Phase 6 (public demo hardening,
  not yet done) to confirm for real before changing.
- **429 with a real `Retry-After` header** (seconds until the bucket's next
  token, from Bucket4j's own `ConsumptionProbe`), plus
  `X-RateLimit-Remaining` on successful requests. A new
  `security.rate_limit.blocked` Micrometer counter, tagged by rule name —
  the Grafana panel entry the roadmap's plan mentions is deferred to
  Security Phase 5 (audit logging, not yet done), which explicitly reuses
  this same metric.
- **Every test resource profile gets `security.rate-limit.enabled: false`**
  (`auth-service` needed a new `@ActiveProfiles("test")` + test yml added —
  it had neither before, unlike the other three services). `AuthIT`,
  `DocumentIngestionIT`, `ChatQueryIT`, and `ConversationIT` all fire several
  requests in a row through a real `MockMvc`-driven filter chain; without
  this, test volume alone would start tripping 429s that have nothing to do
  with what those tests actually verify.

## Consequences
- **Verified for real against the running `docker-compose` stack, not just
  unit tests**: 11 sequential login attempts against a rebuilt
  `auth-service` — the first 10 got `401` (wrong credentials, the limiter
  correctly let them through), the 11th got `429` with `Retry-After` and
  `X-RateLimit-Remaining: 0`. Three follow-up requests each carrying a
  different forged `X-Forwarded-For` value still landed in the exact same
  bucket and got `429` — confirming the header has zero effect with
  `trustedProxyHops: 0`. `GET /actuator/prometheus` showed a real
  `security_rate_limit_blocked_total{rule="auth"} 4.0` afterward, matching
  the 4 blocked requests exactly. A fresh real registration and a real
  `/api/v1/ask` question both succeeded normally (`201`/`200`) once the
  bucket had partially refilled — confirming legitimate use isn't
  collaterally broken.
- **`platform-common` has its first tests ever**
  (`RateLimitFilterTest` 7 cases, `ClientIpResolverTest` 6, `RateLimitConfigTest`
  1) — the module had zero test dependencies before this (no
  `spring-boot-starter-test` at all), added alongside them. Covers both
  roadmap "done when" criteria at the unit level: N+1 requests from the same
  key get 429, and a forged `X-Forwarded-For` has zero effect when no proxy
  hop is trusted — plus independent buckets per IP/tenant, tenant-vs-IP
  keying, multi-hop `X-Forwarded-For` parsing, the no-match-means-no-limit
  path, and the global `enabled: false` off-switch.
- **`./mvnw clean verify` green across all 5 modules** both before and
  after adding the filter to every `SecurityFilterChain` — no existing test
  needed behavior changes beyond the new `enabled: false` test-profile
  overrides above.
- **Real gate check via SonarCloud (ADR 0027) caught a real gap this ADR's
  own first commit introduced**: `platform-common`'s `pom.xml` never had a
  `jacoco-maven-plugin` reference (it had zero tests before this phase, so
  it was never needed) — without it, no `jacoco.xml` was ever produced for
  this module, so SonarCloud reported **0% new-code coverage on
  `RateLimitFilter`** despite `RateLimitFilterTest`'s real content. Fixed by
  adding the bare `<plugin>` reference (root `pom.xml`'s `pluginManagement`
  already supplied the version/executions, same pattern the 4 service
  modules already used). `ClientIpResolver` — the actual trusted-proxy-hop
  parsing, the single most security-sensitive piece of logic in this ADR —
  also had no direct test at all before this pass; added 6 cases including
  the multi-hop and malformed/too-short-header fallback paths. Gate went
  `ERROR` (0% new coverage) → **`OK` (89.1%)** with these two real fixes,
  same discipline as ADR 0027 — no check disabled, no threshold lowered.
- **One known, accepted coverage-attribution gap, not hidden**: the ~6 new
  lines across `AuthSecurityConfig`/`ResourceServerSecurityConfig`/
  `DemoSecurityConfig` (each just gaining one `.addFilterAfter(...)` call
  and a new bean-method parameter) show as uncovered in JaCoCo's per-module
  reports, even though they genuinely execute on every context boot of
  `AuthIT`, `DocumentIngestionIT`, `ChatQueryIT`, and `ConversationIT` — a
  real, observed Spring `@Configuration`-bean-method instrumentation
  quirk (confirmed by checking `auth-service`'s own `jacoco.xml` directly:
  `AuthSecurityConfig` shows 0/9 lines covered despite `AuthService` itself,
  exercised by the exact same test, showing 17/17), not something specific
  to this change. Left as-is rather than chased further: these are two-line
  wiring calls with no branching logic to get wrong, already proven correct
  by every IT test's mere ability to boot a working filter chain at all, and
  the overall gate cleared `OK` regardless.
- **A request-body-size cap on the question/chat text itself and a
  per-tenant concurrent-in-flight-call limit** (both mentioned in the
  roadmap's original plan, alongside rate limiting) are deliberately **not**
  part of this ADR — they're a different protection (payload size /
  concurrency, not request rate) and can land as their own follow-up without
  blocking this phase's actual "done when" criteria, all of which are about
  the rate limiter itself.
