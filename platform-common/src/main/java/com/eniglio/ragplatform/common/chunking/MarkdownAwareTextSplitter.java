package com.eniglio.ragplatform.common.chunking;

import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Multi-LLM Phase 8 (RAG quality deep-dive): splits at Markdown heading boundaries
 * (lines starting with 1-6 {@code #} characters) first, so each chunk stays within
 * one logical section instead of a fixed-size cut landing across two unrelated
 * headings. A section still larger than the target size falls back to {@link
 * RecursiveCharacterTextSplitter} for that section only.
 * <p>
 * Detects headings directly from the flat text {@code TikaDocumentReader} already
 * produces for {@code .md} uploads — confirmed real, not assumed: uploaded this
 * project's own {@code docs/architecture.md} through the real local
 * `ingestion-service` and inspected the stored chunk content directly in Postgres;
 * {@code #}/{@code ##} markdown syntax survives Tika's extraction verbatim. Spring
 * AI 1.0.0 ships no Markdown-structure-aware reader/splitter of its own (only
 * {@code TokenTextSplitter} exists in {@code org.springframework.ai.transformer
 * .splitter}), and adding a dedicated reader dependency just for this comparison
 * was judged more risk than this phase needs.
 * <p>
 * Lives in {@code platform-common}, same reasoning as {@link
 * RecursiveCharacterTextSplitter} — `rag-service`'s benchmark needs it without
 * depending on `ingestion-service`.
 */
public class MarkdownAwareTextSplitter extends TextSplitter {

    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6}\\s+.*$");

    private final int chunkSizeChars;
    private final RecursiveCharacterTextSplitter fallback;

    public MarkdownAwareTextSplitter(int chunkSizeChars) {
        this.chunkSizeChars = chunkSizeChars;
        this.fallback = new RecursiveCharacterTextSplitter(chunkSizeChars);
    }

    @Override
    protected List<String> splitText(String text) {
        List<String> result = new ArrayList<>();
        for (String section : splitByHeadings(text)) {
            if (section.isBlank()) {
                continue;
            }
            if (section.length() > chunkSizeChars) {
                result.addAll(fallback.splitText(section));
            } else {
                result.add(section.strip());
            }
        }
        return result;
    }

    private List<String> splitByHeadings(String text) {
        Matcher matcher = HEADING.matcher(text);
        List<Integer> boundaries = new ArrayList<>();
        while (matcher.find()) {
            boundaries.add(matcher.start());
        }
        if (boundaries.isEmpty()) {
            return List.of(text);
        }

        List<String> sections = new ArrayList<>();
        if (boundaries.get(0) > 0) {
            sections.add(text.substring(0, boundaries.get(0)));
        }
        for (int i = 0; i < boundaries.size(); i++) {
            int start = boundaries.get(i);
            int end = i + 1 < boundaries.size() ? boundaries.get(i + 1) : text.length();
            sections.add(text.substring(start, end));
        }
        return sections;
    }
}
