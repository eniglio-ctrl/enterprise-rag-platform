package com.eniglio.ragplatform.ingestion.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * ingestion-service's only chat model is used for describing uploaded images (ADR
 * 0018), never for the retrieval/generation flows rag-service handles — a single
 * unqualified {@code ChatModel} bean exists here (Ollama only, no LM Studio), so
 * Spring AI's own auto-configured {@code ChatClient.Builder} works without the
 * multi-bean disambiguation rag-service needs (ADR 0017).
 */
@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    // Pinned to .simple() rather than leaving Spring Boot's default (.detect(),
    // which picks the JDK HttpClient-based factory): that factory sends an
    // "Upgrade: h2c" cleartext-HTTP/2 attempt alongside the chunked request body
    // used for vision calls, which Ollama's own Go HTTP server was confirmed —
    // by capturing and replaying the exact raw request bytes both ways against
    // the real container — to sometimes mishandle, silently dropping the image
    // from the request or failing with "unexpected EOF". .simple() sends the
    // identical bytes without the upgrade attempt and was verified reliable.
    // docs/ROADMAP.md item #17's timeout audit found this bean had no timeout
    // settings at all before this - the one confirmed gap in this service, unlike
    // every other outbound client here (Whisper already had one, ADR 0019).
    @Bean
    public RestClient.Builder restClientBuilder(
            @Value("${ingestion.ollama.connect-timeout:5s}") Duration connectTimeout,
            @Value("${ingestion.ollama.read-timeout:180s}") Duration readTimeout) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout);
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.simple().build(settings));
    }
}
