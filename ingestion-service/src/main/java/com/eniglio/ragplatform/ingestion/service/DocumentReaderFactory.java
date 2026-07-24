package com.eniglio.ragplatform.ingestion.service;

import com.eniglio.ragplatform.ingestion.exception.UnsupportedDocumentTypeException;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;

/**
 * Picks the right Spring AI {@link org.springframework.ai.reader.DocumentReader} based on the
 * uploaded file's extension. Tika's content-sniffing is unreliable for plain-text formats like
 * .md, so the extension check takes priority over MIME auto-detection.
 */
@Component
public class DocumentReaderFactory {

    public List<Document> read(MultipartFile file) {
        String filename = normalize(file.getOriginalFilename());
        Resource resource = new NamedByteArrayResource(readBytes(file), filename);

        if (filename.endsWith(".pdf")) {
            return new PagePdfDocumentReader(resource, PdfDocumentReaderConfig.defaultConfig()).get();
        }
        if (filename.endsWith(".docx") || filename.endsWith(".md") || filename.endsWith(".txt")) {
            return new TikaDocumentReader(resource).get();
        }
        throw new UnsupportedDocumentTypeException("Unsupported file type: " + filename);
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
