# ADR 0046: Resource-level authorization — a lightweight ABAC model, not RBAC

## Status
Accepted

## Context
`docs/ROADMAP.md` item #24, full account in
`docs/PRODUCTION-READINESS-ROADMAP.md` Phase 8. Authorization before this
was tenant-only (ADR 0007): any authenticated user within a tenant could
read every document that tenant owned, with no concept of ownership,
sharing, or per-document restriction. The roadmap explicitly flagged this
as needing a design decision — RBAC (roles determine access) vs. ABAC
(attributes/policies determine access) — before any implementation, since
the two solve genuinely different shapes of "who can share what with whom."

The user's own decision: ABAC, specifically an ownership + visibility +
explicit-share-list model, not roles. The reasoning behind that choice —
confirmed against the roadmap's own "done when" wording ("one of whom is
explicitly not granted access to a *specific document*") — is that this is
fundamentally a per-resource sharing question, not a capability question.
RBAC answers "can this user delete any document" well; it doesn't
naturally answer "can this specific user see this specific document" without
degrading into a role per document-user pair, which stops being RBAC in
any meaningful sense.

## Decision

### The model: owner, visibility, sharedWith — all in existing chunk metadata
`platform-common`'s new `DocumentVisibility` class (shared between
`ingestion-service` and `rag-service`, so both agree on the exact same
string values and metadata key names — a typo in one service silently
failing to match the other would otherwise be a real, hard-to-notice
authorization bug) defines:
- `owner` — reuses the `"userId"` key `DocumentIngestionService` already
  stamped on every chunk since before this feature existed (ADR 0007). No
  new column, no new concept — the uploader was always implicitly the
  closest thing to an owner; this just makes it load-bearing.
- `visibility` — `TENANT` (default) or `RESTRICTED`. `TENANT` preserves the
  original, unchanged behavior exactly: every chunk ingested before this
  feature existed has no `"visibility"` key at all, and `DocumentVisibility
  .isVisibleTo` treats any non-`RESTRICTED` value — including a missing key
  — as visible tenant-wide. Nothing already ingested silently became more
  restrictive.
- `sharedWith` — an optional JSON array of specific user IDs, only
  consulted when `visibility` is `RESTRICTED`.

No new database columns or Flyway migration: this is a Java-level
authorization check reading fields already present in each chunk's
existing `metadata` JSON blob, not an extension to the `vector_store`
schema's generated columns. That was a deliberate simplification — see
"Enforcement is Java-side, not SQL-side" below for why.

### A new write path: `PATCH /api/v1/documents/{documentId}/sharing`
Deliberately a separate action from upload, not an upload-time choice —
every document still starts `TENANT`-visible (`DocumentIngestionService`
unchanged in that respect), keeping the upload endpoint's own contract
exactly as it was. Owned by `ingestion-service` (the service that already
owns document lifecycle), not `rag-service`. Only the document's owner may
call it — checked against every chunk's shared `"userId"` metadata key, not
against tenant membership alone (being in the same tenant already lets you
upload; it doesn't make you the owner of someone else's document).

`vector_store` has one row per chunk, not one row per document — there is
no single-row "the document" to update. `DocumentSharingRepository` selects
every chunk sharing the target `documentId` (scoped by `tenant_id` too),
and `DocumentSharingService` rewrites each one's `metadata` JSON in a plain
loop. A real uploaded document's chunk count is small (low tens at most,
confirmed by this project's own ingestion pipeline) — no batching
infrastructure was worth adding for this scale.

### Enforcement is Java-side, not SQL-side, and applied identically to all three retrieval paths
`HybridSearchService` gained a `userId` parameter on both `search` (the
hybrid vector+full-text path) and `findBySource` (the exact-match lookup
`DocumentLookupTool` uses, Multi-LLM Phase 9/ADR 0035). Both legs' raw
result lists are filtered through `DocumentVisibility.isVisibleTo` in Java,
*before* RRF fusion runs — a restricted chunk the caller can't see never
occupies one of `topK`'s slots in the fused result at all, rather than
being fused in and then discarded.

This was a deliberate choice over pushing the check into SQL on both legs:
Spring AI's `FilterExpressionBuilder` DSL (used for the vector leg's
existing `tenantId` filter) supports `in`/`nin` — checking a scalar
metadata field against a list of candidate values — not "does this
document's own `sharedWith` array contain this one caller ID," the
opposite direction this check actually needs. Rather than fighting a DSL
not designed for this, or writing a third, different SQL shape for the
vector leg's own PGVector-generated query, every leg's results are checked
the same way, in one place (`HybridSearchService.filterVisible`), against
the same `Map<String, Object>` metadata every `Document` object already
carries. The `tenant_id` SQL filter stays exactly as it was on all three
paths — this only ever narrows *within* a tenant a caller already belongs
to, never replaces that boundary.

