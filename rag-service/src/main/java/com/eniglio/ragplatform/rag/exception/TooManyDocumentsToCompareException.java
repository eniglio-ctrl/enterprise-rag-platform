package com.eniglio.ragplatform.rag.exception;

/**
 * docs/adr/0057-document-comparison.md: thrown when a comparison request's
 * {@code documentIds} exceeds {@code rag.document-comparison.max-documents} - a
 * configurable runtime bound, not a Bean Validation constraint, since a
 * {@code @Size(max = ...)} annotation can't reference a dynamically configured
 * property value.
 */
public class TooManyDocumentsToCompareException extends RuntimeException {

    public TooManyDocumentsToCompareException(String message) {
        super(message);
    }
}
