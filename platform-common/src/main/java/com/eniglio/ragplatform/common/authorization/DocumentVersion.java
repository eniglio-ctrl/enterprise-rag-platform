package com.eniglio.ragplatform.common.authorization;

import java.util.Map;

/**
 * docs/adr/0058-document-versioning.md — same shape and reasoning as {@link
 * DocumentVisibility}: shared between {@code ingestion-service} (writes it) and
 * {@code rag-service} (reads it at retrieval time), and the default matters as much
 * as the check itself. {@code "documentGroupId"} links versions of the same logical
 * document (defaults to the document's own {@code documentId} when it's never been
 * superseded); {@code "isLatestVersion"} marks which one retrieval should prefer by
 * default.
 */
public final class DocumentVersion {

    public static final String DOCUMENT_GROUP_ID_KEY = "documentGroupId";
    public static final String IS_LATEST_VERSION_KEY = "isLatestVersion";

    private DocumentVersion() {
    }

    /**
     * {@code true} unless {@code metadata}'s own {@code "isLatestVersion"} is
     * exactly {@code false} — a missing key (every chunk ingested before this
     * feature existed, or any document that was never superseded) means "this is
     * the only/latest version", not the opposite. Checking for exactly {@code
     * Boolean.FALSE} rather than requiring {@code Boolean.TRUE} is deliberate: it's
     * what makes the missing-key case default to visible.
     */
    public static boolean isLatestVersion(Map<String, Object> metadata) {
        return !Boolean.FALSE.equals(metadata.get(IS_LATEST_VERSION_KEY));
    }
}
