package com.eniglio.ragplatform.ingestion.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Picks the right Spring AI {@link org.springframework.ai.reader.DocumentReader} based on a
 * {@link ValidatedUpload}'s {@link DocumentKind} — decided once, up front, by
 * {@link UploadValidationService}, not re-derived here from the filename. Tika's
 * content-sniffing is unreliable for plain-text formats like .md, which is exactly why
 * that upstream validation uses its own magic-byte table instead of Tika's detection API.
 * <p>
 * Images and audio are handled separately (ADR 0018, ADR 0019): neither has a
 * "reader" in the usual sense — {@link ImageDescriptionService} asks a vision-capable
 * Ollama model to describe an image, {@link AudioTranscriptionService} asks a local
 * Whisper server to transcribe audio, and either result becomes the single
 * {@link Document} that flows into the normal chunk/embed/store pipeline. The
 * original bytes are never stored or embedded, only the text derived from them.
 */
@Component
public class DocumentReaderFactory {

    private final ImageDescriptionService imageDescriptionService;
    private final AudioTranscriptionService audioTranscriptionService;
    private final ScannedPageVisionFallbackService scannedPageVisionFallbackService;

    public DocumentReaderFactory(ImageDescriptionService imageDescriptionService,
                                  AudioTranscriptionService audioTranscriptionService,
                                  ScannedPageVisionFallbackService scannedPageVisionFallbackService) {
        this.imageDescriptionService = imageDescriptionService;
        this.audioTranscriptionService = audioTranscriptionService;
        this.scannedPageVisionFallbackService = scannedPageVisionFallbackService;
    }

    public List<Document> read(ValidatedUpload upload) {
        return switch (upload.kind()) {
            case IMAGE -> List.of(Document.builder()
                    .text(imageDescriptionService.describe(upload.bytes(), upload.mimeType()))
                    .build());
            case AUDIO -> List.of(Document.builder()
                    .text(audioTranscriptionService.transcribe(upload.bytes(), upload.filename()))
                    .build());
            case PDF -> {
                List<Document> extracted = new PagePdfDocumentReader(
                        resourceFor(upload), PdfDocumentReaderConfig.defaultConfig()).get();
                yield scannedPageVisionFallbackService.fillMissingPages(upload.bytes(), extracted, upload.filename());
            }
            case DOCX, MARKDOWN, TEXT -> new TikaDocumentReader(resourceFor(upload)).get();
        };
    }

    private Resource resourceFor(ValidatedUpload upload) {
        return new NamedByteArrayResource(upload.bytes(), upload.filename());
    }
}
