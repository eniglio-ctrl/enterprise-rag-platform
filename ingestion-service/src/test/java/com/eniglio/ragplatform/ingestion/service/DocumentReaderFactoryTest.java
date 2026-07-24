package com.eniglio.ragplatform.ingestion.service;

import com.eniglio.ragplatform.ingestion.exception.UnsupportedDocumentTypeException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentReaderFactoryTest {

    private final DocumentReaderFactory factory = new DocumentReaderFactory();

    @Test
    void readsMarkdownContentIntoDocuments() {
        String markdown = "# SAGA pattern\n\nSAGA coordinates distributed transactions using choreography or orchestration.";
        MockMultipartFile file = new MockMultipartFile(
                "file", "aula12.md", "text/markdown", markdown.getBytes(StandardCharsets.UTF_8));

        List<Document> documents = factory.read(file);

        assertThat(documents).isNotEmpty();
        assertThat(documents.get(0).getText()).contains("SAGA");
    }

    @Test
    void readsPlainTextContent() {
        String text = "Hexagonal architecture isolates the domain from infrastructure concerns.";
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", text.getBytes(StandardCharsets.UTF_8));

        List<Document> documents = factory.read(file);

        assertThat(documents).isNotEmpty();
        assertThat(documents.get(0).getText()).contains("Hexagonal");
    }

    @Test
    void rejectsUnsupportedFileTypes() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "diagram.png", "image/png", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> factory.read(file))
                .isInstanceOf(UnsupportedDocumentTypeException.class)
                .hasMessageContaining("diagram.png");
    }
}
