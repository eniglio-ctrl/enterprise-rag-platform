package com.eniglio.ragplatform.rag.service;

import java.util.Arrays;
import org.springframework.util.MimeType;

/**
 * An image attachment that {@link ImageAttachmentValidator} has already confirmed
 * matches its declared content type — the only form {@link VisionDescriptionService}
 * ever receives, same reasoning as ingestion-service's {@code ValidatedUpload}
 * (ADR 0022). Never persisted: this attachment is ephemeral, described once to help
 * answer a single question, then discarded.
 * <p>
 * {@code equals}/{@code hashCode}/{@code toString} are overridden because a record's
 * generated versions compare {@code byte[]} by reference, not content (SonarCloud
 * S6218) — two attachments with identical bytes would otherwise compare unequal.
 */
public record ValidatedImage(byte[] bytes, MimeType mimeType) {

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ValidatedImage other)) {
            return false;
        }
        return Arrays.equals(bytes, other.bytes) && mimeType.equals(other.mimeType);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(bytes) + mimeType.hashCode();
    }

    @Override
    public String toString() {
        return "ValidatedImage[bytes=%d bytes, mimeType=%s]".formatted(bytes.length, mimeType);
    }
}
