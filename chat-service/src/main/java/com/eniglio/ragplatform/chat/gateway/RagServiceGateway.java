package com.eniglio.ragplatform.chat.gateway;

import com.eniglio.ragplatform.chat.config.ChatProperties;
import com.eniglio.ragplatform.common.web.RetrievedChunk;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Calls rag-service's retrieval-only endpoint (ADR 0013) — chat-service never
 * re-implements embedding/vector search/full-text search itself, it only builds its
 * own conversation-aware generation on top of chunks rag-service already found.
 * Resilience4j instance name "rag-service", separate from "ollama" (ADR 0009): this
 * fails for a different reason (rag-service being down/slow), not Ollama.
 */
@Component
public class RagServiceGateway {

    private final RestClient restClient;

    public RagServiceGateway(ChatProperties chatProperties) {
        ChatProperties.RagService config = chatProperties.ragService();
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(config.connectTimeout())
                .withReadTimeout(config.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(config.baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
    }

    @CircuitBreaker(name = "rag-service")
    @Retry(name = "rag-service")
    public List<RetrievedChunk> retrieve(String question, String tenantId) {
        RetrieveResponse response = restClient.post()
                .uri("/api/v1/retrieve")
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RetrieveRequest(question))
                .retrieve()
                .body(RetrieveResponse.class);
        return response == null ? List.of() : response.chunks();
    }

    private record RetrieveRequest(String question) {
    }

    private record RetrieveResponse(List<RetrievedChunk> chunks) {
    }
}
