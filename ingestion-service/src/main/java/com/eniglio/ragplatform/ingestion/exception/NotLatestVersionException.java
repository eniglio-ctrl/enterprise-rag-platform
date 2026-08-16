package com.eniglio.ragplatform.ingestion.exception;

/**
 * docs/adr/0058-document-versioning.md: only the current latest version of a
 * document can be superseded by a new one — rejecting an attempt to supersede an
 * already-superseded version keeps the version chain linear (no branching), a
 * simpler mental model than a version tree.
 */
public class NotLatestVersionException extends RuntimeException {

    public NotLatestVersionException(String documentId) {
        super("Document " + documentId + " is not the latest version of its group and cannot be superseded");
    }
}
