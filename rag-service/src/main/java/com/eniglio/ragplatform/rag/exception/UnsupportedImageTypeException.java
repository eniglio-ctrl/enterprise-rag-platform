package com.eniglio.ragplatform.rag.exception;

/**
 * Thrown when an image attached to a question declares a content type outside the
 * small set this service accepts (PNG/JPEG/GIF/WebP) — mirrors ingestion-service's
 * {@code UnsupportedDocumentTypeException} (ADR 0022), mapped to 415.
 */
public class UnsupportedImageTypeException extends RuntimeException {

    public UnsupportedImageTypeException(String message) {
        super(message);
    }
}
