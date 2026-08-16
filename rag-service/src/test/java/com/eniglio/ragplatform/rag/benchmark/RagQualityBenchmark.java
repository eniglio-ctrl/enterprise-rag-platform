package com.eniglio.ragplatform.rag.benchmark;

import com.eniglio.ragplatform.common.web.RetrievedChunk;
import com.eniglio.ragplatform.rag.RagServiceApplication;
import com.eniglio.ragplatform.rag.dto.ChatResponse;
import com.eniglio.ragplatform.rag.dto.ContextRelevance;
import com.eniglio.ragplatform.rag.dto.Groundedness;
import com.eniglio.ragplatform.rag.service.RagQueryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
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

import static com.eniglio.ragplatform.rag.benchmark.BenchmarkSupport.cosineSimilarity;
import static com.eniglio.ragplatform.rag.benchmark.BenchmarkSupport.truncate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Measures real answer quality (Fase 7c) against a fixed set of question/expected-
 * answer pairs ({@code benchmark/qa-pairs.json}), scoring cosine similarity between
 * each generated answer's embedding and its expected answer's embedding — via the
 * same {@link EmbeddingModel} the app already injects everywhere else, no new
 * dependency.
 * <p>
 * Multi-LLM Phase 8 extended this with two more real, measured-per-question metrics
 * instead of just the one cosine-similarity score:
 * <ul>
 * <li><b>Faithfulness</b> — reuses ADR 0008's own groundedness check rather than
 * building a second, parallel implementation. Simply flips {@code answer()}'s
 * {@code grounded} argument to {@code true}, so {@code response.groundedness()}
 * comes back already computed against the exact context the answer was actually
 * generated from.</li>
 * <li><b>Context relevance</b> — independent of the final answer: {@link
 * RagQueryService#retrieve(String, String)} is called separately to get the
 * full, untruncated retrieved-chunk text (not {@code Citation}'s 200-char
 * snippet), and each chunk is judged against the question via {@link
 * RagQueryService#checkContextRelevance(String, String)}.</li>
 * </ul>
 * Both add real LLM calls per question (one more for the chunk-relevance judge, per
 * retrieved chunk) — acceptable here since this is an opt-in benchmark, never run in
 * CI.
 * <p>
 * Multi-LLM Phase 16 added one more thing this benchmark was missing: every run used
 * to print its numbers to stdout and vanish, so there was never anything to compare
 * a later run against (ADR 0034 flagged exactly this as its own unclosed follow-up).
 * Each run now also appends one row — date, git commit, average similarity, faithful
 * count, average context-relevance, average per-question latency — to the real,
 * git-tracked {@code src/test/resources/benchmark/history.csv} ({@link
 * BenchmarkSupport#appendHistoryRow}). A plain {@code git diff
 * rag-service/src/test/resources/benchmark/history.csv} after a run is the whole
 * "did this actually move the numbers" story — no dashboard, no new service.
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
        // Postgres needs the same columns Flyway's V2/V3 migrations add for
        // HybridSearchService's full-text leg, exactly like ChatQueryIT already does.
        // Multi-LLM Phase 16 found this had drifted from ChatQueryIT's own setup:
        // this benchmark's content_tsv column still used plain 'simple' from before
        // ADR 0042 introduced 'unaccent_simple' - HybridSearchService's SQL has
        // queried with 'unaccent_simple' ever since, so every run of this benchmark
        // since that ADR shipped would have failed with "text search configuration
        // unaccent_simple does not exist" the moment it hit the full-text leg. Nobody
        // noticed because nobody had re-run this benchmark since - exactly the gap
        // Phase 16's history tracking exists to catch going forward.
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
            // Harmless on a re-run against a container that already has it - CREATE
            // TEXT SEARCH CONFIGURATION has no IF NOT EXISTS form.
        }
        jdbcTemplate.execute("""
                ALTER TABLE vector_store
                    ADD COLUMN IF NOT EXISTS content_tsv tsvector
                        GENERATED ALWAYS AS (to_tsvector('unaccent_simple', coalesce(content, ''))) STORED
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
        int faithfulCount = 0;
        double totalContextRelevanceRate = 0;
        long totalLatencyMs = 0;

        for (QaPair pair : qaPairs) {
            // grounded=true (was false): gets the real faithfulness verdict for free,
            // computed by the same call against the exact context actually used —
            // no need for a second, separately-reconstructed context string.
            Instant startedAt = Instant.now();
            ChatResponse response = ragQueryService.answer(pair.question(), "benchmark", true, false, null);
            totalLatencyMs += java.time.Duration.between(startedAt, Instant.now()).toMillis();

            float[] expectedEmbedding = embeddingModel.embed(pair.expectedAnswer());
            float[] actualEmbedding = embeddingModel.embed(response.answer());
            double similarity = cosineSimilarity(expectedEmbedding, actualEmbedding);
            totalSimilarity += similarity;

            Groundedness groundedness = response.groundedness();
            if (groundedness == Groundedness.SUPPORTED) {
                faithfulCount++;
            }

            List<RetrievedChunk> retrieved = ragQueryService.retrieve(pair.question(), "benchmark");
            long relevantCount = retrieved.stream()
                    .filter(chunk -> ragQueryService.checkContextRelevance(pair.question(), chunk.content())
                            == ContextRelevance.RELEVANT)
                    .count();
            double contextRelevanceRate = retrieved.isEmpty() ? 0.0 : (double) relevantCount / retrieved.size();
            totalContextRelevanceRate += contextRelevanceRate;

            report.add("%.3f  faithful=%-5s  ctx-relevance=%.2f  %-24s  %s".formatted(
                    similarity, groundedness == Groundedness.SUPPORTED, contextRelevanceRate, pair.source(),
                    truncate(response.answer(), 70)));
        }

        double averageSimilarity = totalSimilarity / qaPairs.size();
        double averageContextRelevance = totalContextRelevanceRate / qaPairs.size();
        double averageLatencyMs = (double) totalLatencyMs / qaPairs.size();

        System.out.println();
        System.out.println("=== RAG Quality Benchmark (" + qaPairs.size() + " questions) ===");
        report.forEach(System.out::println);
        System.out.printf("Average similarity: %.3f (minimum bar: %.2f)%n",
                averageSimilarity, MINIMUM_ACCEPTABLE_AVERAGE_SIMILARITY);
        System.out.printf("Faithful answers: %d/%d%n", faithfulCount, qaPairs.size());
        System.out.printf("Average context-relevance rate: %.2f%n", averageContextRelevance);
        System.out.printf("Average answer latency: %.0f ms%n%n", averageLatencyMs);

        // Multi-LLM Phase 16: leaves a git-tracked trace of this run so a later run
        // can be diffed against it - see BenchmarkSupport.appendHistoryRow's javadoc.
        BenchmarkSupport.appendHistoryRow(
                qaPairs.size(), averageSimilarity, faithfulCount, averageContextRelevance, averageLatencyMs);

        assertThat(averageSimilarity).isGreaterThanOrEqualTo(MINIMUM_ACCEPTABLE_AVERAGE_SIMILARITY);
    }
}
