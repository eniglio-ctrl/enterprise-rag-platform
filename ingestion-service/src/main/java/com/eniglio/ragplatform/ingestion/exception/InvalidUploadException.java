package com.eniglio.ragplatform.ingestion.exception;

/**
 * Thrown when a file's extension and declared MIME type are recognized (unlike
 * {@link UnsupportedDocumentTypeException}) but its actual bytes don't match what
 * either one claims — a truncated/corrupted file, or one deliberately disguised as
 * another type. Deliberately a sibling of {@link UnsupportedDocumentTypeException},
 * not a subclass: the two map to different HTTP statuses (422 vs 415), and a
 * subclass relationship would risk one {@code @ExceptionHandler} silently catching
 * the other.
 */
public class InvalidUploadException extends RuntimeException {

    public InvalidUploadException(String message) {
        super(message);
    }
}
