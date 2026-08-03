package com.eniglio.ragplatform.rag.benchmark;

/**
 * Small helpers shared between {@link RagQualityBenchmark} and {@link
 * ChunkingStrategyBenchmark} (Multi-LLM Phase 8) — extracted rather than duplicated
 * once a second benchmark class needed the exact same cosine-similarity/truncation
 * logic.
 */
final class BenchmarkSupport {

    private BenchmarkSupport() {
    }

    static double cosineSimilarity(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}
