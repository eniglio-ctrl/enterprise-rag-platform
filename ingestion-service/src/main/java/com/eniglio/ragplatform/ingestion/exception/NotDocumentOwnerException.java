package com.eniglio.ragplatform.ingestion.exception;

/**
 * docs/ROADMAP.md item #24: only a document's own owner (or a tenant ADMIN, checked
 * separately by each caller) may take an owner-only action on it — originally just
 * changing sharing settings, and now also superseding it with a new version
 * (docs/adr/0058-document-versioning.md), hence the generic {@code action} parameter
 * rather than a hardcoded "change its sharing settings" message.
 */
public class NotDocumentOwnerException extends RuntimeException {

    public NotDocumentOwnerException(String documentId, String action) {
        super("Only the owner of document " + documentId + " may " + action);
    }
}
