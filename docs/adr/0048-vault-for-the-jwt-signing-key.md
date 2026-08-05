# ADR 0048: HashiCorp Vault (dev mode) for the JWT signing key

## Status
Accepted

## Context
`docs/PRODUCTION-READINESS-ROADMAP.md` Phase 2 was blocked on a decision
before any code: which secrets backend to use (Vault, a cloud secret
manager, or Kubernetes `Secret`s plus an external-secrets operator). The
user chose **HashiCorp Vault in dev mode, run locally via docker-compose**
— free, no cloud account, the same "just another service" shape as
Postgres/Ollama already have, and the only option of the three that lets
this be demonstrated for real without spending anything.

Before this phase, every secret in the project (the JWT signing key, DB
credentials, LLM fallback API keys) followed the exact same pattern: a
mounted file or a Base64 env var, read once at startup via
`@ConfigurationProperties`, never touched again. `JwtKeyProvider`
(`auth-service/.../security/JwtKeyProvider.java`) resolves its `RSAKey` in
its constructor and holds it in a `final` field forever — confirmed by
reading the class, not assumed. There was no refresh/polling mechanism
anywhere in the codebase to build on; this phase introduces the first one.

## Decision

### Same property, new source — not a new code path
The property name stays `auth.signing-key.value` (today populated by
`${JWT_SIGNING_KEY:}`). Vault populates the *exact same name*, via Spring
Cloud Vault's generic KV backend (`spring.config.import:
optional:vault://`, `spring.cloud.vault.kv.enabled: true`) reading
`secret/auth-service` — Spring Cloud Vault defaults to a KV path named
after `spring.application.name`, which is already `auth-service`.

### Three designs tried, in order, each disproved by actually running it
This is the part worth documenting honestly, because the first two
*looked* correct and only a real end-to-end check caught the problem:

1. **`@RefreshScope` on `JwtKeyProvider`.** `AuthProperties` (a
   `@ConfigurationProperties` record) already re-binds automatically after
   `POST /actuator/refresh`; `@RefreshScope` should, on paper, make Spring
   destroy and lazily recreate `JwtKeyProvider` against the now-rebound
   value. Registering a real user, rotating the Vault secret, calling
   `/actuator/refresh`, and checking the JWKS afterward showed the exact
   same `kid` every time. A constructor-call counter confirmed why:
   `JwtKeyProvider`'s constructor only ever ran once, at boot — the
   refresh-scope proxy was never actually re-triggering it in this
   config-data-import setup.
2. **Inject `ObjectProvider<AuthProperties>`, re-fetch the bean on an
   `EnvironmentChangeEvent`.** Since `AuthProperties` is an immutable
   record, refreshing it discards the old instance rather than mutating
   it — a plain constructor-injected reference (all attempt 1's
   lazily-recreated proxy target would still hold) stays pointed at the
   stale instance. Re-fetching the *current* bean from the container
   inside an explicit `@EventListener(EnvironmentChangeEvent.class)`
   looked like the fix, and the JWKS key did change after this. Tightening
   the test to assert against the *exact* kid the new PEM should produce
   (not just "some kid changed") caught a second, subtler bug: the new kid
   matched neither the old nor the new key. `ConfigurationPropertiesRebinder`
   (the component that actually rebinds `AuthProperties`) is itself just
   another listener of the same `EnvironmentChangeEvent`, on a different
   bean, with no defined ordering relative to `JwtKeyProvider`'s own
   listener — depending on registration order, `JwtKeyProvider`'s handler
   could run *before* `AuthProperties` had been rebound, reading the stale
   value right back out of the "current" bean.
3. **Read `Environment` directly, shipped.** `JwtKeyProvider` takes a
   `Environment` in its constructor and calls
   `environment.getProperty("auth.signing-key.value")` (and `.path`)
   itself, both at construction and inside the same
   `@EventListener(EnvironmentChangeEvent.class)`. This sidesteps both
   prior failure modes: there's no scoped-proxy recreation to fail
   silently, and no rebinding step to race against — the `Environment`'s
   own property sources are already updated by the time *any*
   `EnvironmentChangeEvent` listener runs, because the event's own key
   list is computed as a diff against that already-updated environment.
   `AuthProperties` no longer carries a `SigningKey` at all now that
   nothing binds it that way (`TokenService`/`JwksController` needed no
   changes either way across all three attempts: both call
   `jwtKeyProvider.signingKey()`, never anything property-shaped
   directly).

### `optional:` + a non-blank placeholder token — additive, not a hard requirement
`spring.config.import: "optional:vault://"` and `fail-fast: false` mean a
Vault-less run (e.g. `mvn spring-boot:run` without docker-compose) keeps
working exactly as it did before this phase, falling through to
`JWT_SIGNING_KEY`/the ephemeral-key fallback. A real bug caught only by
running the actual test suite, not by reasoning about the config: Spring
Cloud Vault builds its `TOKEN` `ClientAuthentication` *eagerly*, during
config-data processing, and throws `IllegalStateException` on a genuinely
blank token *before* `optional:`/`fail-fast` get a chance to turn anything
into a graceful skip — this broke every single `auth-service` test
(`ApplicationContext failure threshold exceeded`) the first time the test
suite ran after this config landed. Fixed with a non-blank placeholder
default (`token: ${VAULT_TOKEN:not-configured}`) — a real Vault rejects it
with 403 or the connection simply fails when nothing is listening, both of
which *are* the kind of failure `optional:`/`fail-fast: false` are meant
to swallow gracefully; a missing token string is a local configuration
error that happens before any network call, which they are not.

