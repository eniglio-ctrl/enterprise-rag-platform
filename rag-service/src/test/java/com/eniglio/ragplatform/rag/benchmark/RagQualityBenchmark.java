package com.eniglio.ragplatform.rag.benchmark;

import com.eniglio.ragplatform.rag.RagServiceApplication;
import com.eniglio.ragplatform.rag.dto.ChatResponse;
import com.eniglio.ragplatform.rag.service.RagQueryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Measures real answer quality (Fase 7c) against a fixed set of question/expected-
 * answer pairs ({@code benchmark/qa-pairs.json}), scoring cosine similarity between
 * each generated answer's embedding and its expected answer's embedding — via the
 * same {@link EmbeddingModel} the app already injects everywhere else, no new
 * dependency.
 * <p>
 * <b>If this fails with "model ... not found, try pulling it first" against a model
 * that's demonstrably already pulled</b>: check for a second Ollama process
 * competing for port 11434 (a native install — {@code brew services list | grep
 * ollama} — alongside this project's own docker-compose one) before assuming
 * anything is actually broken here. Hit exactly this on the machine this was
 * written on: a long-forgotten Homebrew-managed {@code ollama serve} had been
 * running for over a week, with its own separate, mostly-empty model registry.
 * Java's loopback address resolution non-deterministically landed on whichever
 * process answered first, so the identical command failed roughly half the time
 * and succeeded the other half — {@code brew services stop ollama} resolved it
 * completely, confirmed reproducible before and after.
 * <p>
 * Also observed, and worth knowing before reading a low score as a bug: a
 * CPU-bound local {@code llama3.1} answered several of these English questions in
 * Portuguese despite the question and context both being English. Cross-lingual
 * cosine similarity between two genuinely-correct-but-different-language answers
 * is naturally lower than same-language paraphrase — a handful of the
 * individual scores below the average reflect this, not a retrieval failure.
 * <p>
 * Deliberately NOT an {@code *IT.java} class, so Surefire's {@code verify} execution
 * never picks it up, and deliberately real Ollama calls, not mocked — both mean it
 * cannot run in CI (no GPU-less-but-still-real local Ollama with {@code llama3.1}
 * and {@code nomic-embed-text} already pulled there) and must be opted into
 * explicitly:
 *
 * <pre>
 * ./mvnw test -pl rag-service -Dtest=RagQualityBenchmark -Dbenchmark=true \
 *     -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@Testcontainers
@SpringBootTest(classes = RagServiceApplication.class)
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "benchmark", matches = "true")
class RagQualityBenchmark {

    // Chosen empirically, not from a published rule of thumb — a locally run,
    // CPU-bound llama3.1 paraphrases rather than quoting the expected answer
    // verbatim, so scores well below 1.0 are normal for a genuinely correct
    // answer. Below this threshold reliably meant the retrieved chunk was wrong
    // or the answer was off-topic during manual calibration against this exact
    // qa-pairs.json, not assumed from documentation.
    private static final double MINIMUM_ACCEPTABLE_AVERAGE_SIMILARITY = 0.60;

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

    private record QaPair(String source, String context, String question, String expectedAnswer) {
    }

    @Test
    void averageAnswerSimilarityMeetsMinimumBar() throws Exception {
        // rag-service never runs Flyway (ADR 0011) — this standalone Testcontainers
        // Postgres needs the same columns Flyway's V2 migration adds for
        // HybridSearchService's full-text leg, exactly like ChatQueryIT already does.
        jdbcTemplate.execute("""
                ALTER TABLE vector_store
                    ADD COLUMN IF NOT EXISTS tenant_id text
                        GENERATED ALWAYS AS (metadata->>'tenantId') STORED,
                    ADD COLUMN IF NOT EXISTS content_tsv tsvector
                        GENERATED ALWAYS AS (to_tsvector('simple', coalesce(content, ''))) STORED
                """);

        List<QaPair> qaPairs = new ObjectMapper().readValue(
                new ClassPathResource("benchmark/qa-pairs.json").getInputStream(),
                new TypeReference<List<QaPair>>() {
                });

        // Seeded all up front, then queried — not interleaved — so every question
        // is answered against the same, complete corpus, not a partially-seeded one
        // that happens to favor earlier questions in the list.
        for (QaPair pair : qaPairs) {
            vectorStore.add(List.of(Document.builder()
                    .text(pair.context())
                    .metadata(Map.of("source", pair.source(), "tenantId", "benchmark"))
                    .build()));
        }

        List<String> report = new ArrayList<>();
        double totalSimilarity = 0;

        for (QaPair pair : qaPairs) {
            ChatResponse response = ragQueryService.answer(pair.question(), "benchmark", false, false, null);

            float[] expectedEmbedding = embeddingModel.embed(pair.expectedAnswer());
            float[] actualEmbedding = embeddingModel.embed(response.answer());
            double similarity = cosineSimilarity(expectedEmbedding, actualEmbedding);
            totalSimilarity += similarity;

            report.add("%.3f  %-24s  %s".formatted(similarity, pair.source(), truncate(response.answer(), 90)));
        }

        double averageSimilarity = totalSimilarity / qaPairs.size();

        System.out.println();
        System.out.println("=== RAG Quality Benchmark (" + qaPairs.size() + " questions) ===");
        report.forEach(System.out::println);
        System.out.printf("Average similarity: %.3f (minimum bar: %.2f)%n%n",
                averageSimilarity, MINIMUM_ACCEPTABLE_AVERAGE_SIMILARITY);

        assertThat(averageSimilarity).isGreaterThanOrEqualTo(MINIMUM_ACCEPTABLE_AVERAGE_SIMILARITY);
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}
