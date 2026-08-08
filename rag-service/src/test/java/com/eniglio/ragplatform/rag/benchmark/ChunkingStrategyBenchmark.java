package com.eniglio.ragplatform.rag.benchmark;

import com.eniglio.ragplatform.common.chunking.MarkdownAwareTextSplitter;
import com.eniglio.ragplatform.common.chunking.RecursiveCharacterTextSplitter;
import com.eniglio.ragplatform.rag.RagServiceApplication;
import com.eniglio.ragplatform.rag.dto.ChatResponse;
import com.eniglio.ragplatform.rag.service.RagQueryService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static com.eniglio.ragplatform.rag.benchmark.BenchmarkSupport.cosineSimilarity;

/**
 * Multi-LLM Phase 8 (RAG quality deep-dive): compares the production baseline
 * ({@code TokenTextSplitter}, fixed token count, no structural awareness) against
 * two structure-aware alternatives ({@link RecursiveCharacterTextSplitter}, {@link
 * MarkdownAwareTextSplitter}) on real answer quality — not a synthetic fixture.
 * <p>
 * <b>Corpus is this project's own {@code docs/architecture.md}</b>, read directly
 * from the working tree (not copied into test resources, so it can never drift from
 * the real file) — the roadmap explicitly suggested testing against real, already-
 * seeded Markdown content rather than a purpose-built fixture. Each of the 4
 * questions below targets a specific fact that lives in one particular heading
 * section of that file, so a chunk boundary landing mid-section (the baseline's
 * failure mode this phase is investigating) has a real chance of showing up as a
 * worse answer.
 * <p>
 * Chunk size is deliberately small (150 tokens / ~600 chars, well under the
 * production {@code ingestion.chunk-size-tokens: 800} default) specifically to
 * force multiple chunks out of a single ~180-line document — at the real default
 * size this file would likely fit in one or two chunks, which would make any
 * strategy comparison meaningless. This benchmark's numbers say nothing about
 * which chunk *size* to run in production; only about which *strategy* wins at a
 * size small enough to actually exercise boundary placement.
 * <p>
 * Deliberately real Ollama calls, real Postgres/pgvector, gated the same way as
 * {@link RagQualityBenchmark} — never runs in CI:
 *
 * <pre>
 * ./mvnw test -pl rag-service -Dtest=ChunkingStrategyBenchmark -Dbenchmark=true \
 *     -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@Testcontainers
@SpringBootTest(classes = RagServiceApplication.class)
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "benchmark", matches = "true")
class ChunkingStrategyBenchmark {

    private static final int CHUNK_SIZE_TOKENS = 150;
    private static final int CHUNK_SIZE_CHARS = 600;
    private static final String TENANT_ID = "chunking-benchmark";

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
    private RagQueryService ragQueryService;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record Question(String question, String expectedAnswer) {
    }

    private record Variant(String name, TextSplitter splitter) {
    }

    // Each answer lives in one specific heading section of docs/architecture.md -
    // "Overview", "Ingestion flow", "Query flow", "Multi-turn conversation flow" -
    // chosen so a chunk boundary cutting through that section is likely to hurt
    // retrieval, which a fixed-size cut has no way to avoid.
    private static final List<Question> QUESTIONS = List.of(
            new Question(
                    "O que o ingestion-service faz antes de qualquer parser ou modelo receber o upload?",
                    "Ele valida a extensão, o tipo de conteúdo declarado e os magic bytes do arquivo "
                            + "antes de qualquer parser ou modelo processá-lo."),
            new Question(
                    "Como o POST /api/v1/ask decide entre gerar um diagrama ou uma resposta de texto?",
                    "Ele primeiro pede ao modelo de chat resolvido uma classificação de intenção de uma "
                            + "palavra, em temperatura zero: DIAGRAMA ou RESPOSTA."),
            new Question(
                    "O chat-service duplica a lógica de busca (retrieval) do rag-service?",
                    "Não. Para cada mensagem, o chat-service encaminha o próprio bearer token do "
                            + "usuário para o endpoint de retrieval do rag-service, em vez de "
                            + "reimplementar a busca."),
            new Question(
                    "Por que o PostgreSQL continua compartilhado entre os serviços deste projeto?",
                    "Porque o sistema deliberadamente mantém o PostgreSQL compartilhado enquanto está "
                            + "em escala de portfólio/MVP - uma troca documentada, não um limite "
                            + "acidental."));

    @Test
    void compareChunkingStrategies() throws Exception {
        // Multi-LLM Phase 16 found this had drifted from ChatQueryIT's setup: this
        // still used plain 'simple' from before ADR 0042 introduced 'unaccent_simple',
        // while HybridSearchService's full-text SQL (hit via ragQueryService.answer
        // below) has queried with 'unaccent_simple' ever since - see
        // RagQualityBenchmark's identical fix/comment for the full story.
        jdbcTemplate.execute("""
                ALTER TABLE vector_store
                    ADD COLUMN IF NOT EXISTS tenant_id text
                        GENERATED ALWAYS AS (metadata->>'tenantId') STORED
                """);
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS unaccent");
        try {
            jdbcTemplate.execute("CREATE TEXT SEARCH CONFIGURATION unaccent_simple (COPY = simple)");
            jdbcTemplate.execute("""
                    ALTER TEXT SEARCH CONFIGURATION unaccent_simple
                        ALTER MAPPING FOR hword, hword_part, word WITH unaccent, simple
                    """);
        } catch (org.springframework.dao.DataAccessException alreadyExists) {
            // Harmless on a re-run against a container that already has it.
        }
        jdbcTemplate.execute("""
                ALTER TABLE vector_store
                    ADD COLUMN IF NOT EXISTS content_tsv tsvector
                        GENERATED ALWAYS AS (to_tsvector('unaccent_simple', coalesce(content, ''))) STORED
                """);

        String architectureDoc = Files.readString(Path.of("../docs/architecture.md"));

        List<Variant> variants = List.of(
                new Variant("baseline (TokenTextSplitter)", new TokenTextSplitter(
                        CHUNK_SIZE_TOKENS, 350, 5, 10000, true)),
                new Variant("recursive", new RecursiveCharacterTextSplitter(CHUNK_SIZE_CHARS)),
                new Variant("markdown-aware", new MarkdownAwareTextSplitter(CHUNK_SIZE_CHARS)));

        System.out.println();
        System.out.println("=== Chunking Strategy Benchmark (" + QUESTIONS.size() + " questions, "
                + "docs/architecture.md) ===");

        for (Variant variant : variants) {
            jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'tenantId' = ?", TENANT_ID);

            Document source = Document.builder()
                    .text(architectureDoc)
                    .metadata(Map.of("source", "architecture.md", "tenantId", TENANT_ID))
                    .build();
            List<Document> chunks = variant.splitter().apply(List.of(source));
            vectorStore.add(chunks);

            double totalSimilarity = 0;
            for (Question question : QUESTIONS) {
                ChatResponse response = ragQueryService.answer(question.question(), TENANT_ID, false, false, null);
                float[] expected = embeddingModel.embed(question.expectedAnswer());
                float[] actual = embeddingModel.embed(response.answer());
                totalSimilarity += cosineSimilarity(expected, actual);
            }
            double averageSimilarity = totalSimilarity / QUESTIONS.size();

            System.out.printf("%-28s chunks=%-3d avg-similarity=%.3f%n",
                    variant.name(), chunks.size(), averageSimilarity);
        }
        System.out.println();
    }
}
