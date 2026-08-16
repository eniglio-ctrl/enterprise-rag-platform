package com.eniglio.ragplatform.rag.integration;

import com.eniglio.ragplatform.common.authorization.DocumentVisibility;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
        // docs/ROADMAP.md item #17: overridden down from application.yml's real
        // default (4) to exactly 1 for this whole test class, deliberately, so
        // bulkheadRejectsAConcurrentOllamaCallWhenTheLimitIsAlreadySaturated below
        // can make a deterministic assertion without racing a higher real limit.
        // Harmless for every other test in this class: none of them issue two
        // requests concurrently, so a limit of 1 never conflicts with anything else
        // here - each request finishes before the next one starts.
        registry.add("resilience4j.bulkhead.instances.ollama.max-concurrent-calls", () -> "1");
        registry.add("resilience4j.bulkhead.instances.ollama.max-wait-duration", () -> "0");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private EmbeddingModel embeddingModel;

    // Named explicitly (ADR 0017): two ChatModel beans exist now (ollamaChatModel,
    // openAiChatModel for LM Studio), so Spring's bean-override can't auto-pick one.
    // This test exercises the Ollama path, the default provider.
    @MockitoBean(name = "ollamaChatModel")
    private ChatModel chatModel;

    @BeforeEach
    void seedVectorStoreAndStubModels() {
        // The Testcontainers Postgres instance is shared (class-level @Container)
        // across every test method - without this, each method's own vectorStore.add
        // calls plus this method's own "aula12.md" seed accumulate forever across the
        // whole run, growing duplicate noise that can eventually crowd a real match
        // out of a small top-k result (found for real: adding more test methods to
        // this class started intermittently failing an unrelated, already-passing
        // test purely from accumulated duplicate "aula12.md" rows outweighing a
        // genuine single-row match elsewhere). Every test method already seeds
        // whatever it needs itself, so a clean slate here only improves isolation.
        jdbcTemplate.execute("DELETE FROM vector_store");

        // rag-service never runs Flyway (ADR 0011) — it only reads a schema
        // ingestion-service migrates. This test's Postgres is standalone (no
        // ingestion-service involved), so it needs the same columns Flyway's V2/V3
        // migrations add, or HybridSearchService's full-text SQL leg has nothing to
        // query against. Mirrors those migrations' DDL exactly; IF NOT EXISTS/OR
        // REPLACE makes re-running this every test method harmless. CREATE TEXT
        // SEARCH CONFIGURATION has no IF NOT EXISTS form, hence the catch.
        jdbcTemplate.execute("""
                ALTER TABLE vector_store
                    ADD COLUMN IF NOT EXISTS tenant_id text
                        GENERATED ALWAYS AS (metadata->>'tenantId') STORED,
                    ADD COLUMN IF NOT EXISTS user_id text
                        GENERATED ALWAYS AS (metadata->>'userId') STORED
                """);
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS unaccent");
        try {
            jdbcTemplate.execute("CREATE TEXT SEARCH CONFIGURATION unaccent_simple (COPY = simple)");
            jdbcTemplate.execute("""
                    ALTER TEXT SEARCH CONFIGURATION unaccent_simple
                        ALTER MAPPING FOR hword, hword_part, word WITH unaccent, simple
                    """);
        } catch (org.springframework.dao.DataAccessException alreadyExists) {
            // Testcontainers reuses the same container across every test method in
            // this class - only the first method's call actually creates it.
        }
        jdbcTemplate.execute("""
                ALTER TABLE vector_store
                    ADD COLUMN IF NOT EXISTS content_tsv tsvector
                        GENERATED ALWAYS AS (to_tsvector('unaccent_simple', coalesce(content, ''))) STORED
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

    /** Every JWT in this test class carries the same claim shape auth-service issues (ADR 0016). */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor jwtFor(String tenantId) {
        return jwtFor(tenantId, "user-1");
    }

    /** docs/ROADMAP.md item #24: a distinct userId, for tests that need two different users in the same tenant. */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor jwtFor(String tenantId, String userId) {
        return jwt().jwt(token -> token.subject(userId).claim("tenantId", tenantId));
    }

    /**
     * docs/adr/0059-department-based-sharing.md, docs/adr/0060-multi-department
     * -membership-and-approval.md: a distinct departments claim, for department-sharing
     * tests - a user can belong to several at once now.
     */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor jwtFor(
            String tenantId, String userId, List<String> departments) {
        return jwt().jwt(token -> token.subject(userId).claim("tenantId", tenantId).claim("departments", departments));
    }

    @Test
    void answersWithCitationsFromTheVectorStore() throws Exception {
        mockMvc.perform(post("/api/v1/chat")
                        .with(jwtFor("default"))
                        .contentType("application/json")
                        .content("{\"question\":\"Como funciona o padrão SAGA?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("SAGA")))
                .andExpect(jsonPath("$.citations[0].source").value("aula12.md"));
    }

    // --- docs/PRODUCT-DIFFERENTIATION-ROADMAP.md Phase 8: summarize/FAQ ---

    @Test
    void summarizesADocumentFromItsWholeIndexedContentNotASimilaritySearch() throws Exception {
        // Reuses the "doc-1" document seeded in @BeforeEach - the default chatModel
        // stub's non-"SUPORTADA" branch is a perfectly fine stand-in summary here.
        mockMvc.perform(post("/api/v1/documents/doc-1/summarize").with(jwtFor("default")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value(org.hamcrest.Matchers.containsString("SAGA")))
                .andExpect(jsonPath("$.source").value("aula12.md"))
                .andExpect(jsonPath("$.documentId").value("doc-1"));
    }

    @Test
    void summarizingAnUnknownDocumentIdReturns404() throws Exception {
        mockMvc.perform(post("/api/v1/documents/does-not-exist/summarize").with(jwtFor("default")))
                .andExpect(status().isNotFound());
    }

    @Test
    void generatesAFaqForADocumentParsedFromTheModelsDelimitedTextFormat() throws Exception {
        given(chatModel.call(any(Prompt.class))).willReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage("""
                        P: O que é o padrão SAGA?
                        R: Um padrão para coordenar transações distribuídas.
                        """)))));

        mockMvc.perform(post("/api/v1/documents/doc-1/faq").with(jwtFor("default")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].question").value("O que é o padrão SAGA?"))
                .andExpect(jsonPath("$.items[0].answer").value("Um padrão para coordenar transações distribuídas."))
                .andExpect(jsonPath("$.source").value("aula12.md"))
                .andExpect(jsonPath("$.documentId").value("doc-1"));
    }

    @Test
    void generatingAFaqForAnUnknownDocumentIdReturns404() throws Exception {
        mockMvc.perform(post("/api/v1/documents/does-not-exist/faq").with(jwtFor("default")))
                .andExpect(status().isNotFound());
    }

    @Test
    void faqGenerationReturns500WhenTheModelIgnoresTheRequestedFormat() throws Exception {
        given(chatModel.call(any(Prompt.class))).willReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("Aqui está um resumo em vez de um FAQ.")))));

        mockMvc.perform(post("/api/v1/documents/doc-1/faq").with(jwtFor("default")))
                .andExpect(status().isInternalServerError());
    }

    // --- docs/adr/0057-document-comparison.md ---

    @Test
    void comparesTwoDocumentsFromTheirWholeIndexedContent() throws Exception {
        // "doc-1" (aula12.md) is already seeded in @BeforeEach; this test seeds a
        // second document itself, same convention every other test in this class
        // already follows.
        vectorStore.add(List.of(Document.builder()
                .text("2PC usa um coordenador central para confirmar transações distribuídas.")
                .metadata(Map.of("source", "2pc.md", "documentId", "doc-2", "chunkIndex", 0, "tenantId", "default"))
                .build()));

        mockMvc.perform(post("/api/v1/documents/compare")
                        .with(jwtFor("default"))
                        .contentType("application/json")
                        .content("{\"documentIds\":[\"doc-1\",\"doc-2\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comparison").value(org.hamcrest.Matchers.containsString("SAGA")))
                .andExpect(jsonPath("$.sources[0]").value("aula12.md"))
                .andExpect(jsonPath("$.sources[1]").value("2pc.md"))
                .andExpect(jsonPath("$.documentIds[0]").value("doc-1"))
                .andExpect(jsonPath("$.documentIds[1]").value("doc-2"));
    }

    @Test
    void comparingWithAnUnknownDocumentIdReturns404() throws Exception {
        mockMvc.perform(post("/api/v1/documents/compare")
                        .with(jwtFor("default"))
                        .contentType("application/json")
                        .content("{\"documentIds\":[\"doc-1\",\"does-not-exist\"]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void comparingWithFewerThanTwoDocumentIdsReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/documents/compare")
                        .with(jwtFor("default"))
                        .contentType("application/json")
                        .content("{\"documentIds\":[\"doc-1\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void comparingMoreThanMaxDocumentsReturns400() throws Exception {
        // application.yml's real default is rag.document-comparison.max-documents: 5.
        mockMvc.perform(post("/api/v1/documents/compare")
                        .with(jwtFor("default"))
                        .contentType("application/json")
                        .content("{\"documentIds\":[\"doc-1\",\"doc-2\",\"doc-3\",\"doc-4\",\"doc-5\",\"doc-6\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAQuestionLongerThanTheSizeLimitWith400() throws Exception {
        String tooLong = "a".repeat(8001);
        mockMvc.perform(post("/api/v1/chat")
                        .with(jwtFor("default"))
                        .contentType("application/json")
                        .content("{\"question\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest());
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
                        .with(jwtFor("tenant-a"))
                        .contentType("application/json")
                        .content("{\"question\":\"Como funciona o padrão SAGA?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citations.length()").value(1))
                .andExpect(jsonPath("$.citations[0].source").value("tenant-a-doc.md"));
    }

    @Test
    void restrictedDocumentIsInvisibleToANonOwnerNonSharedUserButVisibleToItsOwnerAndASharedUser() throws Exception {
        // docs/ROADMAP.md item #24 - the roadmap's own "done when": two users in the
        // same tenant, one explicitly not granted access, proven via the real
        // running stack (this HTTP round trip), not just a unit test of the filter
        // logic in isolation.
        vectorStore.add(List.of(Document.builder()
                .text("O projeto Quetzalcoatlus é um documento confidencial sobre a nova arquitetura interna.")
                .metadata(Map.of(
                        "source", "quetzalcoatlus-doc.md", "documentId", "doc-restricted", "chunkIndex", 0,
                        "tenantId", "default", "userId", "owner-1",
                        DocumentVisibility.VISIBILITY_KEY, DocumentVisibility.RESTRICTED,
                        DocumentVisibility.SHARED_WITH_KEY, List.of("shared-user")))
                .build()));

        // Same tenant, neither the owner nor explicitly shared with - must never see it.
        mockMvc.perform(post("/api/v1/chat")
                        .with(jwtFor("default", "other-user"))
                        .contentType("application/json")
                        .content("{\"question\":\"O que é o projeto Quetzalcoatlus?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citations[*].source",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("quetzalcoatlus-doc.md"))));

        // The owner must still see their own restricted document.
        mockMvc.perform(post("/api/v1/chat")
                        .with(jwtFor("default", "owner-1"))
                        .contentType("application/json")
                        .content("{\"question\":\"O que é o projeto Quetzalcoatlus?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citations[*].source", org.hamcrest.Matchers.hasItem("quetzalcoatlus-doc.md")));

        // Explicitly shared-with must also see it.
        mockMvc.perform(post("/api/v1/chat")
                        .with(jwtFor("default", "shared-user"))
                        .contentType("application/json")
                        .content("{\"question\":\"O que é o projeto Quetzalcoatlus?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citations[*].source", org.hamcrest.Matchers.hasItem("quetzalcoatlus-doc.md")));
    }

    // --- docs/adr/0059-department-based-sharing.md ---

    @Test
    void restrictedDocumentIsVisibleToAUserInTheSharedDepartmentButNotToOthers() throws Exception {
        vectorStore.add(List.of(Document.builder()
                .text("O orçamento do projeto Andrômeda é confidencial e só o Financeiro pode ver.")
                .metadata(Map.of(
                        "source", "andromeda-budget.md", "documentId", "doc-dept-restricted", "chunkIndex", 0,
                        "tenantId", "default", "userId", "owner-2",
                        DocumentVisibility.VISIBILITY_KEY, DocumentVisibility.RESTRICTED,
                        DocumentVisibility.SHARED_WITH_DEPARTMENTS_KEY, List.of("Financeiro")))
                .build()));

        // Same tenant, no department at all - must never see it.
        mockMvc.perform(post("/api/v1/chat")
                        .with(jwtFor("default", "no-department-user"))
                        .contentType("application/json")
                        .content("{\"question\":\"Qual o orçamento do projeto Andrômeda?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citations[*].source",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("andromeda-budget.md"))));

        // Same tenant, a different department - must never see it either.
        mockMvc.perform(post("/api/v1/chat")
                        .with(jwtFor("default", "ti-user", List.of("TI")))
                        .contentType("application/json")
                        .content("{\"question\":\"Qual o orçamento do projeto Andrômeda?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citations[*].source",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("andromeda-budget.md"))));

        // A user in the department the document is shared with must see it, without
        // being individually named in sharedWith at all.
        mockMvc.perform(post("/api/v1/chat")
                        .with(jwtFor("default", "finance-user", List.of("Financeiro")))
                        .contentType("application/json")
                        .content("{\"question\":\"Qual o orçamento do projeto Andrômeda?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citations[*].source",
                        org.hamcrest.Matchers.hasItem("andromeda-budget.md")));
    }

    // --- docs/adr/0060-multi-department-membership-and-approval.md ---

    @Test
    void aUserBelongingToSeveralDepartmentsStillSeesADocumentSharedWithJustOneOfThem() throws Exception {
        vectorStore.add(List.of(Document.builder()
                .text("O orçamento do projeto Netuno é confidencial e só o Financeiro pode ver.")
                .metadata(Map.of(
                        "source", "netuno-budget.md", "documentId", "doc-dept-multi", "chunkIndex", 0,
                        "tenantId", "default", "userId", "owner-3",
                        DocumentVisibility.VISIBILITY_KEY, DocumentVisibility.RESTRICTED,
                        DocumentVisibility.SHARED_WITH_DEPARTMENTS_KEY, List.of("Financeiro")))
                .build()));

        mockMvc.perform(post("/api/v1/chat")
                        .with(jwtFor("default", "multi-dept-user", List.of("TI", "Financeiro")))
                        .contentType("application/json")
                        .content("{\"question\":\"Qual o orçamento do projeto Netuno?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citations[*].source",
                        org.hamcrest.Matchers.hasItem("netuno-budget.md")));
    }

    // --- docs/adr/0058-document-versioning.md ---

    @Test
    void aNormalQuestionRetrievesOnlyTheLatestVersionByDefault() throws Exception {
        vectorStore.add(List.of(
                Document.builder()
                        .text("O padrão SAGA coordena transações distribuídas usando apenas choreography.")
                        .metadata(Map.of("source", "aula12-v1.md", "documentId", "doc-v1", "chunkIndex", 0,
                                "tenantId", "default", "documentGroupId", "doc-v1", "isLatestVersion", false))
                        .build(),
                Document.builder()
                        .text("O padrão SAGA coordena transações distribuídas usando apenas orchestration.")
                        .metadata(Map.of("source", "aula12-v2.md", "documentId", "doc-v2", "chunkIndex", 0,
                                "tenantId", "default", "documentGroupId", "doc-v1"))
                        .build()));

        mockMvc.perform(post("/api/v1/chat")
                        .with(jwtFor("default"))
                        .contentType("application/json")
                        .content("{\"question\":\"Como funciona o padrão SAGA?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citations[*].source", org.hamcrest.Matchers.hasItem("aula12-v2.md")))
                .andExpect(jsonPath("$.citations[*].source", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem("aula12-v1.md"))));
    }

    @Test
    void summarizeStillReachesAnOldVersionByItsExplicitDocumentId() throws Exception {
        // findByDocumentId (unlike search/findBySource) deliberately does NOT apply
        // the latest-version filter - an exact documentId lookup already IS "ask
        // against a specific version," and this must keep working after versioning.
        vectorStore.add(List.of(Document.builder()
                .text("O padrão SAGA coordena transações distribuídas usando apenas choreography.")
                .metadata(Map.of("source", "aula12-v1.md", "documentId", "doc-superseded", "chunkIndex", 0,
                        "tenantId", "default", "documentGroupId", "doc-superseded", "isLatestVersion", false))
                .build()));

        mockMvc.perform(post("/api/v1/documents/doc-superseded/summarize").with(jwtFor("default")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value("doc-superseded"));
    }

    @Test
    void groundedRequestReturnsSupportedVerdict() throws Exception {
        mockMvc.perform(post("/api/v1/chat")
                        .with(jwtFor("default"))
                        .contentType("application/json")
                        .content("{\"question\":\"Como funciona o padrão SAGA?\",\"grounded\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groundedness").value("SUPPORTED"));
    }

    @Test
    void notSupportedVerdictOffersFallbackInsteadOfTheUngroundedAnswer() throws Exception {
        // RagQueryService.doAnswer now always runs the groundedness check to gate the
        // public-LLM fallback (not just to populate this field when `grounded: true`
        // is requested) - a NOT_SUPPORTED verdict is treated the same as empty
        // retrieval, not returned as if it were a normal, successful answer.
        given(chatModel.call(any(Prompt.class))).willAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            String content = prompt.getSystemMessage().getText().contains("SUPORTADA")
                    ? "NAO_SUPORTADA"
                    : "O padrão SAGA foi inventado no Brasil em 1990 [1]";
            return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
        });

        mockMvc.perform(post("/api/v1/chat")
                        .with(jwtFor("default"))
                        .contentType("application/json")
                        .content("{\"question\":\"Como funciona o padrão SAGA?\",\"grounded\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groundedness").doesNotExist())
                .andExpect(jsonPath("$.fallbackAvailable").value(true))
                .andExpect(jsonPath("$.answer").value(
                        org.hamcrest.Matchers.containsStringIgnoringCase("não encontrei informação suficiente")));
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
                        .with(jwtFor("default"))
                        .contentType("application/json")
                        .content("{\"question\":\"Globodyne\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citations[*].source", org.hamcrest.Matchers.hasItem("globodyne-doc.md")));
    }

    @Test
    void hybridSearchMatchesAnAccentedQuestionAgainstUnaccentedIndexedContent() throws Exception {
        // docs/ROADMAP.md item #16: 'simple' alone tokenizes "manutencao" and
        // "manutenção" differently. Same opposite-vector trick as the Globodyne test
        // above, so a match here can only have come from the full-text leg's
        // accent-insensitive unaccent_simple config (V3 migration), not the vector leg.
        float[] queryVector = fixedVector();
        float[] oppositeVector = oppositeVector();

        given(embeddingModel.embed(any(String.class))).willAnswer(inv -> {
            String text = inv.getArgument(0);
            return text.contains("manutencao") ? oppositeVector : queryVector;
        });
        given(embeddingModel.embed(any(Document.class))).willAnswer(inv -> {
            Document doc = inv.getArgument(0);
            return doc.getText().contains("manutencao") ? oppositeVector : queryVector;
        });
        given(embeddingModel.embed(any(List.class), any(EmbeddingOptions.class), any(BatchingStrategy.class)))
                .willAnswer(inv -> {
                    List<Document> documents = inv.getArgument(0);
                    return documents.stream()
                            .map(doc -> doc.getText().contains("manutencao") ? oppositeVector : queryVector)
                            .toList();
                });

        vectorStore.add(List.of(Document.builder()
                .text("O procedimento de manutencao evita falhas no sistema.")
                .metadata(Map.of("source", "manutencao-doc.md", "documentId", "doc-unaccented", "chunkIndex", 0, "tenantId", "default"))
                .build()));

        mockMvc.perform(post("/api/v1/chat")
                        .with(jwtFor("default"))
                        .contentType("application/json")
                        .content("{\"question\":\"Como funciona a manutenção preventiva?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citations[*].source", org.hamcrest.Matchers.hasItem("manutencao-doc.md")));
    }

    @Test
    void hybridSearchMatchesAnUnaccentedQuestionAgainstAccentedIndexedContent() throws Exception {
        // The reverse direction of the test above: indexed content has the accent,
        // the question doesn't. Both directions need their own test since folding is
        // applied identically on both sides - a bug that broke only one direction
        // (e.g. an index-time regression that stopped applying unaccent_simple) would
        // otherwise go unnoticed if only one were checked.
        float[] queryVector = fixedVector();
        float[] oppositeVector = oppositeVector();

        given(embeddingModel.embed(any(String.class))).willAnswer(inv -> {
            String text = inv.getArgument(0);
            return text.contains("proteção") ? oppositeVector : queryVector;
        });
        given(embeddingModel.embed(any(Document.class))).willAnswer(inv -> {
            Document doc = inv.getArgument(0);
            return doc.getText().contains("proteção") ? oppositeVector : queryVector;
        });
        given(embeddingModel.embed(any(List.class), any(EmbeddingOptions.class), any(BatchingStrategy.class)))
                .willAnswer(inv -> {
                    List<Document> documents = inv.getArgument(0);
                    return documents.stream()
                            .map(doc -> doc.getText().contains("proteção") ? oppositeVector : queryVector)
                            .toList();
                });

        vectorStore.add(List.of(Document.builder()
                .text("O firewall oferece proteção contra acessos indevidos.")
                .metadata(Map.of("source", "protecao-doc.md", "documentId", "doc-accented", "chunkIndex", 0, "tenantId", "default"))
                .build()));

        mockMvc.perform(post("/api/v1/chat")
                        .with(jwtFor("default"))
                        .contentType("application/json")
                        .content("{\"question\":\"Como funciona a protecao do firewall?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citations[*].source", org.hamcrest.Matchers.hasItem("protecao-doc.md")));
    }

    @Test
    void hybridSearchMatchesAHyphenatedCompoundInBothIndexedContentAndQuestion() throws Exception {
        // docs/ROADMAP.md item #16 also flagged hyphenated compounds (e.g.
        // "e-commerce") as a related but distinct tokenization question, worth its own
        // test rather than assuming the accent fix also covers it. buildOrTsQuery
        // strips non-alphanumeric characters (including hyphens) before building the
        // OR query, and to_tsvector does the same at index time - both sides split
        // "e-commerce" into "e"/"commerce" identically, so this passes without any
        // extra change; this test exists to prove that, not to fix anything.
        float[] queryVector = fixedVector();
        float[] oppositeVector = oppositeVector();

        given(embeddingModel.embed(any(String.class))).willAnswer(inv -> {
            String text = inv.getArgument(0);
            return text.contains("e-commerce") ? oppositeVector : queryVector;
        });
        given(embeddingModel.embed(any(Document.class))).willAnswer(inv -> {
            Document doc = inv.getArgument(0);
            return doc.getText().contains("e-commerce") ? oppositeVector : queryVector;
        });
        given(embeddingModel.embed(any(List.class), any(EmbeddingOptions.class), any(BatchingStrategy.class)))
                .willAnswer(inv -> {
                    List<Document> documents = inv.getArgument(0);
                    return documents.stream()
                            .map(doc -> doc.getText().contains("e-commerce") ? oppositeVector : queryVector)
                            .toList();
                });

        vectorStore.add(List.of(Document.builder()
                .text("Nossa loja de e-commerce cresceu 40% este ano.")
                .metadata(Map.of("source", "ecommerce-doc.md", "documentId", "doc-hyphen", "chunkIndex", 0, "tenantId", "default"))
                .build()));

        mockMvc.perform(post("/api/v1/chat")
                        .with(jwtFor("default"))
                        .contentType("application/json")
                        .content("{\"question\":\"Como está o e-commerce da empresa?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citations[*].source", org.hamcrest.Matchers.hasItem("ecommerce-doc.md")));
    }

    @Test
    void bulkheadRejectsAConcurrentOllamaCallWhenTheLimitIsAlreadySaturated() throws Exception {
        // docs/ROADMAP.md item #17's own "done when": a load test with an
        // artificially small worker pool (1, via the class's own
        // @DynamicPropertySource override above) shows the bulkhead rejecting an
        // excess request cleanly (503) instead of letting it queue indefinitely
        // behind the one already in flight.
        CountDownLatch firstCallStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstCall = new CountDownLatch(1);
        given(chatModel.call(any(Prompt.class))).willAnswer(invocation -> {
            firstCallStarted.countDown();
            assertThat(releaseFirstCall.await(5, TimeUnit.SECONDS)).isTrue();
            return new ChatResponse(List.of(new Generation(new AssistantMessage("O padrão SAGA coordena transações distribuídas [1]"))));
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> firstCallResult = executor.submit(() -> mockMvc.perform(post("/api/v1/chat")
                            .with(jwtFor("default"))
                            .contentType("application/json")
                            .content("{\"question\":\"Como funciona o padrão SAGA?\"}"))
                    .andReturn());

            // Without this wait, the second request below could race ahead of the
            // first one actually reaching (and holding) the bulkhead's one permit,
            // making the assertion meaningless either way it happened to land.
            assertThat(firstCallStarted.await(5, TimeUnit.SECONDS)).isTrue();

            mockMvc.perform(post("/api/v1/chat")
                            .with(jwtFor("default"))
                            .contentType("application/json")
                            .content("{\"question\":\"Como funciona o padrão SAGA?\"}"))
                    .andExpect(status().isServiceUnavailable());

            releaseFirstCall.countDown();
            MvcResult firstResult = firstCallResult.get(5, TimeUnit.SECONDS);
            assertThat(firstResult.getResponse().getStatus()).isEqualTo(200);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void ungroundedRequestOmitsGroundedness() throws Exception {
        mockMvc.perform(post("/api/v1/chat")
                        .with(jwtFor("default"))
                        .contentType("application/json")
                        .content("{\"question\":\"Como funciona o padrão SAGA?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groundedness").doesNotExist());
    }

    @Test
    void askWithAnAttachedImageAnswersSuccessfully() throws Exception {
        MockMultipartFile image = new MockMultipartFile("image", "diagram.png", "image/png",
                new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0});

        mockMvc.perform(multipart("/api/v1/ask")
                        .file(image)
                        .param("question", "O que aparece no anexo enviado?")
                        .with(jwtFor("default")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").exists());
    }

    @Test
    void askWithImageRejectsAQuestionLongerThanTheSizeLimitWith400() throws Exception {
        MockMultipartFile image = new MockMultipartFile("image", "diagram.png", "image/png",
                new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0});
        String tooLong = "a".repeat(8001);

        mockMvc.perform(multipart("/api/v1/ask")
                        .file(image)
                        .param("question", tooLong)
                        .with(jwtFor("default")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void askRejectsAnAttachedFileWithAnUnsupportedContentType() throws Exception {
        MockMultipartFile notAnImage = new MockMultipartFile("image", "notes.txt", "text/plain",
                "just some text".getBytes());

        mockMvc.perform(multipart("/api/v1/ask")
                        .file(notAnImage)
                        .param("question", "O que esse arquivo mostra?")
                        .with(jwtFor("default")))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void askRejectsAnAttachedImageWhoseBytesDoNotMatchItsDeclaredType() throws Exception {
        MockMultipartFile fakeImage = new MockMultipartFile("image", "fake.png", "image/png",
                "not actually a png".getBytes());

        mockMvc.perform(multipart("/api/v1/ask")
                        .file(fakeImage)
                        .param("question", "O que essa imagem mostra?")
                        .with(jwtFor("default")))
                .andExpect(status().isUnprocessableEntity());
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
