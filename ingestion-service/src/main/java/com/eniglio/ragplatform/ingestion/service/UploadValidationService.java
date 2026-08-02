package com.eniglio.ragplatform.ingestion.service;

import com.eniglio.ragplatform.ingestion.config.IngestionProperties;
import com.eniglio.ragplatform.ingestion.exception.InvalidUploadException;
import com.eniglio.ragplatform.ingestion.exception.UnsupportedDocumentTypeException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Confirms an uploaded file's extension, declared MIME type, and actual bytes all
 * agree before any parser (Tika/PDFBox/Ollama/Whisper) ever sees the content —
 * the single place this project decides whether an upload is trustworthy.
 * <p>
 * Byte verification uses a small, hand-rolled magic-byte table rather than Tika's
 * own content-sniffing API. {@link DocumentReaderFactory}'s javadoc already
 * documents Tika's detection as unreliable for plain-text formats like .md — using
 * it here too would undermine exactly the check this class exists to perform.
 */
@Service
public class UploadValidationService {

    private static final Logger log = LoggerFactory.getLogger(UploadValidationService.class);

    private record FormatSpec(DocumentKind kind, MimeType mimeType, Predicate<byte[]> signature) {
    }

    private static final Map<String, FormatSpec> FORMATS = Map.ofEntries(
            Map.entry(".pdf", new FormatSpec(DocumentKind.PDF, MimeType.valueOf("application/pdf"),
                    bytes -> startsWith(bytes, "%PDF-".getBytes(StandardCharsets.US_ASCII), 0))),
            Map.entry(".docx", new FormatSpec(DocumentKind.DOCX,
                    MimeType.valueOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                    bytes -> startsWith(bytes, new byte[]{0x50, 0x4B, 0x03, 0x04}, 0))),
            Map.entry(".md", new FormatSpec(DocumentKind.MARKDOWN, MimeType.valueOf("text/markdown"),
                    UploadValidationService::isPlainText)),
            Map.entry(".txt", new FormatSpec(DocumentKind.TEXT, MimeType.valueOf("text/plain"),
                    UploadValidationService::isPlainText)),
            Map.entry(".png", new FormatSpec(DocumentKind.IMAGE, MimeType.valueOf("image/png"),
                    bytes -> startsWith(bytes,
                            new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}, 0))),
            Map.entry(".jpg", new FormatSpec(DocumentKind.IMAGE, MimeType.valueOf("image/jpeg"),
                    UploadValidationService::isJpeg)),
            Map.entry(".jpeg", new FormatSpec(DocumentKind.IMAGE, MimeType.valueOf("image/jpeg"),
                    UploadValidationService::isJpeg)),
            Map.entry(".gif", new FormatSpec(DocumentKind.IMAGE, MimeType.valueOf("image/gif"),
                    UploadValidationService::isGif)),
            Map.entry(".webp", new FormatSpec(DocumentKind.IMAGE, MimeType.valueOf("image/webp"),
                    bytes -> isRiff(bytes) && startsWith(bytes, "WEBP".getBytes(StandardCharsets.US_ASCII), 8))),
            Map.entry(".mp3", new FormatSpec(DocumentKind.AUDIO, MimeType.valueOf("audio/mpeg"),
                    UploadValidationService::isMp3)),
            Map.entry(".wav", new FormatSpec(DocumentKind.AUDIO, MimeType.valueOf("audio/wav"),
                    bytes -> isRiff(bytes) && startsWith(bytes, "WAVE".getBytes(StandardCharsets.US_ASCII), 8))),
            Map.entry(".m4a", new FormatSpec(DocumentKind.AUDIO, MimeType.valueOf("audio/mp4"),
                    bytes -> startsWith(bytes, "ftyp".getBytes(StandardCharsets.US_ASCII), 4))),
            Map.entry(".ogg", new FormatSpec(DocumentKind.AUDIO, MimeType.valueOf("audio/ogg"),
                    bytes -> startsWith(bytes, "OggS".getBytes(StandardCharsets.US_ASCII), 0))),
            Map.entry(".flac", new FormatSpec(DocumentKind.AUDIO, MimeType.valueOf("audio/flac"),
                    bytes -> startsWith(bytes, "fLaC".getBytes(StandardCharsets.US_ASCII), 0))),
            Map.entry(".webm", new FormatSpec(DocumentKind.AUDIO, MimeType.valueOf("audio/webm"),
                    bytes -> startsWith(bytes, new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3}, 0)))
    );

    private final IngestionProperties ingestionProperties;
    private final MeterRegistry meterRegistry;

    public UploadValidationService(IngestionProperties ingestionProperties, MeterRegistry meterRegistry) {
        this.ingestionProperties = ingestionProperties;
        this.meterRegistry = meterRegistry;
    }

    public ValidatedUpload validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw reject("empty_file", null, "Uploaded file is empty", InvalidUploadException::new);
        }

        String filename = normalize(file.getOriginalFilename());
        FormatSpec spec = FORMATS.entrySet().stream()
                .filter(entry -> filename.endsWith(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> reject("unsupported_extension", filename,
                        "Unsupported file type: " + filename, UnsupportedDocumentTypeException::new));

        String declaredContentType = file.getContentType();
        if (declaredContentType == null
                || !ingestionProperties.allowedContentTypes().contains(declaredContentType)) {
            throw reject("unsupported_content_type", filename,
                    "Unsupported content type '" + declaredContentType + "' for file " + filename,
                    UnsupportedDocumentTypeException::new);
        }
        if (kindOf(declaredContentType) != spec.kind()) {
            throw reject("content_type_mismatch", filename,
                    "Declared content type '" + declaredContentType + "' does not match file extension: "
                            + filename, InvalidUploadException::new);
        }

        byte[] bytes = readBytes(file);
        if (!spec.signature().test(bytes)) {
            throw reject("signature_mismatch", filename, "File content does not match its declared type: "
                    + filename, InvalidUploadException::new);
        }

        log.info("Upload accepted: filename={} kind={}", filename, spec.kind());
        return new ValidatedUpload(bytes, filename, spec.mimeType(), spec.kind());
    }

    /**
     * Every rejection path converges here (Security Phase 5): one structured audit
     * log line and one {@code security.upload.rejected} increment per reason, before
     * building whichever of the two exception types the caller actually throws.
     * Never logs file content, only the filename and the specific reason.
     */
    private RuntimeException reject(String reason, String filename, String message,
            Function<String, RuntimeException> exceptionFactory) {
        log.warn("Upload rejected: reason={} filename={}", reason, filename);
        Counter.builder("security.upload.rejected").tag("reason", reason).register(meterRegistry).increment();
        return exceptionFactory.apply(message);
    }

    private static DocumentKind kindOf(String contentType) {
        if (contentType.startsWith("image/")) {
            return DocumentKind.IMAGE;
        }
        if (contentType.startsWith("audio/")) {
            return DocumentKind.AUDIO;
        }
        return switch (contentType) {
            case "application/pdf" -> DocumentKind.PDF;
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DocumentKind.DOCX;
            case "text/markdown" -> DocumentKind.MARKDOWN;
            case "text/plain" -> DocumentKind.TEXT;
            default -> null;
        };
    }

    private static boolean isPlainText(byte[] bytes) {
        boolean matchesABinaryFormat = FORMATS.values().stream()
                .filter(spec -> spec.kind() != DocumentKind.MARKDOWN && spec.kind() != DocumentKind.TEXT)
                .anyMatch(spec -> spec.signature().test(bytes));
        if (matchesABinaryFormat) {
            return false;
        }
        try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static boolean isJpeg(byte[] bytes) {
        return startsWith(bytes, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, 0);
    }

    private static boolean isGif(byte[] bytes) {
        return startsWith(bytes, "GIF87a".getBytes(StandardCharsets.US_ASCII), 0)
                || startsWith(bytes, "GIF89a".getBytes(StandardCharsets.US_ASCII), 0);
    }

    private static boolean isRiff(byte[] bytes) {
        return startsWith(bytes, "RIFF".getBytes(StandardCharsets.US_ASCII), 0);
    }

    private static boolean isMp3(byte[] bytes) {
        if (startsWith(bytes, "ID3".getBytes(StandardCharsets.US_ASCII), 0)) {
            return true;
        }
        // MPEG frame sync: the first 11 bits of a frame header are all set.
        return bytes.length >= 2 && bytes[0] == (byte) 0xFF && (bytes[1] & 0xE0) == 0xE0;
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

    private static String normalize(String originalFilename) {
        return originalFilename == null ? "" : originalFilename.toLowerCase(Locale.ROOT);
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file", e);
        }
    }
}
