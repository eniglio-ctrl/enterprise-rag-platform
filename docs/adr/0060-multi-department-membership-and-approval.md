# ADR 0060: Multi-department membership, self-service requests, and invite-time role grant

## Status
Accepted

## Context
Testing ADR 0059's department sharing live, the user asked for three direct
evolutions of it:

1. A user should be able to belong to **several departments**, not just one.
2. Joining a department should go through a **request + admin-approval**
   step, not be immediate — requestable both at registration (via an
   invitation into an existing tenant) and later, self-service, from the
   user's own Settings page. A rejected request is simply removed, no
   history kept (explicit product decision).
3. The tenant's bootstrap admin should be able to **grant the ADMIN role
   directly when creating an invitation**, not only via the existing
   promote/demote action after the fact.

This builds directly on ADR 0059's model: `departments(id, tenant_id,
name)` (admin-created registry), `users.department` (single nullable
column), a single-value `department` JWT claim, and
`DocumentVisibility.isVisibleTo` checking that one department against a
document's `sharedWithDepartments`. Since that model shipped only minutes
before this request, with no real usage riding on it, the same convention
already established this session applies again: **clean replacement, no
backwards-compatibility shim**.

## Decision

### `user_departments`: a membership table with state, not a column
New table, replacing `users.department` outright:

```sql
CREATE TABLE user_departments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users (id),
    department_id UUID NOT NULL REFERENCES departments (id),
    status TEXT NOT NULL,               -- 'PENDING' or 'APPROVED'
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX ON user_departments (user_id, department_id);
```

`status` has no `CHECK` constraint — validated in the service layer, the
same convention already used for the department name itself in ADR 0059.
Unlike `users.department`/the JWT claim/document metadata (all name-based,
by design — see ADR 0059's own reasoning about not making `rag-service`/
`ingestion-service` resolve ids), `department_id` here is a real foreign
key: this table is 100% internal to `auth-service`, no other service ever
reads it, so there's no cross-service name-vs-id tension to navigate.

A rejected request is a plain `DELETE` — the unique index means requesting
again later is just a fresh `INSERT`. `V6__user_departments.sql` also
migrates any pre-existing `users.department` value into an `APPROVED` row
(a formality; this feature had no real usage yet) and drops the column.

### Request/approve/reject, plus an admin bulk-replace
- **Self-service request** (`POST /api/v1/auth/users/me/department-requests`,
  any authenticated member, always acting on their own account): inserts
  `PENDING` rows. Idempotent — a name already `PENDING`/`APPROVED` for the
  caller is silently skipped, since the endpoint accepts several names at
  once and a partial-conflict error would be awkward. An unknown
  department name 404s.
- **Admin approve** (`POST /users/{userId}/department-requests/{departmentId}/approve`)
  and **reject** (`DELETE /users/{userId}/department-requests/{departmentId}`,
  204, no body) — both admin-only, tenant-scoped the same defensive way
  `updateRole` already is.
- **Admin bulk-replace** (`PATCH /users/{userId}/departments`, body
  `{"departments": [...]}`) — replaces the target's *entire* approved set
  in one call, the same "replace the whole list" shape
  `DocumentSharingService.updateSharing` already uses for a document's
  `sharedDepartments`. This supersedes ADR 0059's single-value
  `PATCH .../department`. If the admin's new set includes a department the
  user already had a `PENDING` request for, that row is silently promoted
  to `APPROVED` via `INSERT ... ON CONFLICT (user_id, department_id) DO
  UPDATE` — an admin's direct action always takes precedence over an open
  request for the same department.
- **Admin pending queue** (`GET /api/v1/auth/department-requests`) — every
  pending request in the tenant at once, for the approval screen.
- **Own profile** (`GET /api/v1/auth/users/me`, any authenticated member) —
  new endpoint. Nothing let a non-admin see their own department state
  before this; `GET /users` is admin-only, and the JWT claim can be stale
  relative to a just-issued approval (see below). Needed for the
  self-service "My departments" screen to show accurate state without
  requiring a re-login to check.

### `GET /api/v1/auth/departments` is no longer admin-only
Listing department *names* was relaxed from admin-only to any
authenticated tenant member — the self-service request screen (both at
registration and in Settings) needs to show the pickable list, and a
department name isn't sensitive. Creating a department is still
admin-only, unchanged.

### Registration-time department picker, without an account yet
Registration is unauthenticated, so the frontend needs the tenant's
department list before the invitee has any JWT. New, deliberately
unauthenticated endpoint:

```
GET /api/v1/auth/invitations/{token}/departments
```

A valid, unexpired, not-yet-redeemed invitation token *is* the
authorization here — proof of a specific, single-use, time-limited grant,
arguably a higher bar than "any tenant member." `InvitationService`'s
token validation (not-found/redeemed/expired) was extracted into a shared
`validate(token)` used by both this and `redeem`, so the three error
messages stay in exactly one place.

`RegisterRequest` gained `requestedDepartments` (optional). On the
invitation path, `AuthService.register()` resolves each name and inserts
`PENDING` rows — inside the same `@Transactional` boundary the method
already had, so a typo'd department name rolls back the whole
registration rather than silently dropping the request. The no-invitation
bootstrap path ignores this field entirely regardless of what's sent
(defense in depth — no department registry exists yet at that point
anyway, and the frontend never shows the picker there).

### JWT claim: `department` (nullable string) → `departments` (always-present array)
```java
public static List<String> departments(Jwt jwt) {
    List<String> raw = jwt.getClaimAsStringList("departments");
    return raw == null ? List.of() : raw;
}
```
Unlike the single-value claim it replaces (deliberately omitted when
`null` — see ADR 0059), this one is **always** set on the token, even as
an empty array — "no departments" is now representable as `[]`, so there's
no reason to omit the claim. This mirrors `role()`'s own "never absent"
precedent instead. `TokenService.issueToken` now takes the caller's
approved department names explicitly (`User` no longer carries department
at all — it moved out into `user_departments` entirely), resolved via
`UserDepartmentRepository.findApprovedNamesByUserId` in both `register()`
(always empty for a brand-new account — nothing is ever immediately
approved) and `login()` (the user's real current approved set).

**Same accepted limitation as before, now compounded**: a token already
in someone's browser doesn't pick up a newly approved department until
they log in again — the exact caveat ADR 0059 already documented for the
single-value claim, and before that for `role` (ADR 0047). No forced
re-login mechanism exists anywhere in this project. `GET /users/me` exists
specifically so the "My departments" screen doesn't have to rely on a
claim that might be stale.

### `DocumentVisibility`: intersection, not equality
```java
public static boolean isVisibleTo(Map<String, Object> metadata, String userId, List<String> departments) {
    if (!RESTRICTED.equals(metadata.get(VISIBILITY_KEY))) return true;
    if (userId != null && userId.equals(metadata.get(OWNER_KEY))) return true;
    if (contains(metadata.get(SHARED_WITH_KEY), userId)) return true;
    return intersects(metadata.get(SHARED_WITH_DEPARTMENTS_KEY), departments);
}
```
Visible if *any* of the caller's departments appears in the document's
`sharedWithDepartments` — one match is enough, the same way `sharedWith`
only needs the caller's own id to appear once. An empty `departments` list
never matches anything (no accidental grant to someone in no department).

### Every retrieval path re-threaded — same mechanical rigor as ADR 0059
`HybridSearchService` (`search`/`findBySource`/`findByDocumentId`/
`filterVisible`), every one of `RagQueryService`'s ~15 `department`-taking
signatures (`ask`/`answer`/`diagram`/`retrieve`/`summarizeDocument`/
`generateFaq`/`compareDocuments`, public and private), both controllers
(`ChatController`, `DocumentInsightController`), and `DocumentLookupTool`
(via `ToolContext`, key renamed `"department"` → `"departments"`, value a
`List<String>`) all changed from `String department` to `List<String>
departments`. This is the identical shape of rethread ADR 0059 performed
to introduce the parameter in the first place — no path was skipped, per
that ADR's own "enforced uniformly across every retrieval path"
requirement, which still holds.

`ingestion-service` is untouched: `sharedWithDepartments` on the document
side is still a list of department *names* a document is shared with —
this ADR only changes how many departments the *caller* belongs to, not
how a document is shared.

### Invite-time role grant, restricted to existing admins
`CreateInvitationRequest` gained an optional `role` (default `MEMBER`).
`InvitationController` allows `role: "ADMIN"` only when the caller is
already an ADMIN (403 otherwise, reusing `NotTenantAdminException`) —
`MEMBER` invitations stay open to any tenant member, preserving ADR
0031's flat "any member can invite" model exactly. Without this
restriction, a plain member could hand a friend admin rights just by
inviting them at that role, which the flat model was never meant to
allow. `Invitation` persists the role it was created with; `AuthService
.register()` uses `invitation.role()` on redemption instead of the old
hardcoded `Role.MEMBER`.

### `web-ui`
- **Register**: a department checkbox picker, hidden until the invitation
  token field has a value, then populated from the new unauthenticated
  token-scoped endpoint. Never shown on the bootstrap (no-token) path.
- **Invite a teammate**: a Member/Admin role select, hidden for non-admin
  callers (the backend still enforces the rule either way — this just
  avoids showing a control that would 403 for most people).
- **Team**: the single department `<select>` per row became a checkbox
  group, still one Save button per row, now PATCHing the whole approved
  set via `/departments`.
- **New "Pending department requests" card** (admin-only): the tenant-wide
  queue with Approve/Reject per row.
- **New "My departments" card** (every authenticated member): approved
  departments as chips, pending requests as visibly-marked chips, and a
  checkbox picker + "Request" button for everything not yet
  requested/approved.

## Consequences

### Verified
- `AuthIT` (32 tests total in the class): register-with-departments ends
  up `PENDING`, not in the JWT until approved + next login; admin approve
  grants it on the next login's token; admin reject removes it with no
  trace; self-service request after registration; requesting an
  already-pending/approved name is a no-op, not an error; requesting an
  unknown name 404s; `GET /departments` now works for a plain `MEMBER`
  (the old admin-only assertion for this specific endpoint was flipped,
  not just added to); admin bulk-replace; `role: "ADMIN"` on an
  invitation 403s for a non-admin caller and succeeds (redeeming as
  `ADMIN`) for an admin caller; a plain `MEMBER` invitation still works
  from a non-admin caller (regression coverage for ADR 0031).
- `DocumentVisibilityTest`: multi-department intersection — a caller in
  `["Financeiro","TI"]` sees a document shared with just `"Financeiro"`; a
  caller in `["TI"]` alone does not.
- `DocumentLookupToolTest`: `departments` reaches the tool only via
  server-side `ToolContext`, never something the model could supply.
- `ChatQueryIT`: a real HTTP round trip proving a user in two departments
  still sees a document shared with only one of them, alongside the
  original single-department visible/invisible cases from ADR 0059.
- Full suite across all four touched modules green (auth-service,
  platform-common, rag-service, ingestion-service unaffected but
  reverified), no regressions.
- Manual verification against the real running stack (see roadmap entry).

### Not addressed
No audit trail for rejected requests (explicit decision) — a rejection is
indistinguishable from "never asked" after the fact. Department rename/
delete is still out of scope, same as ADR 0059. No forced-re-login
mechanism — an approval's effect is visible in `GET /users/me` and the
web-ui immediately, but the JWT itself (and therefore
`rag-service`/`ingestion-service`'s enforcement) only picks it up on the
next login.
