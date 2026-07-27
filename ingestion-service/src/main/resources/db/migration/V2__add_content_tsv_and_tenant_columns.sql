-- Bundles two schema changes that were both going to need their own migration later
-- (Fase 2b hybrid search, ADR 0007 tenancy) into one stop instead of two.
--
-- All three columns are GENERATED ALWAYS ... STORED, not populated by application
-- code or a trigger: Spring AI's PgVectorStore builds its own INSERT statement and has
-- no idea these columns exist, so anything that depended on the app filling them in
-- would silently stay NULL for every row inserted after this migration. Generated
-- columns are computed by Postgres itself from `content`/`metadata` on every
-- insert/update, so they can never drift out of sync, and backfill existing rows
-- automatically at migration time.
--
-- tenant_id/user_id come from the same `metadata->>'tenantId'/'userId'` keys the
-- application already filters on via Spring AI's filterExpression (ADR 0007) — these
-- indexed columns aren't used by that query path yet (Spring AI's filter DSL only
-- targets the metadata jsonb column), but will be once Fase 2b's hybrid search issues
-- raw SQL that can actually use them.

ALTER TABLE vector_store
    ADD COLUMN IF NOT EXISTS tenant_id text
        GENERATED ALWAYS AS (metadata->>'tenantId') STORED,
    ADD COLUMN IF NOT EXISTS user_id text
        GENERATED ALWAYS AS (metadata->>'userId') STORED,
    ADD COLUMN IF NOT EXISTS content_tsv tsvector
        GENERATED ALWAYS AS (to_tsvector('simple', coalesce(content, ''))) STORED;

CREATE INDEX IF NOT EXISTS vector_store_tenant_id_idx ON vector_store (tenant_id);
CREATE INDEX IF NOT EXISTS vector_store_user_id_idx ON vector_store (user_id);
CREATE INDEX IF NOT EXISTS vector_store_content_tsv_idx ON vector_store USING GIN (content_tsv);
