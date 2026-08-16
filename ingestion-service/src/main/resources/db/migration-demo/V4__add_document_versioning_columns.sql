-- ADR 0020: byte-for-byte identical to db/migration/V4 — same reasoning as V2's own
-- copy in this directory (nothing here touches the embedding column's dimension, and
-- Flyway resolves migrations per-location, so the demo profile needs its own copy).

ALTER TABLE vector_store
    ADD COLUMN IF NOT EXISTS document_group_id text
        GENERATED ALWAYS AS (metadata->>'documentGroupId') STORED,
    ADD COLUMN IF NOT EXISTS is_latest_version boolean
        GENERATED ALWAYS AS (
            CASE WHEN metadata->>'isLatestVersion' = 'false' THEN false ELSE true END
        ) STORED;

CREATE INDEX IF NOT EXISTS vector_store_document_group_id_idx ON vector_store (document_group_id);
CREATE INDEX IF NOT EXISTS vector_store_is_latest_version_idx ON vector_store (is_latest_version);