### `userId` threaded through `ToolContext`, same as `tenantId`
Without this, `DocumentLookupTool` — the model-invokable `@Tool` that
fetches a whole document by filename — would have been a way to read a
restricted document's content bypassing the exact check a normal question
is already subject to. `userId` reaches it the same server-side-only way
`tenantId` already did (ADR 0035's own boundary: the model never sees or
controls either), never a model-supplied parameter.

### Backward-compatible method overloads, not a mechanical signature rewrite
`RagQueryService`'s public `ask`/`answer`/`diagram`/`retrieve` methods
gained new overloads accepting `userId`; the original overloads (used
throughout the existing test suite and both quality benchmarks, none of
which exercise restricted-visibility documents) were kept, delegating to
the new ones with `userId = null` — which `DocumentVisibility.isVisibleTo`
treats correctly as "no additional restriction beyond tenant," identical to
today's pre-#24 behavior. This avoided a 40-plus-call-site mechanical
signature change across every existing test for equivalent real behavior;
`ChatController` (the actual, real enforcement point) calls the new
`userId`-aware overloads exclusively.

## Consequences

### Verified for real, exactly matching the roadmap's own "done when"
> "two users in the same tenant, one of whom is explicitly not granted
> access to a specific document, can be shown for real — via the actual
> running stack, not just a unit test — that the restricted user's
> questions never retrieve chunks from that document, while an explicitly
> shared or public document remains visible to both."

Against the real running local stack: registered a real user (creating a
tenant), used the real invitation flow (ADR 0031) to register a second real
user into the *same* tenant, uploaded a real document as the first user,
restricted it via the new sharing endpoint, and confirmed:
- The second user's question about it returned no citation for that
  document — genuinely invisible, not just unranked.
- The first user (owner) still saw it.
- After sharing it explicitly with the second user via the same endpoint,
  the second user's identical question now returned the citation too.

### Automated tests at every layer
`DocumentIngestionIT` (ingestion-service, real Postgres via Testcontainers):
the owner can restrict and share their own document (real `UPDATE` across
every chunk row, verified via the response body); a non-owner in the same
tenant gets `403`; an unknown `documentId` gets `404`; an invalid
`visibility` value gets `400`. `ChatQueryIT` (rag-service, same real-Postgres
pattern): a restricted document is invisible to a non-owner/non-shared
user but visible to its owner and to a user it was explicitly shared with —
via the real `/api/v1/chat` HTTP path, not a direct service-layer call.
`DocumentLookupToolTest` gained a test proving `userId` reaches
`findBySource` from `ToolContext` the same verifiable way `tenantId`
already does. ingestion-service: 47 → 51 tests; rag-service: 76 → 78 tests.

### Two real bugs found and fixed by writing these tests, not assumed away
1. `@PathVariable String documentId` (no explicit name) failed at runtime
   with `IllegalArgumentException: Name for argument ... not available via
   reflection` — this project's build doesn't compile with `-parameters`,
   so Spring couldn't infer the path variable's name. Every other
   `@PathVariable` in this codebase already names itself explicitly
   (`chat-service`'s `ConversationController`); this one didn't, and only a
   real MockMvc round trip surfaced it — a unit test mocking the service
   layer directly would never have caught it.
2. `UPDATE vector_store SET metadata = ? WHERE id = ?` failed with a real
   Postgres `operator does not exist: uuid = character varying` — `id` is
   `uuid` (V1 migration), and Postgres refuses the implicit cast from a
   plain string JDBC parameter. Fixed with an explicit `id = ?::uuid`,
   confirmed against the real Testcontainers Postgres, not assumed correct
   from reading the SQL.

### Scope not built: no document-listing/management UI
There is still no `GET /api/v1/documents` (list) endpoint, and `web-ui` has
no UI for choosing a document's visibility or shared-with list — this
phase built the enforcement mechanism and its one write path (the sharing
endpoint), verified via `curl`/tests, not a user-facing sharing workflow.
A real product would need a document-management screen; that was
explicitly out of scope for closing this roadmap item, which asked for the
authorization model and its real enforcement, not a UI on top of it.

**Update (ADR 0047)**: this gap is now closed for tenant ADMINs
specifically — a new per-tenant `ADMIN` role, `GET /api/v1/documents`
(admin-only), and a `web-ui` panel built on top of both. The sharing
enforcement and write path documented above are unchanged; ADR 0047 only
adds who else may call them and how they're discovered.
