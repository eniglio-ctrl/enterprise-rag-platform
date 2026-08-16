package com.eniglio.ragplatform.ingestion.service;

import com.eniglio.ragplatform.ingestion.config.IngestionProperties;
import com.eniglio.ragplatform.ingestion.exception.InvalidUploadException;
import com.eniglio.ragplatform.ingestion.exception.UnsupportedDocumentTypeException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadValidationServiceTest {

    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private static final IngestionProperties.Docx DOCX_LIMITS = new IngestionProperties.Docx(100, 10_000_000);

    private static final IngestionProperties.UrlImport URL_IMPORT_LIMITS =
            new IngestionProperties.UrlImport(26_214_400L, Duration.ofSeconds(10));

    private static final IngestionProperties.PdfOcr PDF_OCR_LIMITS = new IngestionProperties.PdfOcr(20, 20);

    private static final IngestionProperties PROPERTIES = new IngestionProperties(800, List.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/markdown",
            "text/plain",
            "image/png",
            "image/jpeg",
            "image/gif",
            "image/webp",
            "audio/mpeg",
            "audio/wav",
            "audio/x-wav",
            "audio/mp4",
            "audio/x-m4a",
            "audio/ogg",
            "audio/flac",
            "audio/webm"), DOCX_LIMITS, URL_IMPORT_LIMITS, PDF_OCR_LIMITS);

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final UploadValidationService service = new UploadValidationService(PROPERTIES, meterRegistry);

    // --- rejections ---

    @Test
    void rejectsEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

        assertThatThrownBy(() -> service.validate(file))
                .isInstanceOf(InvalidUploadException.class);
    }

    @Test
    void rejectsUnknownExtension() {
        MockMultipartFile file = new MockMultipartFile("file", "archive.zip", "application/zip", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> service.validate(file))
                .isInstanceOf(UnsupportedDocumentTypeException.class)
                .hasMessageContaining("archive.zip");
    }

    @Test
    void rejectsDeclaredContentTypeNotInAllowList() {
        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/exe", pdfBytes());

        assertThatThrownBy(() -> service.validate(file))
                .isInstanceOf(UnsupportedDocumentTypeException.class);
    }

    @Test
    void rejectsContentTypeThatDoesNotMatchTheExtensionsKindEvenIfOtherwiseAllowed() {
        // "audio/mpeg" is a real, allow-listed type — just not for a ".pdf" file.
        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "audio/mpeg", pdfBytes());

        assertThatThrownBy(() -> service.validate(file))
                .isInstanceOf(InvalidUploadException.class);
    }

    @Test
    void rejectsCorruptedDocx() {
        MockMultipartFile file = new MockMultipartFile("file", "report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "not actually a zip".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.validate(file))
                .isInstanceOf(InvalidUploadException.class);
    }

    // --- ADR 0022's "known gap", closed: a real ZIP renamed to .docx, and zip-bomb protection ---

    @Test
    void rejectsAnArbitraryZipRenamedToDocx() {
        // A real, valid ZIP archive (passes the PK\x03\x04 signature check) that is
        // not a DOCX at all - no word/document.xml anywhere inside it. This is
        // exactly the gap ADR 0022 flagged: before the fix, this passed validation
        // and reached Tika.
        MockMultipartFile file = new MockMultipartFile("file", "not-a-docx.docx", DOCX_MIME,
                docxBytesWithEntries(Map.of("readme.txt", "just a normal zip, not a Word document")));

        assertThatThrownBy(() -> service.validate(file))
                .isInstanceOf(InvalidUploadException.class);

        assertThat(meterRegistry.get("security.upload.rejected").tag("reason", "docx_missing_document_xml")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void rejectsADocxWithTooManyZipEntries() {
        IngestionProperties strictProperties = new IngestionProperties(800, PROPERTIES.allowedContentTypes(),
                new IngestionProperties.Docx(2, 10_000_000), URL_IMPORT_LIMITS, PDF_OCR_LIMITS);
        UploadValidationService strictService = new UploadValidationService(strictProperties, meterRegistry);
        MockMultipartFile file = new MockMultipartFile("file", "report.docx", DOCX_MIME,
                docxBytesWithEntries(Map.of(
                        "word/document.xml", "<w:document/>",
                        "word/styles.xml", "<w:styles/>",
                        "docProps/core.xml", "<coreProperties/>")));

        assertThatThrownBy(() -> strictService.validate(file))
                .isInstanceOf(InvalidUploadException.class);

        assertThat(meterRegistry.get("security.upload.rejected").tag("reason", "docx_zip_bomb")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void rejectsADocxThatDecompressesBeyondTheConfiguredSizeLimit() {
        // A classic zip-bomb shape: a small compressed payload (a single repeated
        // character compresses extremely well under DEFLATE) that decompresses to
        // far more than a real DOCX ever would. The limit here (100 bytes) is
        // deliberately tiny so the test fixture itself stays small - the real
        // production limit (100MB) is configured in application.yml.
        IngestionProperties strictProperties = new IngestionProperties(800, PROPERTIES.allowedContentTypes(),
                new IngestionProperties.Docx(100, 100), URL_IMPORT_LIMITS, PDF_OCR_LIMITS);
        UploadValidationService strictService = new UploadValidationService(strictProperties, meterRegistry);
        MockMultipartFile file = new MockMultipartFile("file", "report.docx", DOCX_MIME,
                docxBytesWithEntries(Map.of("word/document.xml", "x".repeat(50_000))));

        assertThatThrownBy(() -> strictService.validate(file))
                .isInstanceOf(InvalidUploadException.class);

        assertThat(meterRegistry.get("security.upload.rejected").tag("reason", "docx_zip_bomb")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void rejectsFakePdf() {
        MockMultipartFile file = new MockMultipartFile("file", "fake.pdf", "application/pdf",
                "this is not a pdf".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.validate(file))
                .isInstanceOf(InvalidUploadException.class);
    }

    @Test
    void rejectsFakeImage() {
        MockMultipartFile file = new MockMultipartFile("file", "fake.png", "image/png",
                "not a png".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.validate(file))
                .isInstanceOf(InvalidUploadException.class);
    }

    @Test
    void rejectsFakeAudio() {
        MockMultipartFile file = new MockMultipartFile("file", "fake.mp3", "audio/mpeg",
                "not an mp3".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.validate(file))
                .isInstanceOf(InvalidUploadException.class);
    }

    @Test
    void rejectsAnImageDisguisedAsPlainText() {
        MockMultipartFile file = new MockMultipartFile("file", "sneaky.txt", "text/plain", pngBytes());

        assertThatThrownBy(() -> service.validate(file))
                .isInstanceOf(InvalidUploadException.class);
    }

    // --- Security Phase 5: security.upload.rejected metric ---

    @Test
    void incrementsTheRejectedMetricTaggedByReasonForAnUnsupportedExtension() {
        MockMultipartFile file = new MockMultipartFile("file", "archive.zip", "application/zip", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> service.validate(file)).isInstanceOf(UnsupportedDocumentTypeException.class);

        assertThat(meterRegistry.get("security.upload.rejected").tag("reason", "unsupported_extension")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void incrementsTheRejectedMetricTaggedByReasonForASignatureMismatch() {
        MockMultipartFile file = new MockMultipartFile("file", "fake.pdf", "application/pdf",
                "this is not a pdf".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.validate(file)).isInstanceOf(InvalidUploadException.class);

        assertThat(meterRegistry.get("security.upload.rejected").tag("reason", "signature_mismatch")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void doesNotIncrementTheRejectedMetricForAnAcceptedUpload() {
        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", pdfBytes());

        service.validate(file);

        assertThat(meterRegistry.find("security.upload.rejected").counter()).isNull();
    }

    // --- acceptances ---

    @Test
    void acceptsAValidPdf() {
        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", pdfBytes());

        ValidatedUpload upload = service.validate(file);

        assertThat(upload.kind()).isEqualTo(DocumentKind.PDF);
    }

    @Test
    void acceptsAValidDocx() {
        MockMultipartFile file = new MockMultipartFile("file", "report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docxBytes());

        ValidatedUpload upload = service.validate(file);

        assertThat(upload.kind()).isEqualTo(DocumentKind.DOCX);
    }

    @Test
    void acceptsValidMarkdown() {
        MockMultipartFile file = new MockMultipartFile("file", "notes.md", "text/markdown",
                "# Title\n\nSome body text.".getBytes(StandardCharsets.UTF_8));

        ValidatedUpload upload = service.validate(file);

        assertThat(upload.kind()).isEqualTo(DocumentKind.MARKDOWN);
    }

    @Test
    void acceptsValidPlainText() {
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain",
                "Just plain text content.".getBytes(StandardCharsets.UTF_8));

        ValidatedUpload upload = service.validate(file);

        assertThat(upload.kind()).isEqualTo(DocumentKind.TEXT);
    }

    @Test
    void acceptsValidPng() {
        MockMultipartFile file = new MockMultipartFile("file", "diagram.png", "image/png", pngBytes());

        ValidatedUpload upload = service.validate(file);

        assertThat(upload.kind()).isEqualTo(DocumentKind.IMAGE);
        assertThat(upload.mimeType().toString()).isEqualTo("image/png");
    }

    @Test
    void acceptsValidJpeg() {
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0});

        ValidatedUpload upload = service.validate(file);

        assertThat(upload.kind()).isEqualTo(DocumentKind.IMAGE);
    }

    @Test
    void acceptsValidGif() {
        MockMultipartFile file = new MockMultipartFile("file", "anim.gif", "image/gif",
                "GIF89a".getBytes(StandardCharsets.US_ASCII));

        ValidatedUpload upload = service.validate(file);

        assertThat(upload.kind()).isEqualTo(DocumentKind.IMAGE);
    }

    @Test
    void acceptsValidWebp() {
        MockMultipartFile file = new MockMultipartFile("file", "pic.webp", "image/webp", riff("WEBP"));

        ValidatedUpload upload = service.validate(file);

        assertThat(upload.kind()).isEqualTo(DocumentKind.IMAGE);
    }

    @Test
    void acceptsValidMp3() {
        MockMultipartFile file = new MockMultipartFile("file", "note.mp3", "audio/mpeg",
                "ID3".getBytes(StandardCharsets.US_ASCII));

        ValidatedUpload upload = service.validate(file);

        assertThat(upload.kind()).isEqualTo(DocumentKind.AUDIO);
    }

    @Test
    void acceptsValidWavWithTheXWavAlias() {
        MockMultipartFile file = new MockMultipartFile("file", "meeting.wav", "audio/x-wav", riff("WAVE"));

        ValidatedUpload upload = service.validate(file);

        assertThat(upload.kind()).isEqualTo(DocumentKind.AUDIO);
        assertThat(upload.mimeType().toString()).isEqualTo("audio/wav");
    }

    @Test
    void acceptsValidM4aWithTheXM4aAlias() {
        MockMultipartFile file = new MockMultipartFile("file", "note.m4a", "audio/x-m4a", ftypBytes());

        ValidatedUpload upload = service.validate(file);

        assertThat(upload.kind()).isEqualTo(DocumentKind.AUDIO);
    }

    @Test
    void acceptsValidOgg() {
        MockMultipartFile file = new MockMultipartFile("file", "note.ogg", "audio/ogg",
                "OggS".getBytes(StandardCharsets.US_ASCII));

        ValidatedUpload upload = service.validate(file);

        assertThat(upload.kind()).isEqualTo(DocumentKind.AUDIO);
    }

    @Test
    void acceptsValidFlac() {
        MockMultipartFile file = new MockMultipartFile("file", "note.flac", "audio/flac",
                "fLaC".getBytes(StandardCharsets.US_ASCII));

        ValidatedUpload upload = service.validate(file);

        assertThat(upload.kind()).isEqualTo(DocumentKind.AUDIO);
    }

    @Test
    void acceptsValidWebm() {
        MockMultipartFile file = new MockMultipartFile("file", "note.webm", "audio/webm",
                new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3});

        ValidatedUpload upload = service.validate(file);

        assertThat(upload.kind()).isEqualTo(DocumentKind.AUDIO);
    }

    // --- fixtures ---

    private static byte[] pdfBytes() {
        return "%PDF-1.4\n%useless trailer".getBytes(StandardCharsets.UTF_8);
    }

    // A real, minimal-but-valid DOCX: an actual ZIP archive containing
    // word/document.xml, the one entry every real DOCX has regardless of Office
    // version or content. Deliberately not just the PK\x03\x04 magic bytes alone
    // (that was the exact ADR 0022 gap this phase closed) - a fake ZIP with only
    // the signature bytes and no real structure is covered separately by
    // rejectsAnArbitraryZipRenamedToDocx below.
    private static byte[] docxBytes() {
        return docxBytesWithEntries(Map.of(
                "[Content_Types].xml", "<Types/>",
                "word/document.xml", "<w:document/>"));
    }

    private static byte[] docxBytesWithEntries(Map<String, String> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static byte[] pngBytes() {
        return new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
    }

    private static byte[] riff(String subFormat) {
        byte[] bytes = new byte[16];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 4);
        System.arraycopy(subFormat.getBytes(StandardCharsets.US_ASCII), 0, bytes, 8, 4);
        return bytes;
    }

    private static byte[] ftypBytes() {
        byte[] bytes = new byte[12];
        System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, bytes, 4, 4);
        System.arraycopy("M4A ".getBytes(StandardCharsets.US_ASCII), 0, bytes, 8, 4);
        return bytes;
    }
}
