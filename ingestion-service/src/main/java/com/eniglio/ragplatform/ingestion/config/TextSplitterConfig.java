package com.eniglio.ragplatform.ingestion.config;

import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TextSplitterConfig {

    @Bean
    public TokenTextSplitter tokenTextSplitter(IngestionProperties properties) {
        return new TokenTextSplitter(properties.chunkSizeTokens(), 350, 5, 10000, true);
    }
}
