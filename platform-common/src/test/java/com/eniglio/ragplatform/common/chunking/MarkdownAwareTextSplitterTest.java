package com.eniglio.ragplatform.common.chunking;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownAwareTextSplitterTest {

    @Test
    void textWithNoHeadingsIsTreatedAsOneSection() {
        MarkdownAwareTextSplitter splitter = new MarkdownAwareTextSplitter(1000);

        List<Document> chunks = splitter.apply(List.of(Document.builder().text("Just a plain paragraph.").build()));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getText()).isEqualTo("Just a plain paragraph.");
    }

    @Test
    void splitsAtEachHeadingBoundaryWhenSectionsFitWithinChunkSize() {
        MarkdownAwareTextSplitter splitter = new MarkdownAwareTextSplitter(1000);
        String text = "# Title\n\nIntro text.\n\n## Section A\n\nContent A.\n\n## Section B\n\nContent B.";

        List<Document> chunks = splitter.apply(List.of(Document.builder().text(text).build()));

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).getText()).startsWith("# Title");
        assertThat(chunks.get(1).getText()).startsWith("## Section A");
        assertThat(chunks.get(2).getText()).startsWith("## Section B");
    }

    @Test
    void aSectionLargerThanChunkSizeFallsBackToRecursiveSplitting() {
        MarkdownAwareTextSplitter splitter = new MarkdownAwareTextSplitter(30);
        String longParagraph = "This is a long paragraph that will not fit inside a thirty character chunk at all.";
        String text = "## Section\n\n" + longParagraph;

        List<Document> chunks = splitter.apply(List.of(Document.builder().text(text).build()));

        assertThat(chunks.size()).isGreaterThan(1);
        chunks.forEach(chunk -> assertThat(chunk.getText().length()).isLessThanOrEqualTo(30));
    }

    @Test
    void textBeforeTheFirstHeadingBecomesItsOwnLeadingSection() {
        MarkdownAwareTextSplitter splitter = new MarkdownAwareTextSplitter(1000);
        String text = "Some preamble with no heading.\n\n## First heading\n\nBody.";

        List<Document> chunks = splitter.apply(List.of(Document.builder().text(text).build()));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).getText()).isEqualTo("Some preamble with no heading.");
        assertThat(chunks.get(1).getText()).startsWith("## First heading");
    }
}
