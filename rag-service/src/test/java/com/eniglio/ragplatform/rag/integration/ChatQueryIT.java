package com.eniglio.ragplatform.rag.integration;

import com.eniglio.ragplatform.rag.RagServiceApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises retrieval + generation against a real Postgres/pgvector instance.
 * Both the embedding model and the chat model are mocked so the test does not
 * depend on a running Ollama server; the embedding mock returns a constant
 * vector so the indexed chunk and the query always match.
 */
@Testcontainers
@SpringBootTest(classes = RagServiceApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatQueryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ragplatform")
            .withUsername("ragplatform")
            .withPassword("ragplatform");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private EmbeddingModel embeddingModel;

    @MockitoBean
    private ChatModel chatModel;

    @BeforeEach
    void seedVectorStoreAndStubModels() {
        // rag-service never runs Flyway (ADR 0011) — it only reads a schema
        // ingestion-service migrates. This test's Postgres is standalone (no
        // ingestion-service involved), so it needs the same columns Flyway's V2
        // migration adds, or HybridSearchService's full-text SQL leg has nothing to
        // query against. Mirrors that migration's DDL exactly; IF NOT EXISTS makes
        // re-running it every test method harmless.
        jdbcTemplate.execute("""
                ALTER TABLE vector_store
                    ADD COLUMN IF NOT EXISTS tenant_id text
                        GENERATED ALWAYS AS (metadata->>'tenantId') STORED,
                    ADD COLUMN IF NOT EXISTS user_id text
                        GENERATED ALWAYS AS (metadata->>'userId') STORED,
                    ADD COLUMN IF NOT EXISTS content_tsv tsvector
                        GENERATED ALWAYS AS (to_tsvector('simple', coalesce(content, ''))) STORED
                """);

        float[] fixedVector = fixedVector();
        given(embeddingModel.dimensions()).willReturn(768);
        given(embeddingModel.embed(any(String.class))).willReturn(fixedVector);
        given(embeddingModel.embed(any(Document.class))).willReturn(fixedVector);
        given(embeddingModel.embed(any(List.class))).willReturn(List.of(fixedVector));
        given(embeddingModel.embed(any(List.class), any(EmbeddingOptions.class), any(BatchingStrategy.class)))
                .willAnswer(inv -> {
                    List<?> documents = inv.getArgument(0);
                    return documents.stream().map(doc -> fixedVector).toList();
                });

        // Two different prompts go through this same mock: the regular answer prompt
        // and the groundedness-verification prompt (ADR 0008). A single canned
        // response for both would make grounded=true silently pass without actually
        // exercising the verification path, so the verdict text is picked by
        // inspecting which system prompt was used.
        given(chatModel.call(any(Prompt.class))).willAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            String content = prompt.getSystemMessage().getText().contains("SUPORTADA")
                    ? "SUPORTADA"
                    : "O padrão SAGA coordena transações distribuídas [1]";
            return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
        });

        vectorStore.add(List.of(Document.builder()
                .text("O padrão SAGA coordena transações distribuídas usando choreography ou orchestration.")
                .metadata(Map.of("source", "aula12.md", "documentId", "doc-1", "chunkIndex", 0, "tenantId", "default"))
                .build()));
    }

    @Test
    void answersWithCitationsFromTheVectorStore() throws Exception {
        mockMvc.perform(post("/api/v1/chat")
                        .contentType("application/json")
                        .content("{\"question\":\"Como funciona o padrão SAGA?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("SAGA")))
                .andExpect(jsonPath("$.citations[0].source").value("aula12.md"));
    }

    @Test
    void isolatesRetrievalByTenantId() throws Exception {
        vectorStore.add(List.of(
                Document.builder()
                        .text("O padrão SAGA coordena transações distribuídas usando choreography ou orchestration.")
                        .metadata(Map.of("source", "tenant-a-doc.md", "documentId", "doc-a", "chunkIndex", 0, "tenantId", "tenant-a"))
                        .build(),
                Document.builder()
                        .text("O padrão SAGA coordena transações distribuídas usando choreography ou orchestration.")
                        .metadata(Map.of("source", "tenant-b-doc.md", "documentId", "doc-b", "chunkIndex", 0, "tenantId", "tenant-b"))
                        .build()));

        mockMvc.perform(post("/api/v1/chat")
                        .header("X-Tenant-Id", "tenant-a")
                        .contentType("application/json")
                        .content("{\"question\":\"Como funciona o padrão SAGA?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citations.length()").value(1))
                .andExpect(jsonPath("$.citations[0].source").value("tenant-a-doc.md"));
    }

    @Test
    void groundedRequestReturnsSupportedVerdict() throws Exception {
        mockMvc.perform(post("/api/v1/chat")
                        .contentType("application/json")
                        .content("{\"question\":\"Como funciona o padrão SAGA?\",\"grounded\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groundedness").value("SUPPORTED"));
    }

    @Test
    void groundedRequestReturnsNotSupportedVerdict() throws Exception {
        given(chatModel.call(any(Prompt.class))).willAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            String content = prompt.getSystemMessage().getText().contains("SUPORTADA")
                    ? "NAO_SUPORTADA"
                    : "O padrão SAGA foi inventado no Brasil em 1990 [1]";
            return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
        });

        mockMvc.perform(post("/api/v1/chat")
                        .contentType("application/json")
                        .content("{\"question\":\"Como funciona o padrão SAGA?\",\"grounded\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groundedness").value("NOT_SUPPORTED"));
    }

    @Test
    void hybridSearchFindsRareTermDocumentThatVectorSearchAloneWouldMiss() throws Exception {
        // Deliberately opposite vectors (cosine similarity -1) so vector search alone,
        // with the configured 0.5 similarity threshold, would never surface this
        // document — only the full-text leg's exact match on "Globodyne" can. This is
        // the concrete case ADR 0012 exists for: a rare proper noun the embedding
        // model doesn't happen to place near the query in vector space.
        float[] queryVector = fixedVector();
        float[] oppositeVector = oppositeVector();

        given(embeddingModel.embed(any(String.class))).willAnswer(inv -> {
            String text = inv.getArgument(0);
            return text.contains("Globodyne") ? oppositeVector : queryVector;
        });
        given(embeddingModel.embed(any(Document.class))).willAnswer(inv -> {
            Document doc = inv.getArgument(0);
            return doc.getText().contains("Globodyne") ? oppositeVector : queryVector;
        });
        given(embeddingModel.embed(any(List.class), any(EmbeddingOptions.class), any(BatchingStrategy.class)))
                .willAnswer(inv -> {
                    List<Document> documents = inv.getArgument(0);
                    return documents.stream()
                            .map(doc -> doc.getText().contains("Globodyne") ? oppositeVector : queryVector)
                            .toList();
                });

        vectorStore.add(List.of(Document.builder()
                .text("A Globodyne é a fornecedora exclusiva de hardware do datacenter.")
                .metadata(Map.of("source", "globodyne-doc.md", "documentId", "doc-rare", "chunkIndex", 0, "tenantId", "default"))
                .build()));

        mockMvc.perform(post("/api/v1/chat")
                        .contentType("application/json")
                        .content("{\"question\":\"Globodyne\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citations[*].source", org.hamcrest.Matchers.hasItem("globodyne-doc.md")));
    }

    @Test
    void ungroundedRequestOmitsGroundedness() throws Exception {
        mockMvc.perform(post("/api/v1/chat")
                        .contentType("application/json")
                        .content("{\"question\":\"Como funciona o padrão SAGA?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groundedness").doesNotExist());
    }

    private static float[] fixedVector() {
        Random random = new Random(7);
        float[] vector = new float[768];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = random.nextFloat();
        }
        return vector;
    }

    /** Exact opposite direction of {@link #fixedVector()} — cosine similarity -1. */
    private static float[] oppositeVector() {
        float[] vector = fixedVector();
        float[] negated = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            negated[i] = -vector[i];
        }
        return negated;
    }
}
