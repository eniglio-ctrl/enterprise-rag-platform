package com.eniglio.ragplatform.ingestion.integration;

import com.eniglio.ragplatform.ingestion.IngestionServiceApplication;
import com.eniglio.ragplatform.ingestion.gateway.AudioTranscriptionGateway;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the full upload -> validate -> chunk -> embed -> persist flow against a
 * real Postgres/pgvector instance. The embedding model, chat model (used for image
 * description, ADR 0018) and Whisper gateway (ADR 0019) are all mocked so the test
 * does not depend on a running Ollama or Whisper server.
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

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private EmbeddingModel embeddingModel;

    @MockitoBean
    private ChatModel chatModel;

    @MockitoBean
    private AudioTranscriptionGateway audioTranscriptionGateway;

    @Test
    void uploadingAMarkdownFileIngestsItIntoTheVectorStore() throws Exception {
        stubEmbeddingModel();

        String markdown = "# Aula 12\n\nO padrão SAGA coordena transações distribuídas via choreography ou orchestration.";
        MockMultipartFile file = new MockMultipartFile(
                "file", "aula12.md", "text/markdown", markdown.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/documents").file(file)
                        .with(jwt().jwt(token -> token.subject("user-1").claim("tenantId", "acme"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("aula12.md"))
                .andExpect(jsonPath("$.chunkCount").value(1));
    }

    @Test
    void uploadingARealPdfIngestsItIntoTheVectorStore() throws Exception {
        stubEmbeddingModel();

        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", minimalPdfBytes());

        mockMvc.perform(multipart("/api/v1/documents").file(file)
                        .with(jwt().jwt(token -> token.subject("user-1").claim("tenantId", "acme"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("report.pdf"));
    }

    @Test
    void uploadingAPngDescribesItInsteadOfRejectingIt() throws Exception {
        stubEmbeddingModel();
        given(chatModel.call(any(Prompt.class))).willReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("A flowchart with three boxes labeled A, B and C.")))));

        byte[] pngBytes = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
        MockMultipartFile file = new MockMultipartFile("file", "diagram.png", "image/png", pngBytes);

        mockMvc.perform(multipart("/api/v1/documents").file(file)
                        .with(jwt().jwt(token -> token.subject("user-1").claim("tenantId", "acme"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("diagram.png"));
    }

    @Test
    void uploadingAWavFileTranscribesItInsteadOfRejectingIt() throws Exception {
        stubEmbeddingModel();
        given(audioTranscriptionGateway.transcribe(any(byte[].class), eq("meeting.wav")))
                .willReturn("Let's ship the disaster recovery runbook by Friday.");

        byte[] wavBytes = riff("WAVE");
        MockMultipartFile file = new MockMultipartFile("file", "meeting.wav", "audio/wav", wavBytes);

        mockMvc.perform(multipart("/api/v1/documents").file(file)
                        .with(jwt().jwt(token -> token.subject("user-1").claim("tenantId", "acme"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("meeting.wav"));
    }

    @Test
    void rejectsAnUnsupportedFileExtensionWith415() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "archive.zip", "application/zip", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/v1/documents").file(file)
                        .with(jwt().jwt(token -> token.subject("user-1").claim("tenantId", "acme"))))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void rejectsAFileWhoseBytesDoNotMatchItsDeclaredTypeWith422() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.pdf", "application/pdf", "not actually a pdf".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/documents").file(file)
                        .with(jwt().jwt(token -> token.subject("user-1").claim("tenantId", "acme"))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void ownerCanRestrictAndShareTheirOwnDocument() throws Exception {
        // docs/ROADMAP.md item #24: the one write path for the ABAC model - real
        // Postgres round trip, not a mock, since the whole point is proving the
        // UPDATE across every chunk row actually lands.
        stubEmbeddingModel();
        String documentId = uploadMarkdown("aula12.md", "user-owner", "acme");

        mockMvc.perform(patch("/api/v1/documents/{documentId}/sharing", documentId)
                        .with(jwt().jwt(token -> token.subject("user-owner").claim("tenantId", "acme")))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.eniglio.ragplatform.ingestion.dto.UpdateSharingRequest(
                                        "RESTRICTED", List.of("user-shared")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(documentId))
                .andExpect(jsonPath("$.visibility").value("RESTRICTED"))
                .andExpect(jsonPath("$.sharedWith[0]").value("user-shared"));
    }

    @Test
    void aNonOwnerCannotChangeAnotherUsersDocumentSharing() throws Exception {
        stubEmbeddingModel();
        String documentId = uploadMarkdown("aula12.md", "user-owner", "acme");

        mockMvc.perform(patch("/api/v1/documents/{documentId}/sharing", documentId)
                        // Same tenant, different user - tenant membership alone must not be
                        // enough to change someone else's document's sharing.
                        .with(jwt().jwt(token -> token.subject("user-other").claim("tenantId", "acme")))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.eniglio.ragplatform.ingestion.dto.UpdateSharingRequest("RESTRICTED", List.of()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void changingSharingForAnUnknownDocumentReturns404() throws Exception {
        mockMvc.perform(patch("/api/v1/documents/{documentId}/sharing", "does-not-exist")
                        .with(jwt().jwt(token -> token.subject("user-owner").claim("tenantId", "acme")))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.eniglio.ragplatform.ingestion.dto.UpdateSharingRequest("RESTRICTED", List.of()))))
                .andExpect(status().isNotFound());
    }

    @Test
    void aVisibilityValueOtherThanTenantOrRestrictedReturns400() throws Exception {
        stubEmbeddingModel();
        String documentId = uploadMarkdown("aula12.md", "user-owner", "acme");

        mockMvc.perform(patch("/api/v1/documents/{documentId}/sharing", documentId)
                        .with(jwt().jwt(token -> token.subject("user-owner").claim("tenantId", "acme")))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.eniglio.ragplatform.ingestion.dto.UpdateSharingRequest("PUBLIC", List.of()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aTenantAdminCanRestrictADocumentTheyDoNotOwn() throws Exception {
        // ADR 0047: the one bypass to the ownership check above - same tenant, a
        // different user, but with role=ADMIN on their token.
        stubEmbeddingModel();
        String documentId = uploadMarkdown("aula12.md", "user-owner", "acme");

        mockMvc.perform(patch("/api/v1/documents/{documentId}/sharing", documentId)
                        .with(jwt().jwt(token -> token.subject("user-admin").claim("tenantId", "acme")
                                .claim("role", "ADMIN")))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.eniglio.ragplatform.ingestion.dto.UpdateSharingRequest(
                                        "RESTRICTED", List.of("user-shared")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("RESTRICTED"));
    }

    @Test
    void listingDocumentsRequiresATenantAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/documents")
                        .with(jwt().jwt(token -> token.subject("user-other").claim("tenantId", "acme"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void aTenantAdminCanListEveryDocumentInTheTenantIncludingOnesIngestedBeforeAdr0046() throws Exception {
        // A dedicated tenant, not the shared "acme" every other test in this class also
        // uploads into - otherwise the exact document count asserted below would depend
        // on test execution order.
        String tenantId = "admin-list-" + java.util.UUID.randomUUID();
        stubEmbeddingModel();
        String restrictedDocId = uploadMarkdown("aula12.md", "user-owner", tenantId);
        mockMvc.perform(patch("/api/v1/documents/{documentId}/sharing", restrictedDocId)
                        .with(jwt().jwt(token -> token.subject("user-owner").claim("tenantId", tenantId)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new com.eniglio.ragplatform.ingestion.dto.UpdateSharingRequest(
                                        "RESTRICTED", List.of("user-shared")))))
                .andExpect(status().isOk());

        String legacyDocId = uploadMarkdown("legacy.md", "user-owner", tenantId);
        // Simulates a chunk ingested before ADR 0046 introduced the "visibility" key at
        // all - `metadata` is `json`, not `jsonb` (V1 migration), so the `jsonb -` key
        // removal operator needs an explicit round-trip cast.
        jdbcTemplate.update(
                "UPDATE vector_store SET metadata = (metadata::jsonb - 'visibility')::json "
                        + "WHERE metadata->>'documentId' = ?",
                legacyDocId);

        MvcResult result = mockMvc.perform(get("/api/v1/documents")
                        .with(jwt().jwt(token -> token.subject("user-admin").claim("tenantId", tenantId)
                                .claim("role", "ADMIN"))))
                .andExpect(status().isOk())
                .andReturn();

        var documents = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(documents.size()).isEqualTo(2);
        var byId = new java.util.HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        documents.forEach(doc -> byId.put(doc.get("documentId").asText(), doc));

        assertThat(byId.get(restrictedDocId).get("visibility").asText()).isEqualTo("RESTRICTED");
        assertThat(byId.get(restrictedDocId).get("sharedWith").get(0).asText()).isEqualTo("user-shared");
        assertThat(byId.get(restrictedDocId).get("ownerId").asText()).isEqualTo("user-owner");
        // No "visibility" key at all must still default to TENANT, not null - the same
        // default DocumentVisibility.isVisibleTo already applies for retrieval.
        assertThat(byId.get(legacyDocId).get("visibility").asText()).isEqualTo("TENANT");
    }

    private String uploadMarkdown(String filename, String userId, String tenantId) throws Exception {
        String markdown = "# Aula 12\n\nO padrão SAGA coordena transações distribuídas via choreography ou orchestration.";
        MockMultipartFile file = new MockMultipartFile("file", filename, "text/markdown",
                markdown.getBytes(StandardCharsets.UTF_8));
        MvcResult result = mockMvc.perform(multipart("/api/v1/documents").file(file)
                        .with(jwt().jwt(token -> token.subject(userId).claim("tenantId", tenantId))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("documentId").asText();
    }

    private void stubEmbeddingModel() {
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
    }

    private static float[] randomVector() {
        Random random = new Random(42);
        float[] vector = new float[768];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = random.nextFloat();
        }
        return vector;
    }

    private static byte[] minimalPdfBytes() throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] riff(String subFormat) {
        byte[] bytes = new byte[16];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 4);
        System.arraycopy(subFormat.getBytes(StandardCharsets.US_ASCII), 0, bytes, 8, 4);
        return bytes;
    }
}
