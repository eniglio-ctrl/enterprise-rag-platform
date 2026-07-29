package com.eniglio.ragplatform.rag.exception;

/**
 * Thrown when an image attached to a question has a recognized extension/content
 * type but its actual bytes don't match — mirrors ingestion-service's
 * {@code InvalidUploadException} (ADR 0022): a sibling concept to "unsupported type
 * entirely" (415), mapped to 422 instead.
 */
public class InvalidImageAttachmentException extends RuntimeException {

    public InvalidImageAttachmentException(String message) {
        super(message);
    }
}
