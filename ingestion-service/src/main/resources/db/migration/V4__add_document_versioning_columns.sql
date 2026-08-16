-- docs/adr/0058-document-versioning.md. Same GENERATED ALWAYS ... STORED pattern as
-- V2's tenant_id/user_id: these columns exist for direct-SQL consistency and possible
-- future use, but the actual "latest version only" retrieval filter runs in Java
-- (HybridSearchService.filterLatestVersion), not via a WHERE on is_latest_version here
-- - the vector search leg only has access to the raw metadata JSON, not this generated
-- column, so filtering both legs consistently means applying the same rule in Java
-- after retrieval, the same reason ABAC visibility filtering already works that way.
--
-- is_latest_version's CASE (not a plain boolean cast) mirrors
-- DocumentVersion.isLatestVersion's own Java logic exactly: only an explicit "false"
-- string in the metadata JSON means superseded - a missing key (every chunk ingested
-- before this migration) computes true here too.

ALTER TABLE vector_store
    ADD COLUMN IF NOT EXISTS document_group_id text
        GENERATED ALWAYS AS (metadata->>'documentGroupId') STORED,
    ADD COLUMN IF NOT EXISTS is_latest_version boolean
        GENERATED ALWAYS AS (
            CASE WHEN metadata->>'isLatestVersion' = 'false' THEN false ELSE true END
        ) STORED;

CREATE INDEX IF NOT EXISTS vector_store_document_group_id_idx ON vector_store (document_group_id);
CREATE INDEX IF NOT EXISTS vector_store_is_latest_version_idx ON vector_store (is_latest_version);
