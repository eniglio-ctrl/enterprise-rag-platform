package com.eniglio.ragplatform.rag.gateway;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Similarity search embeds the query text via Ollama before searching pgvector, so it
 * depends on Ollama being reachable too, not just the chat calls in {@link LlmGateway}.
 * Both share the same "ollama" circuit breaker/retry instance since they fail for the
 * same underlying reason: the local Ollama container being down.
 */
@Component
public class VectorStoreGateway {

    private final VectorStore vectorStore;

    public VectorStoreGateway(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @CircuitBreaker(name = "ollama")
    @Retry(name = "ollama")
    public List<Document> search(SearchRequest searchRequest) {
        return vectorStore.similaritySearch(searchRequest);
    }
}
