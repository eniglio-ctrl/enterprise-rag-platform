package com.eniglio.ragplatform.ingestion.service;

import org.springframework.util.MimeType;

/**
 * A file that {@link UploadValidationService} has already confirmed is what it
 * claims to be — extension, declared MIME type, and actual bytes all agree.
 * {@link DocumentReaderFactory} only ever accepts this type, never a raw
 * {@code MultipartFile}, so "read an unvalidated upload" is a compile error rather
 * than a convention someone can forget to follow.
 */
public record ValidatedUpload(byte[] bytes, String filename, MimeType mimeType, DocumentKind kind) {
}