### `docker-compose.yml`: `vault` + a one-shot `vault-init`
`vault` runs `hashicorp/vault:1.17` in dev mode
(`VAULT_DEV_ROOT_TOKEN_ID`/`VAULT_DEV_LISTEN_ADDRESS`, healthcheck via
`vault status`). `vault-init` is a one-shot container, depending on
`vault` being healthy, that runs `vault kv put secret/auth-service
auth.signing-key.value="$JWT_SIGNING_KEY"` — reusing the exact
`JWT_SIGNING_KEY` value `.env` already provides (Security Phase 4, ADR
0031), just relocating *where it's read from* going forward. Idempotent;
re-running it on every `docker compose up` is harmless.
`auth-service` no longer receives `JWT_SIGNING_KEY` directly in its own
`environment:` block — deliberately, to prove it now genuinely depends on
Vault rather than silently still reading a same-named env var underneath.
It gains `VAULT_HOST`/`VAULT_PORT`/`VAULT_TOKEN` instead, and its
`depends_on` requires `vault-init: condition: service_completed_successfully`.

## Consequences

### Verified two ways, not just one
**Automated** (`VaultKeyRotationIT`, real Testcontainers Vault +
Postgres, no mocks): seeds a real generated RSA key into Vault, confirms a
token issued by `/api/v1/auth/register` verifies against the real JWKS
endpoint *and* that the JWKS `kid` matches the exact thumbprint that PEM
should produce (not just "a token verified" — that stricter check is what
caught attempt 2's ordering bug above); overwrites the Vault secret with a
*different* generated key; calls `POST /actuator/refresh`; confirms a
token issued afterward now verifies against a public key matching the
*new* PEM's own exact thumbprint, in the same running process (no new
Testcontainers container, no JVM restart) — and confirms the pre-rotation
token no longer verifies against the post-rotation JWKS, proving the
hard-cutover behavior below for real, not asserting it away.

A fourth real bug, specific to the test itself rather than the production
code: `@DynamicPropertySource` (used to point `spring.cloud.vault.host`/
`port`/`token` at the Testcontainers Vault's actual mapped port) is
applied too late for `spring.config.import: vault://` to see it — config
data imports resolve during Spring Boot's earliest environment-preparation
phase, before `@DynamicPropertySource` values are attached via the Spring
TestContext framework's `ApplicationContextInitializer`. Every run
silently fell back to the ephemeral key (both "before" and "after" checks
alike, which is exactly how a false pass slipped through: two different
ephemeral keys are still "different" whether or not real Vault rotation
ever happened). Fixed by setting the same values as plain JVM system
properties in a static initializer instead, which are visible from the
very first moment Spring reads its `Environment` — with an `@AfterAll`
clearing them again, since Surefire reuses one JVM across test classes and
a leaked `spring.cloud.vault.enabled=true` would have broken `AuthIT`
(which relies on Vault staying off) if it ran afterward in the same fork.

**Manual, against the real docker-compose stack**: `vault kv get
secret/auth-service` confirms the seeded secret; registering/logging in
issues a valid token; `vault kv put` with a new key + `curl -X POST
localhost:8084/actuator/refresh` + a fresh login, confirmed via `docker
inspect`'s `StartedAt` timestamp that the `auth-service` container was
never restarted throughout.

### Two limitations, stated rather than hidden
1. **Dev-mode Vault is not production Vault.** No unseal/HA, in-memory
   storage lost on every restart, a static root token authenticated over
   plain HTTP. Real production use would need Vault's server mode with
   real storage, TLS, and a non-static auth method (AppRole, Kubernetes
   auth) — explicitly out of scope here; dev mode is the only way to
   demonstrate this integration for free, matching the Tier 2 "no money"
   framing the user chose it under.
2. **Rotation here is a hard cutover, not a graceful grace-period.** The
   JWKS endpoint only ever publishes one key (`JwtKeyProvider.publicJwk()`
   — a single `RSAKey`, not a set); the instant a refresh picks up a new
   secret, every token signed with the previous key stops verifying
   anywhere in the platform. A zero-downtime rotation would need the JWKS
   to publish both the outgoing and incoming key for a grace window and
   `TokenService` to keep signing with a clearly-designated "current" one
   — real, additional scope this phase's own "done when" (one secret,
   rotated, verified without a restart) never asked for.

### Scope not built
No other secret migrated to Vault yet (DB credentials, LLM fallback API
keys still read from `.env` directly) — the roadmap's "done when" asked
for one real secret proven end-to-end, not every secret in the project.
