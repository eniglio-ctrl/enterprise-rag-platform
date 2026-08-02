# ADR 0031: Tenant invitations and a persisted JWT signing key

## Status
Accepted

## Context
`docs/SECURITY-HARDENING-ROADMAP.md` Phase 4. This phase **deliberately supersedes**
two of ADR 0016's explicitly-flagged simplifications, rather than fixing bugs nobody
noticed:

- Registration took a caller-supplied, free-text `tenantId` string
  (`{email, password, tenantId}`) with no invite flow — anyone could join any existing
  tenant just by typing its name, since there was no check that the caller actually
  belonged there.
- `JwtKeyProvider` generated a fresh RSA keypair in memory on every `auth-service`
  restart, so every previously-issued token stopped validating the moment the service
  restarted — called out in ADR 0016 as "acceptable for a portfolio demo, not for a
  real deployment," with the Kubernetes Secret for it explicitly marked "not done in
  this phase."

## Decision

### Tenant model: invitations, not typed tenant names
- Registering with no `invitationToken` now always creates a brand-new tenant with a
  non-guessable UUID id (`AuthService.register`) — there is no longer any way to join
  an existing tenant by typing its name.
- Registering with an `invitationToken` redeems it via the new `InvitationService`,
  which enforces, in order: the token exists, it hasn't already been redeemed, it
  hasn't expired (7 days, `auth.invitation-ttl`), and its `email` matches the
  registering email exactly. Only then does the new user join the invitation's
  `tenantId`.
- **Redemption is a single atomic `UPDATE ... RETURNING`**
  (`InvitationRepository.redeem`), not a separate check-then-update: two concurrent
  registrations racing the same token can both pass the earlier read-only checks, but
  at most one can ever see `redeemed_at IS NULL` at the moment the atomic statement
  runs. `InvitationService.redeem` still does a plain read first, purely to produce a
  specific error message (not found / already used / expired / wrong email) — that
  read being stale under a race is harmless, since the atomic statement is the only
  one that actually grants a tenant.
- **Any authenticated member of a tenant can invite another member to it** — there is
  no separate admin/owner role. Deliberately kept flat, matching how the rest of the
  tenant model already has no ownership concept (ADR 0016); a role system was
  explicitly out of scope for this phase.
- `InvitationController`'s `POST /api/v1/auth/invitations` is the first endpoint
  `auth-service` itself requires a bearer token for — every other endpoint it exposes
  (`register`, `login`, JWKS, health) stays open by necessity, since you need this
  service precisely because you don't have a token yet.
- **New `tenants` table, `tenants.id` stays `TEXT`, not `UUID`** (`V2` migration):
  every pre-existing `tenant_id` value in `users` (free-text strings from before this
  table existed) is backfilled into it as-is via
  `INSERT INTO tenants SELECT DISTINCT tenant_id FROM users`, and `users.tenant_id`
  gets a foreign key to it. Casting the column to a native `UUID` type would have
  failed outright on any non-UUID-shaped existing value (e.g. a tenant literally named
  `"acme"` from local testing) — keeping it `TEXT` means only *new* tenants created
  going forward get a random UUID string as their id; the column type doesn't need to
  enforce that shape retroactively.

