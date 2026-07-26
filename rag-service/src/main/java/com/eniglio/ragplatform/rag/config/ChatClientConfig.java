package com.eniglio.ragplatform.rag.config;

import java.time.Duration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    // Ollama has no built-in call timeout: a stuck/overloaded model would hang the
    // request indefinitely. Read timeout must stay comfortably above real (slow,
    // CPU-only) inference latency — a genuine diagram/chat completion has taken up to
    // ~2min in practice, so 90s cut off legitimate responses, not just hangs.
    // Spring AI's OllamaApi picks up this RestClient.Builder bean as its base client.
    @Bean
    public RestClient.Builder restClientBuilder(
            @Value("${rag.ollama.connect-timeout:5s}") Duration connectTimeout,
            @Value("${rag.ollama.read-timeout:180s}") Duration readTimeout) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout);
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings));
    }
}
