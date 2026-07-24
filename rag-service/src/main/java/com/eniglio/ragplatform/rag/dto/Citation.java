package com.eniglio.ragplatform.rag.dto;

public record Citation(
        String source,
        Integer chunkIndex,
        Double score,
        String snippet
) {
}
