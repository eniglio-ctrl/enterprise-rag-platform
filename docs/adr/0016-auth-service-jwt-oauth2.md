# ADR 0016: auth-service — RS256 JWTs, JWKS, and the transition from trusted headers

## Status
Accepted

## Context
Since ADR 0007, every service trusted `X-Tenant-Id`/`X-User-Id` headers supplied
directly by the caller, with `"default"` as a fallback — explicitly documented at the
time as a placeholder to unblock retrieval/chat-service work without building real
authentication first. That placeholder was never meant to be permanent: anyone could
claim any `tenantId` just by setting a header, which defeats the entire point of
per-tenant retrieval isolation. This phase replaces the header-trust mechanism with a
real identity provider, without redesigning the tenancy contract itself — `tenantId`
and `userId` already existed as concepts everywhere; only their *source* changes.

## Decision
- **Custom `auth-service`, not Keycloak**: a hand-rolled JWT issuer is more didactic
  for a portfolio (demonstrates understanding RS256/JWKS/claims directly) than
  configuring a pre-built identity provider, and avoids adding a heavyweight service
  to an already-6-service local stack. Documented as a deliberate choice, not
  unawareness of the alternative.
- **RS256 + JWKS, not a shared symmetric secret (HS256)**: with four independent
  codebases needing to validate tokens, a shared secret means every one of them holds
  a value that can *forge* a token, not just verify one. With RS256, only
  `auth-service` holds the private key; everyone else fetches the public key from
  `GET /.well-known/jwks.json` and can only verify, never sign. This also simplifies
  the Kubernetes Secrets story from ADR 0014 — only `auth-service`'s deployment would
  ever need a signing-key Secret, once persisted (see limitation below).
- **Nimbus JOSE+JWT directly, not `jjwt`**: `com.nimbusds:nimbus-jose-jwt` is already
  on every service's classpath transitively through
  `spring-boot-starter-oauth2-resource-server` (Spring Security's own resource-server
  support is built on it). Using it directly in `auth-service` too means the exact
  same `RSAKey` object both signs tokens (`TokenService`) and serializes itself as a
  JWK for `JwksController` — no format conversion between two different JWT libraries'
  internal representations, and no extra dependency.
- **Deliberately simple tenant model**: registration takes a caller-supplied
  `tenantId` string directly (`POST /api/v1/auth/register {email, password, tenantId}`)
  rather than a separate organization-provisioning/invite flow. Two users registering
  with the same `tenantId` share a tenant, with no ownership or admin concept over it.
  Good enough to demonstrate real multi-tenant data isolation without building an
  organization-management feature that isn't this project's point.
- **Shared resource-server config in `platform-common`**
  (`ResourceServerSecurityConfig`, `JwtClaims`): identical
  `SecurityFilterChain`/CORS/CSRF/claim-extraction logic was about to be written three
  times (ingestion-service, rag-service, chat-service) — same rationale as ADR 0010's
  `CorsConfig` extraction. `auth-service` explicitly excludes this bean via its
  `@ComponentScan` filter, since it's the token *issuer*, not a resource server — it
  has no `jwk-set-uri` to validate against but itself, and its own registration/login
  endpoints must stay reachable without a token.
- **Token pass-through for service-to-service calls**: chat-service no longer forwards
  `X-Tenant-Id` to rag-service's `/api/v1/retrieve` — it forwards the *caller's own*
  bearer token as-is (`RagServiceGateway.retrieve(question, bearerToken)`).
  rag-service validates it against the same JWKS every service trusts and derives
  `tenantId` from the token itself, so chat-service's say-so about who's asking is
  never the thing being trusted.
- **web-ui stores the JWT in `localStorage`, not an HttpOnly cookie**: an HttpOnly
  cookie would require `auth-service` to set a cross-origin cookie for a static file
  server running on a different port (`localhost:3000` vs `localhost:8084`),
  substantially complicating CORS for a local demo with no real gain — there's no
  browser-based XSS attack surface being defended against here that matters more than
  shipping a working login flow. Documented tradeoff a real production deployment
  behind one origin could revisit, not an oversight.

## Consequences

- **Verified for real, cryptographically, not just HTTP-status checks**: `AuthIT`
  registers a user, then parses the JWT it gets back and verifies its RS256 signature
  against the public key served at `/.well-known/jwks.json` using Nimbus's own
  `RSASSAVerifier` — a wiring mistake between `JwtKeyProvider` and `TokenService`
  (e.g. signing with one key while publishing another) would pass a naive
  "token is a non-blank string" check but fail this one. `ConversationIT` similarly
  asserts the *exact* bearer token chat-service received from its caller is the one
  that reaches rag-service's stub, not just that *some* Authorization header arrived.
- **Every existing integration test needed updating**: `DocumentIngestionIT`,
  `ChatQueryIT` (6 tests), and `ConversationIT` (3 tests) all called endpoints that are
  now behind Spring Security — `SecurityMockMvcRequestPostProcessors.jwt()` fabricates
  an authenticated `Jwt` principal directly for `MockMvc`-based tests without any real
  cryptographic validation happening, which is exactly what these tests need (they're
  testing tenancy/business logic, not the auth mechanism itself — `AuthIT` already
  covers that separately).
- **Known limitation, called out rather than solved**: `JwtKeyProvider` generates a
  fresh RSA keypair in memory on every `auth-service` restart. Tokens issued before a
  restart stop validating after one — acceptable for a portfolio demo, not for a real
  deployment (which would mount a persisted key file or use a secret store). The
  entire point of RS256+JWKS over a shared secret was to make this the *only* place
  key management matters, which this limitation doesn't undermine — it just means that
  one place isn't finished yet.
- **`auth-service` becomes a real startup dependency for the other three services** in
  `docker-compose.yml` (`depends_on: auth-service: condition: service_healthy`) — not
  strictly required at the HTTP level (Spring Security's JWT decoder fetches and caches
  the JWKS lazily on first validation), but added anyway so a fresh `docker compose up`
  doesn't have ingestion-service/rag-service/chat-service accept traffic before
  there's anywhere to get a real token from.
- **Kubernetes manifests (ADR 0014) still need their promised second pass**: a fifth
  Deployment+Service for `auth-service`, a `ConfigMap`/env var for
  `AUTH_SERVICE_BASE_URL` on the other three, and — once the key-persistence
  limitation above is addressed — a Secret for the signing key. Not done in this phase;
  tracked as the exact follow-up ADR 0014 already flagged.
- **web-ui gained its first real auth flow**: login/register forms, `Authorization:
  Bearer` attached to the upload and ask requests, and a 401 response now clears the
  stored session and re-prompts for login rather than silently failing.
- **Real frontend bug found and fixed in the browser, not just by reading the code**:
  the logged-in state bar and the login form both rendered at once on first load — the
  new `.auth-bar { display: flex; }` rule and the browser's built-in
  `[hidden] { display: none }` rule tie on CSS specificity (one class selector, one
  attribute selector), and since `.auth-bar` was declared later in the stylesheet, it
  silently won even while the `hidden` attribute was correctly present on the element
  (confirmed via `getComputedStyle` — attribute present, `display: flex` anyway).
  Fixed with a global `[hidden] { display: none !important; }` rule, the standard
  defensive fix for this well-known pitfall — any future element that sets its own
  `display` while also relying on `hidden` for visibility would otherwise hit the same
  bug silently. Verified in a real browser after the fix: login-only state, then a
  full login → authenticated-app → real upload → real cited answer flow, end to end.