### JWT signing key: persisted, not regenerated per restart
- `JwtKeyProvider` now loads a PKCS8 PEM-encoded RSA private key from a mounted secret
  file (`auth.signing-key.path`) or a Base64-encoded environment variable
  (`auth.signing-key.value`) — the same two options this project already uses for
  every other secret (Kubernetes Secret vs. docker-compose `.env`). It falls back to
  generating an ephemeral in-memory key, exactly like before, only when neither is
  configured — fine for tests (a JVM that never restarts mid-test doesn't care), never
  acceptable for a real deployment, which always supplies one of the two.
- **The JWKS `kid` is now a SHA-256 thumbprint of the key material itself**
  (`RSAKey.Builder.keyIDFromThumbprint`), not a random value generated at construction
  time. This is the detail that actually makes cross-restart validation work: the same
  persisted key always produces the same `kid`, so a downstream service resolving a
  token's `kid` against a freshly-fetched JWKS after `auth-service` restarts still
  finds the same entry, instead of a `kid` that only existed for the previous process's
  lifetime.
- **Kubernetes mounts the key as a file** (`kubernetes/base/jwt-signing-key.pem`,
  gitignored, generated locally with
  `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048`), added to
  `kustomization.yaml`'s `secretGenerator` as a `files:` entry and mounted as a volume
  at `/etc/auth-service/jwt/` — the standard way Kubernetes handles key/cert material,
  same pattern as a TLS secret.
- **docker-compose supplies it as a required Base64 env var**
  (`JWT_SIGNING_KEY: ${JWT_SIGNING_KEY:?...}`, Security Phase 3's fail-fast pattern) —
  but unlike the DB credentials and Grafana password, `.env.example` does **not** ship
  a default value for it. A signing key is sensitive enough (it can forge a token for
  any tenant, not just gate access to one local Postgres instance) that it shouldn't be
  a shared, publicly-known value even for local/portfolio use, even though the same
  threat model technically applies to the fixed DB password already committed there.
  `.env.example` instead documents the one-line `openssl genpkey | base64` command to
  generate a private one. (This was a real judgment call surfaced by Claude Code's own
  safety classifier blocking an initial attempt to commit a generated key inline into
  `.env.example` — asked directly, the placeholder-plus-instructions approach was
  chosen over committing a shared fixed key or a gitignored-only file.)

## Consequences
- **Verified for real, per this phase's own "done when" criteria**, not just by
  reasoning about the code (`AuthIT`, 8 tests):
  - Registering with no invitation always gets a fresh UUID tenant; a second person
    cannot join it without an invitation.
  - A teammate can join an existing tenant through a valid invitation
    (`aTeammateCanJoinAnExistingTenantThroughAValidInvitation`).
  - Redeeming the same invitation twice is rejected on the second attempt
    (`rejectsRedeemingTheSameInvitationTwice`).
  - An invitation redeemed with the wrong email is rejected
    (`rejectsAnInvitationRedeemedWithADifferentEmail`).
  - An expired invitation is rejected even with the correct email — verified by
    **manually expiring the row via direct SQL** in the test
    (`UPDATE invitations SET expires_at = now() - interval '1 day'`), not just trusting
    the TTL logic (`rejectsAnExpiredInvitationEvenWithTheCorrectEmail`).
  - `POST /api/v1/auth/invitations` without a bearer token is rejected
    (`createInvitationRequiresAToken`).
  - `JwtKeyProviderTest` proves two independent `JwtKeyProvider` instances loading the
    *same* configured PEM produce the *identical* `kid` — the unit-level version of
    "tokens issued before a restart are still valid after one."
- **The actual restart proof was run for real against the live docker-compose stack**,
  not just asserted at the unit level: registered a user (got a fresh UUID tenant),
  created an invitation with that token, redeemed it as a teammate (confirmed same
  `tenantId` in both responses), confirmed redeeming the same invitation a second time
  returns 400, then ran `docker restart` on the real `auth-service` container and
  reused the *original pre-restart* token against the now-authenticated
  `POST /api/v1/auth/invitations` endpoint — `201 Created`, not `401`. Confirmed via
  the container logs that no "generating an ephemeral in-memory key" warning appeared
  on that restart, ruling out a false pass from both processes coincidentally
  generating compatible ephemeral keys. Also confirmed via `psql` against the real
  local Postgres (which already had 7 real pre-existing free-text tenant IDs from
  earlier phases, e.g. `"eniglio"`, `"acme"`) that the `V2` migration's backfill left
  zero users without a matching row in the new `tenants` table — the exact risk the
  `TEXT`-not-`UUID` column type decision above was made to avoid.
- **A real browser-tooling gotcha hit while verifying the new "Invite a teammate" UI
  panel visually**: the automated browser session's HTTP cache kept serving a stale,
  pre-rebuild copy of `app.js` for every subsequent tab/navigation in that session,
  confirmed by fetching `/app.js` directly from within the page (stale content) versus
  via `curl` from the host (correct, updated content) — a cache scoped to the tool's
  browser profile, not per-tab, since even a freshly opened tab reused it. Rather than
  keep fighting a test-tool artifact unrelated to the actual code, the equivalent
  check was done a different way: the real `/api/v1/auth/register` call was issued
  directly from the page's own JavaScript context (proving real CORS/origin behavior
  from `localhost:3000`, not just from a bare `curl`), and the panel's markup and
  copy were confirmed present and correctly rendered via a real screenshot. The
  event-wiring itself (`inviteForm.addEventListener`) was confirmed present in the
  server's actual served bytes via `curl`, and is structurally identical to the
  pre-existing `registerForm` handler this project already ran in a real browser
  successfully (ADR 0016).
- **`./mvnw clean verify` green across all 5 modules**: `auth-service`'s own suite grew
  from 3 test classes / 11 tests to 5 classes / 29 tests (`JwtKeyProviderTest` and
  `InvitationServiceTest` new, `AuthServiceTest`/`AuthIT` extended). No other module
  changed — nothing outside `auth-service` calls its DTOs directly.
- **web-ui updated to match**: the free-text "Tenant ID" registration field is gone,
  replaced by an optional "Invitation token" field; a new "Invite a teammate" panel
  (hidden in the public demo, which has no `auth-service` at all — ADR 0020) lets a
  logged-in user create an invitation and see its token to share out-of-band. There is
  no email-sending in this phase — sharing the token is a manual, out-of-band step,
  consistent with keeping the invitation model itself deliberately minimal.
- **Kubernetes and docker-compose topologies now genuinely differ in how they supply
  the signing key** (file vs. env var) — this is intentional, not an inconsistency to
  reconcile: `JwtKeyProvider` already needed to support both for exactly this reason
  (Kubernetes Secrets are conventionally files; docker-compose `.env` is conventionally
  flat key-value pairs), and this phase is what actually exercises both paths for the
  first time (`JwtKeyProviderTest` covers both directly).
- **Not addressed, deliberately out of scope**: revoking a specific issued token before
  its natural expiry (no token blocklist/short-lived-refresh-token pattern exists);
  removing a member from a tenant; an admin/owner role distinguishing who can invite.
  None of these were asked for by this phase's roadmap entry, and adding any of them
  now would be scope creep beyond "stop letting anyone join any tenant by typing its
  name."
