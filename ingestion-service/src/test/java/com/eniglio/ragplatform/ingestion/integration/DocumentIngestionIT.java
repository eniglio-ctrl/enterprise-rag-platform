package com.eniglio.ragplatform.ingestion.integration;

import com.eniglio.ragplatform.ingestion.IngestionServiceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the full upload -> chunk -> embed -> persist flow against a real
 * Postgres/pgvector instance. The embedding model is mocked so the test does not
 * depend on a running Ollama server.
 */
@Testcontainers
@SpringBootTest(classes = IngestionServiceApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentIngestionIT {

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

    @MockitoBean
    private EmbeddingModel embeddingModel;

    @Test
    void uploadingAMarkdownFileIngestsItIntoTheVectorStore() throws Exception {
        given(embeddingModel.dimensions()).willReturn(768);
        given(embeddingModel.embed(any(String.class))).willAnswer(inv -> randomVector());
        given(embeddingModel.embed(any(org.springframework.ai.document.Document.class))).willAnswer(inv -> randomVector());
        given(embeddingModel.embed(any(List.class))).willAnswer(inv -> List.of(randomVector()));
        given(embeddingModel.embed(any(List.class), any(EmbeddingOptions.class), any(BatchingStrategy.class)))
                .willAnswer(inv -> {
                    List<?> documents = inv.getArgument(0);
                    return documents.stream().map(doc -> randomVector()).toList();
                });
        given(embeddingModel.call(any(EmbeddingRequest.class))).willAnswer(inv ->
                new EmbeddingResponse(List.of(new Embedding(randomVector(), 0))));

        String markdown = "# Aula 12\n\nO padrão SAGA coordena transações distribuídas via choreography ou orchestration.";
        MockMultipartFile file = new MockMultipartFile(
                "file", "aula12.md", "text/markdown", markdown.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/documents").file(file)
                        .with(jwt().jwt(token -> token.subject("user-1").claim("tenantId", "acme"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("aula12.md"))
                .andExpect(jsonPath("$.chunkCount").value(1));
    }

    private static float[] randomVector() {
        Random random = new Random(42);
        float[] vector = new float[768];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = random.nextFloat();
        }
        return vector;
    }
}
