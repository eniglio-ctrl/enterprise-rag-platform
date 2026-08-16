# ADR 0047: A tenant-scoped ADMIN role, and the permission-management screen it powers

## Status
Accepted

## Context
The user asked for a screen to grant document permissions and to create/promote an
ADMIN user. Before this, the platform had no role concept anywhere: not in the JWT
(`TokenService` issued `sub`/`tenantId`/`email` only), not in the `users` table, not in
Spring Security (no `@PreAuthorize`, no `GrantedAuthority` mapping in any of the three
security configs). ADR 0031 documented this as deliberate: "there is no separate
admin/owner role... any authenticated member of a tenant can invite another member to
it." ADR 0046 (resource-level authorization / ABAC, `docs/ROADMAP.md` item #24) then
left an explicit gap: no `GET /api/v1/documents`, and no UI at all for the sharing
endpoint it built — "this phase built the enforcement mechanism and its one write path,
not a user-facing sharing workflow."

Three design questions had to be resolved before any code, all decided by the user:
1. **Scope**: a per-tenant ADMIN, not a platform-wide super-admin — preserves the
   absolute tenant isolation every other part of this codebase already enforces (JWT,
   SQL, Spring Security). A global admin would have required the first cross-tenant
   exception anywhere in the system.
2. **Bootstrap**: automatic — whoever creates a tenant (registers with no
   `invitationToken`) becomes its first ADMIN. There is no other way a tenant's first
   ADMIN could come into existence otherwise (a chicken-and-egg problem any
   promote-only design would hit). Tenants that already existed before this migration
   get the same rule applied retroactively.
3. **Powers, this first version**: (i) change the sharing of *any* document in the
   tenant, not just ones the ADMIN owns themselves — the actual reason this screen
   exists; (ii) list the tenant's members; (iii) promote/demote another member.
   Inviting someone directly as ADMIN is out of scope — the existing invitation flow
   (ADR 0031) is unchanged and always grants `MEMBER`; promotion is a separate,
   explicit action an existing ADMIN takes afterward.

## Decision

### `platform-common`: a shared `Role` enum, the same pattern as `DocumentVisibility`
`Role { MEMBER, ADMIN }` lives in `platform-common` because both `auth-service` (issues
the `"role"` JWT claim) and `ingestion-service` (reads it to decide whether a caller may
override a document's own owner) must agree on the exact same values — precisely the
reasoning ADR 0046 already used for `DocumentVisibility`. `JwtClaims.role(jwt)` defaults
to `Role.MEMBER` when the claim is missing or blank, not an exception: tokens issued
before this claim existed, and the demo profile's synthetic JWT (`DemoSecurityConfig`,
ADR 0020), must keep authenticating — just without admin privilege — rather than fail.

### `users.role`, with an automatic and a retroactive bootstrap
A new column (`V3__user_role.sql`), `NOT NULL DEFAULT 'MEMBER'`, backfilled in the same
migration:
```sql
UPDATE users SET role = 'ADMIN'
WHERE id IN (
    SELECT DISTINCT ON (tenant_id) id FROM users ORDER BY tenant_id, created_at ASC
);
```
`created_at` already existed on every row since the V1 migration, unused until now —
this picks the earliest-created user of each tenant, mirroring exactly the rule new
registrations follow going forward (`AuthService.register`: no invitation token means a
brand-new tenant, and its creator becomes `Role.ADMIN`; redeeming an invitation always
grants `Role.MEMBER`, unchanged from before). Every tenant, old or new, ends up with
exactly one ADMIN with no manual step.

### Two new admin-only endpoints in `auth-service`
`GET /api/v1/auth/users` lists the caller's own tenant's members (id, email, role) —
needed regardless, since the permission screen has to populate a "share with" selector
from somewhere, and ingestion-service (owner of documents) still never calls
auth-service (owner of users): the `web-ui` does the owner-id-to-email join client-side.
`PATCH /api/v1/auth/users/{userId}/role` promotes or demotes another member of the same
tenant (`UserRepository.updateRole` is scoped by `tenantId` in its `WHERE` clause, so a
cross-tenant target simply affects zero rows — treated as 404, the same
existence-hiding spirit `InvitationService` already follows for its own lookups). A
caller can never change their own role — blocked outright
(`CannotChangeOwnRoleException`, 400) rather than counting how many other ADMINs a
tenant has left; simpler, and it makes a tenant permanently lockout-proof by
construction rather than by bookkeeping.

