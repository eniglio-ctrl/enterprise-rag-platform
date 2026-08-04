package com.eniglio.ragplatform.ingestion.gateway;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code VectorStore.add(...)} embeds every chunk via Ollama before inserting, so this
 * is where an Ollama outage actually surfaces during ingestion (see ADR 0009). Calling
 * this through a separate bean — instead of annotating a method inside
 * DocumentIngestionService directly — is required for Resilience4j's annotations to
 * take effect at all: self-invocation within the same class bypasses the Spring proxy.
 */
@Component
public class VectorStoreGateway {

    private final VectorStore vectorStore;

    public VectorStoreGateway(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @CircuitBreaker(name = "ollama")
    @Retry(name = "ollama")
    @Bulkhead(name = "ollama")
    public void add(List<Document> chunks) {
        vectorStore.add(chunks);
    }
}
