package com.eniglio.ragplatform.ingestion.service;

import com.eniglio.ragplatform.ingestion.config.IngestionProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * docs/adr/0055-ocr-fallback-for-scanned-pdfs.md. Spring AI's
 * {@code PagePdfDocumentReader} silently drops any page whose text layer is
 * empty - a fully scanned page never becomes a {@link Document} at all, not a
 * {@code Document} with empty text. This service fills those gaps (and pages
 * whose extracted text is too short to be real content) by rendering the
 * page as an image and reusing the existing vision pipeline
 * ({@link ImageDescriptionService}, ADR 0018) instead of introducing a
 * dedicated OCR engine.
 */
@Component
public class ScannedPageVisionFallbackService {

    private final ImageDescriptionService imageDescriptionService;
    private final IngestionProperties.PdfOcr pdfOcr;
    private final Counter ocrPagesCounter;

    public ScannedPageVisionFallbackService(ImageDescriptionService imageDescriptionService,
                                             IngestionProperties ingestionProperties,
                                             MeterRegistry meterRegistry) {
        this.imageDescriptionService = imageDescriptionService;
        this.pdfOcr = ingestionProperties.pdfOcr();
        this.ocrPagesCounter = Counter.builder("ingestion.pdf.ocr_pages")
                .description("Number of PDF pages that needed the vision-model OCR fallback")
                .register(meterRegistry);
    }

    public List<Document> fillMissingPages(byte[] pdfBytes, List<Document> extracted, String filename) {
        // PagePdfDocumentReader's "page_number" metadata is 1-indexed (physical page
        // index 0 -> page_number 1), confirmed by actually running it - not the
        // 0-indexed value its own field name suggests. Kept as-is here (rather than
        // normalized to 0-indexed) so every Document in the final merged list uses
        // the same convention Spring AI already established.
        Set<Integer> goodPageNumbers = new HashSet<>();
        for (Document document : extracted) {
            Object pageNumber = document.getMetadata().get("page_number");
            if (pageNumber instanceof Integer pageNumberValue
                    && document.getText() != null
                    && document.getText().length() >= pdfOcr.minTextLengthPerPage()) {
                goodPageNumbers.add(pageNumberValue);
            }
        }

        List<Document> result = new ArrayList<>(extracted);
        try (PDDocument pdDocument = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(pdDocument);
            int totalPages = pdDocument.getNumberOfPages();
            int fallbackPagesProcessed = 0;

            for (int pageIndex = 0; pageIndex < totalPages && fallbackPagesProcessed < pdfOcr.maxOcrPages(); pageIndex++) {
                int pageNumber = pageIndex + 1;
                if (goodPageNumbers.contains(pageNumber)) {
                    continue;
                }

                byte[] pngBytes = renderPageAsPng(renderer, pageIndex);
                String description = imageDescriptionService.describe(pngBytes, MimeTypeUtils.IMAGE_PNG);

                Document fallbackDocument = Document.builder()
                        .text(description)
                        .metadata("page_number", pageNumber)
                        .metadata("file_name", filename)
                        .build();
                result.add(fallbackDocument);
                fallbackPagesProcessed++;
                ocrPagesCounter.increment();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        result.sort(Comparator.comparingInt(document -> (Integer) document.getMetadata().get("page_number")));
        return result;
    }

    private byte[] renderPageAsPng(PDFRenderer renderer, int pageIndex) throws IOException {
        BufferedImage image = renderer.renderImageWithDPI(pageIndex, 200);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