### `ingestion-service`: the one ownership bypass, plus the listing endpoint ADR 0046 didn't build
`DocumentSharingService.updateSharing`'s ownership check becomes
`!callerUserId.equals(ownerId) && callerRole != Role.ADMIN` — an ADMIN may restrict or
share any document in their own tenant, the actual capability this whole screen exists
to expose. `findChunks` already scopes the lookup by `tenantId`, so this never crosses
a tenant boundary regardless of role. `GET /api/v1/documents` (admin-only, 403
otherwise) fills the gap ADR 0046 flagged as unbuilt: one row per document (not per
chunk) via `SELECT DISTINCT ON (metadata->>'documentId') ... ORDER BY
metadata->>'documentId', id`, the `id` tiebreaker added deliberately so the choice of
representative chunk is deterministic rather than relying silently on every chunk of a
document already carrying identical `visibility`/`sharedWith` (true today because
`DocumentSharingService` rewrites them in lockstep, but not worth depending on quietly).
A missing `"visibility"` key (any document ingested before ADR 0046) is reported as
`"TENANT"` in the response, not `null` — the same default `DocumentVisibility
.isVisibleTo` already applies for retrieval.

### `web-ui`: one admin panel, hidden unless `role === "ADMIN"`
`AuthResponse` gained a `role` field, and the call site passes `{ ...body, email }`
into `setAuth`, which looked like it would forward `role` into `localStorage` for
free. It didn't: `setAuth`'s own signature explicitly destructured only
`{ token, expiresInSeconds, tenantId, userId, email }`, silently dropping any other
key the caller passed in — a real bug caught only by live verification (the JWT itself
correctly carried `"role":"ADMIN"`, confirmed by decoding it, while the admin panel
still stayed hidden), fixed by adding `role` to that destructured parameter list.
`renderAuthState()` shows a new
`#admin-panel` section (hidden in the public demo, same as `#upload-panel`/
`#invite-panel`, since the demo has no login at all) only when the stored role is
`ADMIN`, and then calls `loadAdminPanel()`, which fetches both new endpoints in
parallel and renders two lists: **Team** (promote/demote buttons, hidden on the
caller's own row) and **Documents** (a visibility `<select>` plus checkboxes for every
other tenant member, populated from the same user list — the owner is excluded from
their own document's checkbox list since they're always implicitly visible).

## Consequences

### What this unlocks
A tenant's ADMIN can now, for real: see every document in the tenant regardless of who
uploaded it, restrict or reopen any of them, see who else is in the tenant, and
promote/demote members — closing the "no UI at all" gap ADR 0046 explicitly left open.

### Verified with real tests, not just reasoned about
`auth-service`: `AuthServiceTest` confirms `register` grants `ADMIN` with no invitation
and `MEMBER` with one. `AuthIT` (real Postgres via Testcontainers) adds: listing
requires admin (403 otherwise); promote then demote round-trips correctly; a non-admin
cannot change anyone's role; an admin cannot change their own; an invalid role value is
400; a cross-tenant or unknown target is 404; and a dedicated test re-runs the exact
backfill `UPDATE` (scoped to one tenant so it can't touch other tests' rows in the same
shared container) against three deliberately-ordered "legacy" rows, confirming only the
earliest becomes `ADMIN`. `ingestion-service`: `DocumentIngestionIT` adds an ADMIN
successfully restricting a document they don't own (previously 403); listing requiring
admin (403 otherwise); and a full listing test that manually strips the `"visibility"`
key from one document's chunks via `metadata::jsonb - 'visibility'` to simulate a
pre-ADR-0046 document, confirming it reports `"TENANT"`, not `null`.

### A real bug found only by live verification, not by the test suite
Every automated test above passed before this was caught: none of them exercise
`web-ui/app.js` at all. Registering a brand-new user against the real running stack and
checking the browser's own `localStorage` (not just the HTTP response, which was
already correct) showed the admin panel staying hidden despite a JWT that, decoded by
hand, genuinely carried `"role":"ADMIN"`. The cause was `setAuth`'s own explicit
destructuring silently dropping the new field (see above) — fixed, then re-verified by
registering again and confirming the panel now appears.

### Scope not built
No self-service sharing UI for a regular (non-admin) document owner — the sharing
`PATCH` endpoint from ADR 0046 was already directly callable by any owner, but
discovering their own `documentId` still has no non-admin-facing UI; `GET
/api/v1/documents` is admin-only in this version. No UI for inviting someone directly
as ADMIN — an admin invites normally (ADR 0031, unchanged) and promotes afterward as a
separate step.
