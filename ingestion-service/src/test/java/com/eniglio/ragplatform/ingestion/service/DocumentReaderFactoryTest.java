package com.eniglio.ragplatform.ingestion.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
        ValidatedUpload upload = new ValidatedUpload(
                markdown.getBytes(StandardCharsets.UTF_8), "aula12.md",
                MimeType.valueOf("text/markdown"), DocumentKind.MARKDOWN);

        List<Document> documents = newFactory().read(upload);

        assertThat(documents).isNotEmpty();
        assertThat(documents.get(0).getText()).contains("SAGA");
    }

    @Test
    void readsPlainTextContent() {
        String text = "Hexagonal architecture isolates the domain from infrastructure concerns.";
        ValidatedUpload upload = new ValidatedUpload(
                text.getBytes(StandardCharsets.UTF_8), "notes.txt",
                MimeType.valueOf("text/plain"), DocumentKind.TEXT);

        List<Document> documents = newFactory().read(upload);

        assertThat(documents).isNotEmpty();
        assertThat(documents.get(0).getText()).contains("Hexagonal");
    }

    @Test
    void describesPngImagesInsteadOfRejectingThem() {
        byte[] pngBytes = {1, 2, 3};
        ValidatedUpload upload = new ValidatedUpload(
                pngBytes, "diagram.png", MimeTypeUtils.IMAGE_PNG, DocumentKind.IMAGE);
        given(imageDescriptionService.describe(eq(pngBytes), any(MimeType.class)))
                .willReturn("A flowchart showing a client calling an API gateway.");

        List<Document> documents = newFactory().read(upload);

        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).getText()).contains("API gateway");
    }

    @Test
    void describesJpegImagesUsingTheJpegMimeType() {
        byte[] jpegBytes = {4, 5, 6};
        ValidatedUpload upload = new ValidatedUpload(
                jpegBytes, "photo.jpeg", MimeTypeUtils.IMAGE_JPEG, DocumentKind.IMAGE);
        given(imageDescriptionService.describe(eq(jpegBytes), eq(MimeTypeUtils.IMAGE_JPEG)))
                .willReturn("A photo of a server room.");

        List<Document> documents = newFactory().read(upload);

        assertThat(documents.get(0).getText()).contains("server room");
    }

    @Test
    void transcribesAudioFilesInsteadOfRejectingThem() {
        byte[] wavBytes = {7, 8, 9};
        ValidatedUpload upload = new ValidatedUpload(
                wavBytes, "meeting.wav", MimeType.valueOf("audio/wav"), DocumentKind.AUDIO);
        given(audioTranscriptionService.transcribe(wavBytes, "meeting.wav"))
                .willReturn("Let's ship the disaster recovery runbook by Friday.");

        List<Document> documents = newFactory().read(upload);

        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).getText()).contains("runbook");
    }

    @Test
    void transcribesMp3FilesToo() {
        byte[] mp3Bytes = {10, 11, 12};
        ValidatedUpload upload = new ValidatedUpload(
                mp3Bytes, "note.mp3", MimeType.valueOf("audio/mpeg"), DocumentKind.AUDIO);
        given(audioTranscriptionService.transcribe(mp3Bytes, "note.mp3"))
                .willReturn("Quick voice note about the release.");

        List<Document> documents = newFactory().read(upload);

        assertThat(documents.get(0).getText()).contains("release");
    }
}
