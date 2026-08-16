package com.eniglio.ragplatform.chat.gateway;

import com.eniglio.ragplatform.chat.config.ChatProperties;
import com.eniglio.ragplatform.common.logging.CorrelationIdFilter;
import com.eniglio.ragplatform.common.web.RetrievedChunk;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.MDC;
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
 * <p>
 * Forwards the caller's own bearer token (ADR 0016) rather than re-deriving or
 * re-issuing one — rag-service validates it against the same JWKS every service
 * trusts and extracts {@code tenantId} from it itself, so this gateway doesn't need
 * to know or pass the tenant separately. Also forwards the current correlation ID
 * (Security Phase 5) from {@link MDC} - this is the one inter-service HTTP call in
 * the whole codebase, so it's the one place a single request's ID needs to survive
 * the hop from chat-service's logs into rag-service's.
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
    public List<RetrievedChunk> retrieve(String question, String bearerToken) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        RestClient.RequestBodySpec requestSpec = restClient.post()
                .uri("/api/v1/retrieve")
                .header("Authorization", "Bearer " + bearerToken)
                .contentType(MediaType.APPLICATION_JSON);
        if (correlationId != null) {
            requestSpec = requestSpec.header(CorrelationIdFilter.HEADER, correlationId);
        }
        RetrieveResponse response = requestSpec
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
