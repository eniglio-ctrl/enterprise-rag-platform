package com.eniglio.ragplatform.ingestion.exception;

/** docs/ROADMAP.md item #24: only a document's own owner may change its sharing settings. */
public class NotDocumentOwnerException extends RuntimeException {

    public NotDocumentOwnerException(String documentId) {
        super("Only the owner of document " + documentId + " may change its sharing settings");
    }
}
