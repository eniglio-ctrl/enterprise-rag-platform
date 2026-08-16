package com.eniglio.ragplatform.common.web;

public record Citation(
        String source,
        Integer chunkIndex,
        Double score,
        String snippet
) {
}
