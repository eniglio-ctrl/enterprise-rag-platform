package com.eniglio.ragplatform.common.web;

/**
 * Unlike {@link Citation} (whose {@code snippet} is deliberately truncated for
 * display), {@code content} here is the full retrieved chunk text — this is what a
 * caller like chat-service needs to actually use as generation context, not just show
 * provenance for.
 */
public record RetrievedChunk(String source, Integer chunkIndex, Double score, String content) {
}
