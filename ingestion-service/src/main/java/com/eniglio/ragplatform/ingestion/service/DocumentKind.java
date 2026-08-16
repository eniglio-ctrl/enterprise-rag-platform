package com.eniglio.ragplatform.ingestion.service;

/**
 * What {@link DocumentReaderFactory} needs to do with an already-validated upload.
 * Coarser than the file extension: several image/audio extensions share the IMAGE
 * or AUDIO handling path (a vision model / Whisper), same as before this class
 * existed — this just makes that grouping a compile-time concept instead of two
 * parallel {@code Set<String>} extension lists.
 */
public enum DocumentKind {
    PDF,
    DOCX,
    MARKDOWN,
    TEXT,
    IMAGE,
    AUDIO
}
