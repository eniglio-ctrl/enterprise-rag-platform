package com.eniglio.ragplatform.rag.service;

import org.springframework.util.MimeType;

/**
 * An image attachment that {@link ImageAttachmentValidator} has already confirmed
 * matches its declared content type — the only form {@link VisionDescriptionService}
 * ever receives, same reasoning as ingestion-service's {@code ValidatedUpload}
 * (ADR 0022). Never persisted: this attachment is ephemeral, described once to help
 * answer a single question, then discarded.
 */
public record ValidatedImage(byte[] bytes, MimeType mimeType) {
}
