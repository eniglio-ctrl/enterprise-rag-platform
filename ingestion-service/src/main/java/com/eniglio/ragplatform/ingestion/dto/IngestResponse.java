package com.eniglio.ragplatform.ingestion.dto;

/**
 * docs/adr/0058-document-versioning.md: {@code documentGroupId} links this document
 * to every other version of the same logical document ({@code documentId} itself
 * when this is the only version so far); {@code version} is 1-based, the count of
 * versions in that group after this ingestion.
 */
public record IngestResponse(
        String documentId,
        String source,
        int pageCount,
        int chunkCount,
        String documentGroupId,
        int version
) {
}
