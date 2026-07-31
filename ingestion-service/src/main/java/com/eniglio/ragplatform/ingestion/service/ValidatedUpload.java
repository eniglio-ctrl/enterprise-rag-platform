package com.eniglio.ragplatform.ingestion.service;

import java.util.Arrays;
import java.util.Objects;
import org.springframework.util.MimeType;

/**
 * A file that {@link UploadValidationService} has already confirmed is what it
 * claims to be — extension, declared MIME type, and actual bytes all agree.
 * {@link DocumentReaderFactory} only ever accepts this type, never a raw
 * {@code MultipartFile}, so "read an unvalidated upload" is a compile error rather
 * than a convention someone can forget to follow.
 * <p>
 * {@code equals}/{@code hashCode}/{@code toString} are overridden because a record's
 * generated versions compare {@code byte[]} by reference, not content (SonarCloud
 * S6218) — two uploads with identical bytes would otherwise compare unequal.
 */
public record ValidatedUpload(byte[] bytes, String filename, MimeType mimeType, DocumentKind kind) {

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ValidatedUpload other)) {
            return false;
        }
        return Arrays.equals(bytes, other.bytes)
                && filename.equals(other.filename)
                && mimeType.equals(other.mimeType)
                && kind == other.kind;
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(bytes), filename, mimeType, kind);
    }

    @Override
    public String toString() {
        return "ValidatedUpload[bytes=%d bytes, filename=%s, mimeType=%s, kind=%s]"
                .formatted(bytes.length, filename, mimeType, kind);
    }
}
