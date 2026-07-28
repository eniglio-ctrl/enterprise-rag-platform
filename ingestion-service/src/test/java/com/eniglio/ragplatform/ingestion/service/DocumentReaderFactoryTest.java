package com.eniglio.ragplatform.ingestion.service;

import com.eniglio.ragplatform.ingestion.exception.UnsupportedDocumentTypeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class DocumentReaderFactoryTest {

    @Mock
    private ImageDescriptionService imageDescriptionService;

    @Mock
    private AudioTranscriptionService audioTranscriptionService;

    private DocumentReaderFactory newFactory() {
        return new DocumentReaderFactory(imageDescriptionService, audioTranscriptionService);
    }

    @Test
    void readsMarkdownContentIntoDocuments() {
        String markdown = "# SAGA pattern\n\nSAGA coordinates distributed transactions using choreography or orchestration.";
        MockMultipartFile file = new MockMultipartFile(
                "file", "aula12.md", "text/markdown", markdown.getBytes(StandardCharsets.UTF_8));

        List<Document> documents = newFactory().read(file);

        assertThat(documents).isNotEmpty();
        assertThat(documents.get(0).getText()).contains("SAGA");
    }

    @Test
    void readsPlainTextContent() {
        String text = "Hexagonal architecture isolates the domain from infrastructure concerns.";
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", text.getBytes(StandardCharsets.UTF_8));

        List<Document> documents = newFactory().read(file);

        assertThat(documents).isNotEmpty();
        assertThat(documents.get(0).getText()).contains("Hexagonal");
    }

    @Test
    void rejectsUnsupportedFileTypes() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "archive.zip", "application/zip", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> newFactory().read(file))
                .isInstanceOf(UnsupportedDocumentTypeException.class)
                .hasMessageContaining("archive.zip");
    }

    @Test
    void describesPngImagesInsteadOfRejectingThem() {
        byte[] pngBytes = {1, 2, 3};
        MockMultipartFile file = new MockMultipartFile("file", "diagram.png", "image/png", pngBytes);
        given(imageDescriptionService.describe(eq(pngBytes), any(MimeType.class)))
                .willReturn("A flowchart showing a client calling an API gateway.");

        List<Document> documents = newFactory().read(file);

        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).getText()).contains("API gateway");
    }

    @Test
    void describesJpegImagesUsingTheJpegMimeType() {
        byte[] jpegBytes = {4, 5, 6};
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpeg", "image/jpeg", jpegBytes);
        given(imageDescriptionService.describe(eq(jpegBytes), eq(MimeTypeUtils.IMAGE_JPEG)))
                .willReturn("A photo of a server room.");

        List<Document> documents = newFactory().read(file);

        assertThat(documents.get(0).getText()).contains("server room");
    }

    @Test
    void transcribesAudioFilesInsteadOfRejectingThem() {
        byte[] wavBytes = {7, 8, 9};
        MockMultipartFile file = new MockMultipartFile("file", "meeting.wav", "audio/wav", wavBytes);
        given(audioTranscriptionService.transcribe(wavBytes, "meeting.wav"))
                .willReturn("Let's ship the disaster recovery runbook by Friday.");

        List<Document> documents = newFactory().read(file);

        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).getText()).contains("runbook");
    }

    @Test
    void transcribesMp3FilesToo() {
        byte[] mp3Bytes = {10, 11, 12};
        MockMultipartFile file = new MockMultipartFile("file", "note.mp3", "audio/mpeg", mp3Bytes);
        given(audioTranscriptionService.transcribe(mp3Bytes, "note.mp3"))
                .willReturn("Quick voice note about the release.");

        List<Document> documents = newFactory().read(file);

        assertThat(documents.get(0).getText()).contains("release");
    }
}
