# ADR 0007: Tenancy data contract (`tenantId` + `userId`), without real authentication yet

## Status
Accepted

## Context
Every future feature that isolates data per customer or per user — hybrid search
result scoping, chat-service conversation ownership, and eventually a real
`auth-service` — needs chunks in the vector store to already carry a tenant/user
identity. Deciding that data format after those features exist would mean
retrofitting a migration and rewriting their filters; deciding it now, while there is
still a single logical "default" tenant, costs nothing.

This ADR only fixes the **shape of the data and the isolation boundary**. It
deliberately does not add authentication: there is no login, no token, no verification
that a caller is who it claims to be. `auth-service` (a later phase) replaces the
*source* of `tenantId`/`userId` — from a trusted header to a validated JWT claim —
without changing the contract itself.

## Decision
- `ingestion-service` reads two optional request headers on `POST /api/v1/documents`:
  `X-Tenant-Id` and `X-User-Id`, both defaulting to `"default"` when absent. Every
  chunk's metadata gets `tenantId` and `userId` alongside the existing `documentId`/
  `source`/`chunkIndex`/`contentType`/`ingestedAt` keys (same camelCase convention,
  same `metadata` jsonb column — no schema migration needed).
- `rag-service` reads `X-Tenant-Id` (same default) on `/api/v1/ask`, `/api/v1/chat`
  and `/api/v1/diagrams`, and restricts retrieval with
  `SearchRequest.builder().filterExpression(...)` built via Spring AI's
  `FilterExpressionBuilder.eq("tenantId", tenantId)`.
- **The isolation boundary is the tenant, not the user.** A search for tenant A
  returns every chunk ingested by any user of tenant A — `userId` is recorded on each
  chunk for attribution/audit, but is not part of the query filter. This matches how
  most multi-tenant knowledge bases behave in practice (a company's employees share
  the company's documents); scoping retrieval further down to `userId` would make the
  "shared team knowledge base" use case impossible without extra work later, whereas
  going the other way (adding a `userId` filter on top of the existing `tenantId` one)
  is a small, backward-compatible change if a future use case needs it.
- The `web-ui` sends no tenancy headers today; every request implicitly resolves to
  `"default"`/`"default"`, so current single-tenant behavior is unchanged.

## Consequences
- No schema migration: `filterExpression` operates on the existing `metadata` jsonb
  column. The filter is **not backed by an index** — acceptable at portfolio/demo
  scale, but a real deployment with many tenants would want `tenantId` promoted to an
  indexed column. Left for Fase 2a (Flyway), which is already touching the schema for
  the hybrid-search `tsvector` column — bundling both avoids two separate schema stops.
- No enforcement: any caller can claim any `tenantId`/`userId` via headers. This is a
  known, accepted gap until `auth-service` (Fase 4) makes the identity trustworthy.
- `RagQueryService.answer/diagram/ask` and `DocumentIngestionService.ingest` all
  gained a required parameter as part of this change — every existing call site
  (controllers and tests) was updated accordingly.
- **Backward-compatibility gap found during manual testing**: chunks ingested before
  this change have no `tenantId` key at all, and `metadata->>'tenantId' = 'default'`
  does not match a missing key — those documents silently stopped being retrievable
  the moment the filter shipped (confirmed: a question that worked before the change
  returned "não encontrei informação suficiente" after it). Fixed for the existing
  local demo data with a one-off backfill:
  `UPDATE vector_store SET metadata = (metadata::jsonb || '{"tenantId": "default",
  "userId": "default"}'::jsonb)::json WHERE metadata->>'tenantId' IS NULL;`. This is a
  real migration concern, not just a local quirk — Fase 2a (Flyway) should ship this
  same backfill as a proper versioned migration instead of a one-off manual `UPDATE`,
  so it also applies to anyone else's existing data, not just this machine's.
