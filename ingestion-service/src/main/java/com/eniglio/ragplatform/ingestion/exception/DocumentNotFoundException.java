package com.eniglio.ragplatform.ingestion.exception;

public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException(String documentId) {
        super("Document not found: " + documentId);
    }
}
