package com.eniglio.ragplatform.ingestion.service;

import com.eniglio.ragplatform.ingestion.exception.UnsupportedDocumentTypeException;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Picks the right Spring AI {@link org.springframework.ai.reader.DocumentReader} based on the
 * uploaded file's extension. Tika's content-sniffing is unreliable for plain-text formats like
 * .md, so the extension check takes priority over MIME auto-detection.
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

    private static final Map<String, MimeType> IMAGE_EXTENSIONS = Map.of(
            ".png", MimeTypeUtils.IMAGE_PNG,
            ".jpg", MimeTypeUtils.IMAGE_JPEG,
            ".jpeg", MimeTypeUtils.IMAGE_JPEG,
            ".gif", MimeTypeUtils.IMAGE_GIF,
            ".webp", new MimeType("image", "webp"));

    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            ".mp3", ".wav", ".m4a", ".ogg", ".flac", ".webm");

    private final ImageDescriptionService imageDescriptionService;
    private final AudioTranscriptionService audioTranscriptionService;

    public DocumentReaderFactory(ImageDescriptionService imageDescriptionService,
                                  AudioTranscriptionService audioTranscriptionService) {
        this.imageDescriptionService = imageDescriptionService;
        this.audioTranscriptionService = audioTranscriptionService;
    }

    public List<Document> read(MultipartFile file) {
        String filename = normalize(file.getOriginalFilename());
        byte[] bytes = readBytes(file);

        MimeType imageMimeType = imageMimeTypeFor(filename);
        if (imageMimeType != null) {
            String description = imageDescriptionService.describe(bytes, imageMimeType);
            return List.of(Document.builder().text(description).build());
        }
        if (AUDIO_EXTENSIONS.stream().anyMatch(filename::endsWith)) {
            String transcript = audioTranscriptionService.transcribe(bytes, filename);
            return List.of(Document.builder().text(transcript).build());
        }

        Resource resource = new NamedByteArrayResource(bytes, filename);
        if (filename.endsWith(".pdf")) {
            return new PagePdfDocumentReader(resource, PdfDocumentReaderConfig.defaultConfig()).get();
        }
        if (filename.endsWith(".docx") || filename.endsWith(".md") || filename.endsWith(".txt")) {
            return new TikaDocumentReader(resource).get();
        }
        throw new UnsupportedDocumentTypeException("Unsupported file type: " + filename);
    }

    private MimeType imageMimeTypeFor(String filename) {
        return IMAGE_EXTENSIONS.entrySet().stream()
                .filter(entry -> filename.endsWith(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private String normalize(String originalFilename) {
        return originalFilename == null ? "" : originalFilename.toLowerCase(Locale.ROOT);
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file", e);
        }
    }
}
