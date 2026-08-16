package com.eniglio.ragplatform.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "chat")
public record ChatProperties(int maxMessages, RagService ragService) {

    public record RagService(String baseUrl, Duration connectTimeout, Duration readTimeout) {
    }
}
