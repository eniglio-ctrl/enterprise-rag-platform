# ADR 0011: Flyway takes over schema creation from PgVectorStore's auto-init

## Status
Accepted

## Context
Both services relied on Spring AI's `PgVectorStore` auto-init
(`spring.ai.vectorstore.pgvector.initialize-schema`) to create the `vector_store`
table — `ingestion-service` had it `true` (it "owns" the schema, per ADR 0002),
`rag-service` `false` (read-only). That's fine for a table that never changes shape,
but Fase 2b (hybrid search) and the tenancy filter (ADR 0007) both want real, indexed
columns instead of unindexed `metadata->>'key'` lookups — and hand-editing the schema
outside of version control isn't something a portfolio project (or any project) should
be doing going forward.

**Critical ordering constraint, confirmed by hitting it, not by reading docs first**:
`FlywayMigrationInitializer` runs during `DataSource` initialization, *before*
`PgVectorStore`'s own bean exists. If both were left enabled, a migration doing
`ALTER TABLE vector_store ...` would run before `PgVectorStore` ever created the table,
and fail on a table that doesn't exist yet. The fix is binary: Flyway owns 100% of
schema creation, and `initialize-schema` is `false` in **both** services (only
`ingestion-service` changed — `rag-service` already had it `false`).

## Decision
- `ingestion-service` gets `flyway-core` + `flyway-database-postgresql`.
  `rag-service` does not — it never migrates schema, only reads from a table
  `ingestion-service` maintains (same read/write split as ADR 0002).
- `V1__baseline_vector_store.sql`: the exact DDL `PgVectorStore` used to create,
  captured with `pg_dump --schema-only -t vector_store` against a real running
  instance rather than reconstructed from memory (dimensions, HNSW index, cosine
  distance are all *checked*, not assumed). Every statement is `IF NOT EXISTS`, so
  this is safe to run against a database `PgVectorStore` already initialized, not just
  a brand-new one.
- `V2__add_content_tsv_and_tenant_columns.sql`: bundles the Fase 2b `content_tsv`
  column and the ADR 0007 `tenant_id`/`user_id` promotion into one migration instead of
  two, since both were already planned and touching the same table twice was avoidable.
  All three are `GENERATED ALWAYS ... STORED` columns (computed from
  `content`/`metadata` by Postgres itself), not populated by application code or a
  trigger — `PgVectorStore` builds its own `INSERT` and has no idea these columns
  exist, so anything relying on the app to fill them in would silently stay `NULL` for
  every row inserted after this migration. Generated columns can never drift out of
  sync and backfill existing rows automatically at migration time.
- These new columns aren't wired into the actual query path yet — Spring AI's
  `filterExpression` DSL only ever targets the `metadata` jsonb column, it has no
  concept of arbitrary extra table columns. They exist, indexed, ready for Fase 2b's
  hybrid search (which will issue raw SQL that *can* use them) — this ADR is schema
  preparation, not a behavior change to `RagQueryService`.

## Consequences — one real problem found running this against actual data, not a fresh database

`./mvnw clean verify`'s Testcontainers-based integration tests passed immediately —
because they always start from a completely empty database, they never exercise the
one case that matters most for an existing project: **adopting Flyway into a database
that already has a matching, non-Flyway-created schema.** Restarting the real
`ingestion-service` against the actual long-running dev Postgres volume (already
populated by `PgVectorStore`'s old auto-init, and holding real demo data) failed
immediately on boot:

> `Found non-empty schema(s) "public" but no schema history table. Use baseline() or
> set baselineOnMigrate to true to initialize the schema history table.`

This is Flyway correctly refusing to guess whether an existing, un-tracked schema
matches what it's about to manage — exactly the scenario this migration exists for,
and exactly the kind of thing that only shows up against a database with real history,
not a disposable test container. Fixed with `spring.flyway.baseline-on-migrate: true`
and `baseline-version: 0`: this only takes effect when the target schema is non-empty
(a fresh database just runs migrations normally, no baseline), and version `0` means
"nothing is considered applied yet" so `V1` and `V2` both still run for real —
`V1` safely no-ops against a table that already matches it, `V2` actually adds the new
generated columns and indexes.

**Verified for real, not just via the automated test suite**: restarted
`ingestion-service` against the live dev database (19 real rows of demo content, not
a synthetic fixture). `flyway_schema_history` shows the baseline plus both migrations
applied successfully; all 19 pre-existing rows survived with `tenant_id`/`user_id`/
`content_tsv` correctly backfilled from their existing `metadata`; a new document
uploaded after the migration got the same columns populated correctly on insert
(proving the generated-column mechanism, not just the one-time backfill); `rag-service`
answered a real question afterward with correct citations, unaffected by any of this.
