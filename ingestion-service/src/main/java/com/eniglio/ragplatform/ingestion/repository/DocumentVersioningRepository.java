package com.eniglio.ragplatform.ingestion.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * docs/adr/0058-document-versioning.md. Deliberately separate from {@link
 * DocumentSharingRepository} despite both querying {@code vector_store}: {@link
 * DocumentVersioningService} reuses {@link DocumentSharingRepository#findChunks}/
 * {@link DocumentSharingRepository#updateMetadata} directly for the "read/rewrite a
 * document's chunk metadata" operations versioning and sharing both need — this
 * repository only adds the one query neither already had.
 */
@Repository
public class DocumentVersioningRepository {

    // A document with no documentGroupId of its own is its own group (see
    // DocumentIngestionService's javadoc) - counting distinct documentIds within a
    // group this way naturally includes the group's original document even though
    // its own metadata never explicitly names the group by id.
    private static final String COUNT_VERSIONS_IN_GROUP_SQL = """
            SELECT COUNT(DISTINCT metadata->>'documentId')
            FROM vector_store
            WHERE tenant_id = ?
              AND (metadata->>'documentGroupId' = ? OR metadata->>'documentId' = ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public DocumentVersioningRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Counts every distinct {@code documentId} that belongs to {@code documentGroupId}
     * - either because it explicitly names that group, or because it IS that group's
     * original document (which never writes its own id into {@code documentGroupId}).
     */
    public int countVersionsInGroup(String documentGroupId, String tenantId) {
        Integer count = jdbcTemplate.queryForObject(COUNT_VERSIONS_IN_GROUP_SQL, Integer.class,
                tenantId, documentGroupId, documentGroupId);
        return count == null ? 0 : count;
    }
}
