package com.eniglio.ragplatform.common.chunking;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecursiveCharacterTextSplitterTest {

    @Test
    void textShorterThanChunkSizeStaysInOneChunk() {
        RecursiveCharacterTextSplitter splitter = new RecursiveCharacterTextSplitter(100);

        List<Document> chunks = splitter.apply(List.of(Document.builder().text("A short paragraph.").build()));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getText()).isEqualTo("A short paragraph.");
    }

    @Test
    void prefersSplittingAtParagraphBoundariesOverCuttingMidSentence() {
        RecursiveCharacterTextSplitter splitter = new RecursiveCharacterTextSplitter(40);
        String text = "First paragraph, short enough.\n\nSecond paragraph, also short.";

        List<Document> chunks = splitter.apply(List.of(Document.builder().text(text).build()));

        assertThat(chunks).extracting(Document::getText)
                .containsExactly("First paragraph, short enough.", "Second paragraph, also short.");
    }

    @Test
    void aParagraphLongerThanChunkSizeFallsBackToSentenceSplitting() {
        RecursiveCharacterTextSplitter splitter = new RecursiveCharacterTextSplitter(30);
        String text = "This sentence is long enough to need splitting. This one too, quite long as well.";

        List<Document> chunks = splitter.apply(List.of(Document.builder().text(text).build()));

        assertThat(chunks.size()).isGreaterThan(1);
        chunks.forEach(chunk -> assertThat(chunk.getText().length()).isLessThanOrEqualTo(30));
    }

    @Test
    void aSingleWordLongerThanChunkSizeFallsBackToAHardCharacterCutoff() {
        RecursiveCharacterTextSplitter splitter = new RecursiveCharacterTextSplitter(10);
        String text = "a".repeat(35);

        List<Document> chunks = splitter.apply(List.of(Document.builder().text(text).build()));

        assertThat(chunks).isNotEmpty();
        chunks.forEach(chunk -> assertThat(chunk.getText().length()).isLessThanOrEqualTo(10));
    }

    @Test
    void metadataIsCarriedForwardOntoEveryChunk() {
        RecursiveCharacterTextSplitter splitter = new RecursiveCharacterTextSplitter(20);
        Document source = Document.builder()
                .text("First bit.\n\nSecond bit.")
                .metadata("source", "test.md")
                .build();

        List<Document> chunks = splitter.apply(List.of(source));

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        chunks.forEach(chunk -> assertThat(chunk.getMetadata()).containsEntry("source", "test.md"));
    }
}
