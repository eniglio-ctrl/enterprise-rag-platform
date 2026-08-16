package com.eniglio.ragplatform.common.chunking;

import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Multi-LLM Phase 8 (RAG quality deep-dive): the baseline {@code TokenTextSplitter}
 * (`ingestion-service`'s {@code TextSplitterConfig}) cuts at a fixed token count with
 * no awareness of document structure — a chunk boundary can land mid-sentence.
 * Recursive splitting instead tries progressively finer separators (paragraph, then
 * sentence, then a hard character cutoff as a last resort), only descending to a
 * finer separator for a piece that's still too large after the coarser one — closer
 * to how a person would actually break up a document by hand.
 * <p>
 * Lives in {@code platform-common}, not `ingestion-service` (the module that
 * actually chunks real uploads) — `rag-service`'s test-only {@code
 * ChunkingStrategyBenchmark} needs it too, and `rag-service` has no dependency on
 * `ingestion-service` (they're sibling services, not layered). This class is
 * currently used only for that comparison, not wired into the real ingestion
 * pipeline — swapping the production splitter is a separate decision this phase
 * doesn't make on its own, see ADR 0034.
 * <p>
 * Character-based sizing, not token-based like {@code TokenTextSplitter} — this
 * class exists to compare splitting *strategy*, not to replace the token-based
 * baseline outright, so an exact token-count equivalence isn't the point. Adjacent
 * pieces are rejoined with a single space, which does lose the original
 * paragraph/sentence separator — an accepted simplification for a comparison
 * benchmark, not a production formatting requirement.
 */
public class RecursiveCharacterTextSplitter extends TextSplitter {

    private static final List<String> DEFAULT_SEPARATORS = List.of("\n\n", "\n", ". ", " ", "");

    private final int chunkSizeChars;
    private final List<String> separators;

    public RecursiveCharacterTextSplitter(int chunkSizeChars) {
        this(chunkSizeChars, DEFAULT_SEPARATORS);
    }

    public RecursiveCharacterTextSplitter(int chunkSizeChars, List<String> separators) {
        this.chunkSizeChars = chunkSizeChars;
        this.separators = separators;
    }

    @Override
    protected List<String> splitText(String text) {
        return merge(split(text, 0));
    }

    private List<String> split(String text, int separatorIndex) {
        if (text.length() <= chunkSizeChars || separatorIndex >= separators.size()) {
            return List.of(text);
        }
        String separator = separators.get(separatorIndex);
        List<String> parts = separator.isEmpty()
                ? fixedSizePieces(text)
                : List.of(text.split(Pattern.quote(separator), -1));

        List<String> result = new ArrayList<>();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (part.length() > chunkSizeChars) {
                result.addAll(split(part, separatorIndex + 1));
            } else {
                result.add(part);
            }
        }
        return result;
    }

    private List<String> fixedSizePieces(String text) {
        List<String> pieces = new ArrayList<>();
        for (int i = 0; i < text.length(); i += chunkSizeChars) {
            pieces.add(text.substring(i, Math.min(text.length(), i + chunkSizeChars)));
        }
        return pieces;
    }

    /** Greedily concatenates adjacent small pieces so chunks approach, without exceeding, the target size. */
    private List<String> merge(List<String> pieces) {
        List<String> merged = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String piece : pieces) {
            if (!current.isEmpty() && current.length() + 1 + piece.length() > chunkSizeChars) {
                merged.add(current.toString().strip());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(piece);
        }
        if (!current.isEmpty()) {
            merged.add(current.toString().strip());
        }
        return merged;
    }
}
