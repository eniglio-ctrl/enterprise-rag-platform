package com.eniglio.ragplatform.chat.config;

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

    // Same reasoning as rag-service's ChatClientConfig (Fase 0): Ollama has no
    // built-in call timeout, and CPU-bound local inference can legitimately take
    // over a minute. Spring AI's OllamaApi picks up this bean as its base client.
    @Bean
    public RestClient.Builder restClientBuilder(
            @Value("${chat.ollama.connect-timeout:5s}") Duration connectTimeout,
            @Value("${chat.ollama.read-timeout:180s}") Duration readTimeout) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout);
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings));
    }
}
