package com.eniglio.ragplatform.ingestion.dto;

import java.util.List;

/**
 * docs/adr/0058-document-versioning.md: {@code documentGroupId} links this document to
 * every other version of the same logical document ({@code documentId} itself when
 * it's never been superseded); {@code version} is 1-based, computed by ingestion order
 * within the group; {@code isLatestVersion} is what a normal question actually
 * retrieves by default.
 */
public record DocumentSummary(String documentId, String source, String ownerId, String visibility,
        List<String> sharedWith, String documentGroupId, int version, boolean isLatestVersion,
        List<String> sharedDepartments) {
}
