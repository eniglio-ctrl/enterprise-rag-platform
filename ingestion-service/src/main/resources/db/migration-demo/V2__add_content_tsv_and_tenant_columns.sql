-- ADR 0020: byte-for-byte identical to db/migration/V2 — nothing here touches the
-- embedding column's dimension, so there's no reason for the demo schema to diverge.
-- Kept as its own copy (not a shared file) because Flyway resolves migrations
-- per-location, and the demo profile's spring.flyway.locations points only at
-- db/migration-demo — a migration living solely under db/migration would never be
-- picked up here.

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
