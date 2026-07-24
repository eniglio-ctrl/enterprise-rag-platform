package com.eniglio.ragplatform.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag")
public record RagProperties(int topK, double similarityThreshold) {
}
