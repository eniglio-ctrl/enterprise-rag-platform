package com.eniglio.ragplatform.ingestion.service;

import com.eniglio.ragplatform.ingestion.config.IngestionProperties;
import com.eniglio.ragplatform.ingestion.gateway.VisionGateway;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * {@code extracted} in every test here comes from actually running the real
 * {@link PagePdfDocumentReader} against the test PDF - not hand-built
 * {@link Document}s - specifically because that reader's own {@code
 * page_number} metadata turned out to be 1-indexed (physical page 0 ->
 * {@code page_number} 1), confirmed only by running it for real against a
 * running stack. A hand-fabricated {@code page_number} would have hidden
 * exactly the off-by-one this service must get right.
 */
@ExtendWith(MockitoExtension.class)
class ScannedPageVisionFallbackServiceTest {

    @Mock
    private ChatModel chatModel;

    private ScannedPageVisionFallbackService newService() {
        ChatClient chatClient = ChatClient.builder(chatModel).build();
        ImageDescriptionService imageDescriptionService = new ImageDescriptionService(chatClient, new VisionGateway());
        IngestionProperties ingestionProperties = new IngestionProperties(800, List.of(),
                new IngestionProperties.Docx(100, 10_000_000),
                new IngestionProperties.UrlImport(26_214_400L, java.time.Duration.ofSeconds(10)),
                new IngestionProperties.PdfOcr(20, 20));
        return new ScannedPageVisionFallbackService(imageDescriptionService, ingestionProperties, new SimpleMeterRegistry());
    }

    @Test
    void leavesAPageWithRealTextUntouchedAndDescribesTheBlankPageInstead() throws IOException {
        given(chatModel.call(any(Prompt.class))).willReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("A scanned invoice with a total of $500.")))));

        byte[] pdfBytes = twoPagePdfWithOneBlankPage();
        List<Document> extracted = readWithRealPagePdfDocumentReader(pdfBytes);
        // Sanity-check the premise: the reader only produced a Document for the
        // real-text page, and its page_number is 1-indexed (physical page 0 -> 1).
        assertThat(extracted).hasSize(1);
        assertThat(extracted.get(0).getMetadata().get("page_number")).isEqualTo(1);

        List<Document> result = newService().fillMissingPages(pdfBytes, extracted, "report.pdf");

        assertThat(result).hasSize(2);
        assertThat(normalizeWhitespace(result.get(0).getText())).contains("Quarterly report body text.");
        assertThat(result.get(0).getMetadata().get("page_number")).isEqualTo(1);
        assertThat(result.get(1).getText()).contains("scanned invoice");
        assertThat(result.get(1).getMetadata().get("page_number")).isEqualTo(2);
    }

    @Test
    void doesNotCallTheVisionModelWhenEveryPageAlreadyHasRealText() throws IOException {
        byte[] pdfBytes = onePagePdfWithRealText();
        List<Document> extracted = readWithRealPagePdfDocumentReader(pdfBytes);

        List<Document> result = newService().fillMissingPages(pdfBytes, extracted, "report.pdf");

        assertThat(result).hasSize(1);
        assertThat(normalizeWhitespace(result.get(0).getText())).contains("Quarterly report body text.");
    }

    private static List<Document> readWithRealPagePdfDocumentReader(byte[] pdfBytes) {
        return new PagePdfDocumentReader(new NamedByteArrayResource(pdfBytes, "report.pdf"),
                PdfDocumentReaderConfig.defaultConfig()).get();
    }

    // PDFTextStripper spaces out glyphs unevenly (kerning-derived gaps become
    // literal extra spaces) - real PDFBox output, not a bug, just not directly
    // string-equal to what was drawn.
    private static String normalizeWhitespace(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private static byte[] onePagePdfWithRealText() throws IOException {
        try (PDDocument document = new PDDocument()) {
            addTextPage(document, "Quarterly report body text.");
            return save(document);
        }
    }

    private static byte[] twoPagePdfWithOneBlankPage() throws IOException {
        try (PDDocument document = new PDDocument()) {
            addTextPage(document, "Quarterly report body text.");
            document.addPage(new PDPage());
            return save(document);
        }
    }

    private static void addTextPage(PDDocument document, String text) throws IOException {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
            contentStream.beginText();
            contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            contentStream.newLineAtOffset(50, 700);
            contentStream.showText(text);
            contentStream.endText();
        }
    }

    private static byte[] save(PDDocument document) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.save(out);
        return out.toByteArray();
    }
}
