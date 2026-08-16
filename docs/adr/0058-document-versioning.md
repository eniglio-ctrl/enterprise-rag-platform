# ADR 0058: Document versioning

## Status
Accepted

## Context
Reuploading a file with the same name today creates a brand-new
`documentId`, totally disconnected from any earlier upload of "the same"
document (`DocumentIngestionService.doIngest` always mints a fresh
`UUID.randomUUID()`). There's no relationship between versions, no way
for a normal question to prefer the newest content by default, and no
way to still ask against an older version explicitly.
`docs/PRODUCT-DIFFERENTIATION-ROADMAP.md` Phase 6's own "done when":
uploading a revised file linked to a prior document shows both as
versions of one logical document, and a normal question retrieves only
the latest version by default.

## Decision

### Two new metadata keys, same shape/defaults as `DocumentVisibility`
`platform-common`'s new `DocumentVersion` (`common/authorization/`,
alongside `DocumentVisibility` — both checked together at retrieval
time):
- `documentGroupId`: links every version of the same logical document.
  Deliberately left **unwritten** for a brand-new, never-superseded
  document — its own `documentId` already IS its group, the same
  "absent key = implicit default" convention `DocumentVisibility`
  already established, and it keeps the overwhelmingly common case (a
  document that's never versioned) metadata-neutral.
- `isLatestVersion`: absent (or `true`) means "this is the version
  retrieval should prefer"; explicitly `false` means superseded.
  `DocumentVersion.isLatestVersion` checks `!Boolean.FALSE.equals(...)`,
  not `Boolean.TRUE.equals(...)` — every chunk ingested before this
  feature existed has no such key at all, and that absence must mean
  "latest", not the opposite.

### Write path: reuses `DocumentSharingRepository` directly, not a copy
`DocumentSharingRepository.findChunks`/`updateMetadata` (raw
`JdbcTemplate`, one `UPDATE` per chunk in a loop) already does exactly
what "rewrite every chunk of a documentId" needs — it was written for
sharing changes, but nothing about it is sharing-specific. The new
`DocumentVersioningService.ingestNewVersion` calls it directly instead
of duplicating the same SQL into a bigger `DocumentVersioningRepository`;
that new repository ended up holding only the one query neither service
already had (`countVersionsInGroup`, used to compute the new version's
1-based `version` number).

Order of operations in `ingestNewVersion`:
1. `findChunks(supersedesDocumentId, tenantId)` — empty → 404
   (`DocumentNotFoundException`, already existed).
2. Owner or tenant ADMIN — the exact same check
   `DocumentSharingService.updateSharing` already enforces, reusing
   `NotDocumentOwnerException` (now generalized to accept an `action`
   parameter, since "change its sharing settings" no longer describes
   every caller).
3. **Only the current latest version can be superseded.** If the target
   is already superseded, reject with a new `NotLatestVersionException`
   → 409. This keeps the version chain strictly linear — no branching
   tree — a deliberate simplicity trade-off, not a discovered limitation.
4. Ingest the new file via `DocumentIngestionService.ingestNewVersion`,
   passing `documentGroupId`/`visibility`/`sharedWith` **inherited from
   the version being superseded** (read from its own chunk metadata
   before superseding it) — not the `TENANT`-visible default a brand
   new upload always gets. Without this, a restricted document would
   silently become tenant-wide visible the moment someone uploaded a
   revision.
5. Rewrite every old chunk's metadata with `isLatestVersion=false`.
6. Return an `IngestResponse` carrying `documentGroupId`/`version`.

### Endpoint: an optional query param on the existing upload route
`POST /api/v1/documents` gains `@RequestParam(value = "supersedes",
required = false) String supersedes` rather than a new
`POST /api/v1/documents/{id}/versions` route — it reuses 100% of the
existing upload validation/pipeline, and only branches to
`DocumentVersioningService` when the parameter is present. Same
"optional param on an existing route" convention `DocumentInsightController`
already uses for `model`.

`IngestResponse` gained `documentGroupId`/`version`; `DocumentSummary`
(the admin document-listing DTO) gained the same two fields plus
`isLatestVersion`. `DocumentSharingRepository.findDocuments`'s SQL adds
`document_group_id`/`is_latest_version` (`COALESCE`/`CASE`, same
`GENERATED ALWAYS AS (...) STORED`-style expressions as the new Flyway
columns below) and an `ingested_at` column used only to compute each
document's 1-based `version` — grouped and ranked in Java rather than a
SQL window function, since the underlying timestamp is JSON-derived
text, not an indexed column, and this table is small enough per tenant
that the readability of doing it in Java outweighs any cost.

### Flyway V4: generated columns exist for direct-SQL use, not for the retrieval filter
Same pattern as V2's `tenant_id`/`user_id`:
```sql
ALTER TABLE vector_store
    ADD COLUMN IF NOT EXISTS document_group_id text
        GENERATED ALWAYS AS (metadata->>'documentGroupId') STORED,
    ADD COLUMN IF NOT EXISTS is_latest_version boolean
        GENERATED ALWAYS AS (
            CASE WHEN metadata->>'isLatestVersion' = 'false' THEN false ELSE true END
        ) STORED;
```
These columns are indexed for consistency and possible future direct-SQL
use, but the retrieval-time "latest version only" filter below does
**not** query them.

### Retrieval filter: Java post-filter, mirroring `filterVisible` exactly
`HybridSearchService.filterVisible` already solved this same class of
problem for ABAC: Spring AI's `FilterExpressionBuilder` DSL only
expresses scalar equality/in/nin against metadata JSON keys, with no way
to say "a missing key means true". A SQL `WHERE is_latest_version`
clause would be correct for the full-text leg (raw JDBC, sees the
generated column), but the vector leg's `SearchRequest.filterExpression`
only ever sees the raw `metadata` JSON — never the generated column — so
filtering only one leg in SQL would apply the rule inconsistently
between legs. The new `filterLatestVersion` mirrors `filterVisible`'s
exact shape and position, applied to both legs' candidate lists before
RRF fusion:
```java
List<Document> vectorResults = filterLatestVersion(filterVisible(vectorStoreGateway.search(vectorRequest), userId));
List<Document> textResults = filterLatestVersion(filterVisible(fullTextSearch(question, tenantId, poolSize), userId));
```
Same accepted trade-off `filterVisible` already carries: the SQL
candidate pool itself doesn't know about this filter, so a tenant with
many superseded versions could in theory crowd a genuine match out of a
small pool. Documented here as a conscious trade-off, not discovered
later.

`findBySource` (backs `DocumentLookupTool`, the Multi-LLM tool-calling
path) gets the same filter — nothing stops two different `documentId`s
from sharing a `source` filename without ever going through
`supersedes`, and a tool-invoked lookup is "a normal question" in the
same sense `search()` is. `findByDocumentId` (backs
summarize/FAQ/compare, ADR 0052/0057) **deliberately does not** — an
exact `documentId` lookup already IS "ask against a specific version",
the exact mechanism the roadmap requires to keep working after this
change.

### Sharing stays keyed by `documentId`, inherited forward, not managed per-group
`DocumentSharingService`/`PATCH .../sharing` are unchanged — each
version still has its own independent sharing settings in principle,
but because a new version inherits its predecessor's `visibility`/
`sharedWith` at ingestion time (step 4 above), the common case ("a
restricted document stays restricted after a new version") is already
correct without any cascade-on-share logic. No cross-version
propagation was added; this is a deliberate scope decision, not an
oversight.

### web-ui: version badge + "Nova versão" button, no new component
`renderAdminDocuments` now sorts documents by `documentGroupId` then
`version` so every version of the same logical document renders
together and in order, and shows a `v{{n}} (atual)`/`v{{n}}` badge per
item. A document's own list item gains a "Nova versão" button (only for
its current latest version — superseding an already-superseded one is
rejected server-side with 409 anyway, so the option isn't offered) that
opens a file picker and reuses the exact same multipart `POST` the main
upload form already issues, just with `?supersedes={documentId}`
appended. No dedicated version-history component — same "reuse the
existing flat list" approach ADR 0057's compare checkboxes already took.

## Consequences

### Verified
- Full `./mvnw -pl ingestion-service,rag-service,platform-common -am
  test` suite green (the one pre-existing failure, `RagQualityBenchmark`'s
  real-Ollama quality-score threshold, is unrelated to this phase and
  gated behind `-Dbenchmark=true`, not part of normal CI).
- `DocumentVersionTest`: absent key defaults to latest; explicit
  `false` does not; explicit `true` does.
- `HybridSearchServiceTest.filterLatestVersion*`: mirrors `fuseWithRrf`'s
  own direct-list-testing style, no JDBC involved.
- `DocumentIngestionIT`: uploading a new version links it to the
  original by `documentGroupId` and bumps `version` to 2; an
  already-superseded version cannot be superseded again (409); a
  non-owner cannot version someone else's document (403); superseding
  an unknown document 404s.
- `ChatQueryIT`: a normal question against two versions of a document
  in the same tenant retrieves citations from the latest version only,
  never the superseded one; `summarize` still reaches a superseded
  version directly by its own `documentId`.

### Not addressed
No UI for browsing a group's full version history beyond the flat,
version-ordered admin list; no way to roll back to an older version as
the new latest (would require un-superseding, out of scope); no
propagation of a sharing change made on one version to its siblings.
