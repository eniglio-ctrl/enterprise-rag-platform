package com.eniglio.ragplatform.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "rag")
public record RagProperties(int topK, double similarityThreshold, int rerankCandidatePoolSize,
                             List<AvailableModel> availableModels, DocumentInsights documentInsights) {

    /**
     * A model selectable in the web-ui dropdown (ADR 0017). The first entry in the
     * configured list is the default. {@code provider} is {@code "ollama"} or
     * {@code "lmstudio"} — picks which {@code ChatClient}/circuit breaker handles the
     * request (see {@code RagQueryService}). For {@code "ollama"}, {@code id} must
     * already be pulled (`ollama pull <id>`); this list never triggers a pull itself.
     */
    public record AvailableModel(String id, String label, String provider) {
    }

    /**
     * docs/PRODUCT-DIFFERENTIATION-ROADMAP.md Phase 8. {@code maxChunks} bounds how
     * much of a document's indexed content gets sent to the model for a
     * summarize/FAQ call - a whole document can have far more chunks than the top-K
     * used for a normal question, so this is a real, explicit cap rather than "send
     * everything and hope it fits the model's context window." A document over the
     * limit is truncated to its first {@code maxChunks} chunks (in document order).
     */
    public record DocumentInsights(int maxChunks) {
    }
}
