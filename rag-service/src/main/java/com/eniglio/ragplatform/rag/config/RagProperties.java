package com.eniglio.ragplatform.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "rag")
public record RagProperties(int topK, double similarityThreshold, int rerankCandidatePoolSize,
                             List<AvailableModel> availableModels) {

    /**
     * A model selectable in the web-ui dropdown (ADR 0017). The first entry in the
     * configured list is the default. {@code provider} is {@code "ollama"} or
     * {@code "lmstudio"} — picks which {@code ChatClient}/circuit breaker handles the
     * request (see {@code RagQueryService}). For {@code "ollama"}, {@code id} must
     * already be pulled (`ollama pull <id>`); this list never triggers a pull itself.
     */
    public record AvailableModel(String id, String label, String provider) {
    }
}
