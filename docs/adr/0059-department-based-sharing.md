# ADR 0059: Department-based document sharing

## Status
Accepted

## Context
Direct user request: a department registry the admin creates ("quero
cadastrar os departamentos"), and the ability to restrict a document to a
whole department instead of only "tenant-wide" or "an explicit list of
individual users picked one at a time." This was already the documented
gap behind item #12 ("fine-grained per-document permissions") in
`docs/PRODUCT-DIFFERENTIATION-ROADMAP.md`'s own status table — today's
ABAC model (`DocumentVisibility`, ADR 0046) has exactly two tiers:
`TENANT` (everyone) or `RESTRICTED` (owner + `sharedWith`, a hand-picked
list of user ids). There is no group/department concept anywhere: not on
`User`, not in document metadata.

Confirmed directly with the user before implementing: departments are
**admin-created** (not a fixed list like Legal/HR/Finance/IT, not a full
CRUD screen with rename/delete — just create and list), and the access
management UI **extends the two existing pages** (Settings > Team;
Documents > Manage sharing) rather than a new dedicated page.

## Decision

### Departments are a name registry, not a foreign key
New `departments(id, tenant_id, name)` table in `auth-service`, unique
per `(tenant_id, lower(name))` (same case-insensitive-uniqueness pattern
as `users_email_idx`). This table exists **only** to give an admin a
controlled list of names to pick from when assigning a user or sharing a
document — every actual consumer (`users.department`, the JWT claim,
`sharedWithDepartments` in document metadata) stores the department's
**name** as plain text, never an id. This keeps the whole system
name-based end to end: the JWT claim is a simple string, and neither
`ingestion-service` nor `rag-service` ever needs to resolve an id by
calling back into `auth-service` — those services already don't call
each other (see `web-ui`'s own client-side join between users and
documents in `loadAdminPanel`), and a name-based design preserves that.

`DepartmentService`/`DepartmentController` mirror `UserManagementService`/
`UserController`'s existing shape exactly — `createDepartment`/
`listDepartments`, both admin-only via the same `requireAdmin` check
every other admin action already uses.

### `User` gains a `department` field, validated against the registry
`users.department` (nullable `TEXT`, no `CHECK` — validity is enforced in
`UserManagementService.updateDepartment` against the `departments` table,
not at the database level, mirroring how visibility/sharing metadata is
already validated in the service layer rather than with SQL constraints).
Unlike `updateRole`, there's no "can't change your own" restriction —
nothing dangerous about an admin setting their own department.

### JWT claim, same pattern as `role`, different default
`TokenService.issueToken` adds a `department` claim when the user has
one (omitted entirely when absent, not set to `null` — Nimbus's builder
doesn't need a placeholder key for "no value"). `JwtClaims.department(jwt)`
returns `null` when absent — deliberately **not** defaulting to some
placeholder the way `role()` defaults to `MEMBER`: no department is a
real, common state, not a gap to paper over.

**Accepted limitation, not discovered later**: a user already logged in
keeps their old token until they log in again, so a department
assignment doesn't take effect until then — the exact same caveat that
already existed when the `role` claim was first introduced (ADR 0047).
No forced-re-login mechanism exists anywhere in this project.

### `DocumentVisibility`: an additive tier, not a new enum value
```java
public static boolean isVisibleTo(Map<String, Object> metadata, String userId, String department) {
    if (!RESTRICTED.equals(metadata.get(VISIBILITY_KEY))) return true;
    if (userId != null && userId.equals(metadata.get(OWNER_KEY))) return true;
    if (contains(metadata.get(SHARED_WITH_KEY), userId)) return true;
    return department != null && contains(metadata.get(SHARED_WITH_DEPARTMENTS_KEY), department);
}
```
A document can be `RESTRICTED`, shared with specific users, **and**
shared with whole departments, all at once — `sharedWithDepartments` is
a second independent way in, not a replacement for `sharedWith`. A
`null` caller department never matches anything, so a user with no
department assigned is never accidentally granted access through this
path.

### Every retrieval path threads `department` — no shortcut
`HybridSearchService.filterVisible`/`search`/`findBySource`/
`findByDocumentId` all gained a `department` parameter, which propagates
through **every** public method of `RagQueryService` (`ask`, `answer`,
`diagram`, `summarizeDocument`, `generateFaq`, `compareDocuments`,
`retrieve`), both controllers (`ChatController`, `DocumentInsightController`,
extracting `JwtClaims.department(jwt)`), and `DocumentLookupTool` (via a
new `"department"` entry in the `ToolContext` map `RagQueryService`
already builds for `userId`/`tenantId`). ADR 0046 already established
"enforced uniformly across every retrieval path" as a hard requirement
for this ABAC model — a single path that forgot the new parameter would
be a real authorization leak, not a cosmetic gap, so there was no
partial-rollout option here.

`findByDocumentId` (summarize/FAQ/compare) is the one method that,
consistent with its existing behavior for `userId`, still enforces the
check but was never a candidate for a *different* kind of exemption — an
exact `documentId` lookup already IS "ask against a specific document,"
the same reasoning that already applied before this ADR.

### `ingestion-service`: `sharedDepartments` mirrors `sharedWith` exactly
`UpdateSharingRequest`/`SharingResponse`/`DocumentSummary` all gained a
`sharedDepartments` field alongside the existing `sharedWith`.
`DocumentSharingService.updateSharing` writes the new metadata key the
same way it already writes `sharedWith`. `DocumentSharingRepository`'s
`SELECT_DOCUMENTS_SQL` gained one more JSON column
(`metadata->'sharedWithDepartments'`); the private `parseSharedWith`
helper was renamed to `parseStringList` and reused for both columns
rather than duplicated, since both are "a JSON array of strings or
absent" with identical parsing. No new migration needed here — like
`sharedWith` before it, this lives entirely inside the existing JSON
`metadata` blob.

### `web-ui`: extends the two existing admin surfaces
- **Settings**: a new "Departments" card (admin-only) with a create
  form and a list — the literal "cadastrar os departamentos" ask.
  `renderAdminUsers` gained a department `<select>` (populated from the
  fetched registry) plus its own Save button per row, same interaction
  shape as the existing promote/demote button.
- **Documents > Manage sharing**: `renderAdminDocuments` gained a second,
  independently-labeled checklist (departments) next to the existing
  per-user one, both inside the same `RESTRICTED`-only shared-with
  block. The save handler now sends `sharedDepartments` alongside
  `sharedWith` in the same PATCH request.
- `loadAdminPanel` fetches `GET /api/v1/auth/departments` in parallel
  with the two calls it already made, passing the list to both render
  functions — no new decoupling concern, same client-side join pattern
  already established for owner emails.

## Consequences

### Verified
- Full suite across all four touched modules (`auth-service`,
  `platform-common`, `ingestion-service`, `rag-service`) green: 271
  tests total, no regressions.
- `AuthIT`: create + list departments; duplicate name rejected
  case-insensitively (409); non-admin blocked from both endpoints (403);
  assign and clear a teammate's department; assigning an unregistered
  department name 404s; two different tenants can register the exact
  same department name without conflict.
- `DocumentVisibilityTest`: a department-shared document is visible to a
  user in that department and invisible to one in a different (or no)
  department; department and per-user sharing grant access
  independently of each other.
- `DocumentLookupToolTest`: `department` reaches `findBySource` via
  `ToolContext` only, never something the model could supply itself —
  same boundary already proven for `tenantId`/`userId`.
- `ChatQueryIT`: a document restricted to "Financeiro" is invisible to a
  user with no department and to a user in "TI", visible to a user in
  "Financeiro" — a real HTTP round trip against the live retrieval
  pipeline, not just the filter logic in isolation.
- Manual verification against the real running stack (see roadmap entry
  for the full walkthrough).

### Not addressed
No rename/delete for departments (matches exactly what was asked for —
"cadastrar," not manage) — since departments are never removed, no
bulk-reassignment or orphan-cleanup logic was needed either.

### Worth stating explicitly, since it's easy to assume otherwise
Access is evaluated against the caller's *current* department at
request time, not snapshotted at share-time. Adding a user to
"Financeiro" later automatically grants them access to every document
already shared with that department — no separate re-sharing step
needed, and no stale access left behind if they're later moved to a
different department either.
