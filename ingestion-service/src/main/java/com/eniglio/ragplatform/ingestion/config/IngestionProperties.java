package com.eniglio.ragplatform.ingestion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "ingestion")
public record IngestionProperties(int chunkSizeTokens, List<String> allowedContentTypes) {
}
