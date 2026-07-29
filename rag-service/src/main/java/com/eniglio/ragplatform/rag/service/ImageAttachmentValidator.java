package com.eniglio.ragplatform.rag.service;

import com.eniglio.ragplatform.rag.exception.InvalidImageAttachmentException;
import com.eniglio.ragplatform.rag.exception.UnsupportedImageTypeException;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Confirms an image attached to a question is really an image before it's ever
 * handed to a vision model — same magic-byte reasoning as ingestion-service's
 * {@code UploadValidationService} (ADR 0022), scoped down to just the four image
 * formats this ephemeral attachment path supports (no PDF/DOCX/audio/text — the
 * user explicitly chose "image only" for this feature).
 */
@Service
public class ImageAttachmentValidator {

    private record ImageFormat(MimeType mimeType, Predicate<byte[]> signature) {
    }

    private static final Map<String, ImageFormat> ACCEPTED_CONTENT_TYPES = Map.of(
            "image/png", new ImageFormat(MimeType.valueOf("image/png"),
                    bytes -> startsWith(bytes, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}, 0)),
            "image/jpeg", new ImageFormat(MimeType.valueOf("image/jpeg"),
                    bytes -> startsWith(bytes, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, 0)),
            "image/gif", new ImageFormat(MimeType.valueOf("image/gif"), ImageAttachmentValidator::isGif),
            "image/webp", new ImageFormat(MimeType.valueOf("image/webp"),
                    bytes -> isRiff(bytes) && startsWith(bytes, "WEBP".getBytes(StandardCharsets.US_ASCII), 8))
    );

    public ValidatedImage validate(MultipartFile image) {
        String declaredContentType = image.getContentType();
        ImageFormat format = declaredContentType == null ? null : ACCEPTED_CONTENT_TYPES.get(declaredContentType);
        if (format == null) {
            throw new UnsupportedImageTypeException(
                    "Unsupported image content type: " + declaredContentType);
        }

        byte[] bytes = readBytes(image);
        if (!format.signature().test(bytes)) {
            throw new InvalidImageAttachmentException("Image content does not match its declared type");
        }

        return new ValidatedImage(bytes, format.mimeType());
    }

    private static boolean isGif(byte[] bytes) {
        return startsWith(bytes, "GIF87a".getBytes(StandardCharsets.US_ASCII), 0)
                || startsWith(bytes, "GIF89a".getBytes(StandardCharsets.US_ASCII), 0);
    }

    private static boolean isRiff(byte[] bytes) {
        return startsWith(bytes, "RIFF".getBytes(StandardCharsets.US_ASCII), 0);
    }

    private static boolean startsWith(byte[] data, byte[] prefix, int offset) {
        if (data.length < offset + prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[offset + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read attached image", e);
        }
    }
}
