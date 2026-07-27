package com.eniglio.ragplatform.rag.service;

import com.eniglio.ragplatform.rag.config.RagProperties;
import com.eniglio.ragplatform.rag.gateway.VectorStoreGateway;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Combines pgvector similarity search with Postgres full-text search via Reciprocal
 * Rank Fusion (ADR 0012). Spring AI 1.0.0 has no native RRF, so the full-text leg is
 * raw SQL against the {@code content_tsv} column added in ADR 0011.
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    /** Standard RRF constant from the literature (e.g. Elasticsearch's own RRF). */
    private static final int RRF_K = 60;

    private static final String FULL_TEXT_SEARCH_SQL = """
            SELECT id, content, metadata
            FROM vector_store
            WHERE content_tsv @@ to_tsquery('simple', ?)
              AND tenant_id = ?
            ORDER BY ts_rank(content_tsv, to_tsquery('simple', ?)) DESC
            LIMIT ?
            """;

    private final VectorStoreGateway vectorStoreGateway;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RagProperties ragProperties;

    public HybridSearchService(VectorStoreGateway vectorStoreGateway, JdbcTemplate jdbcTemplate,
                                ObjectMapper objectMapper, RagProperties ragProperties) {
        this.vectorStoreGateway = vectorStoreGateway;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.ragProperties = ragProperties;
    }

    /**
     * Retrieves a candidate pool from both the vector and full-text legs and fuses
     * them by RRF, returning the top {@code topK}. A document with low embedding
     * similarity but an exact rare-term match (e.g. a proper noun) can still surface
     * here purely on the strength of the full-text leg.
     *
     * {@code 'simple'} (not {@code 'portuguese'}/{@code 'english'}) is deliberate:
     * content can be in either language, and a fixed stemming config would silently
     * degrade matches in whichever language it wasn't tuned for.
     *
     * {@code [achado]} the plan called for {@code plainto_tsquery}, which ANDs every
     * token together — a real natural-language question ("Onde fica o Globodyne?")
     * would then require the document to contain "onde"/"fica"/"o" too, not just the
     * one term that actually matters, defeating the entire point of the full-text leg
     * for exactly the rare-term case it exists for. The query is built as an OR of its
     * significant words instead (via {@code to_tsquery}, not raw string
     * concatenation — {@link #buildOrTsQuery} strips anything that isn't a letter or
     * digit before joining, so it can't produce invalid tsquery syntax from arbitrary
     * user input), so a document matching even one rare term ranks in via
     * {@code ts_rank}, without needing every common word around it too.
     */
    public List<Document> search(String question, String tenantId, int topK) {
        int poolSize = ragProperties.rerankCandidatePoolSize();

        SearchRequest vectorRequest = SearchRequest.builder()
                .query(question)
                .topK(poolSize)
                .similarityThreshold(ragProperties.similarityThreshold())
                .filterExpression(tenantFilter(tenantId))
                .build();
        List<Document> vectorResults = vectorStoreGateway.search(vectorRequest);
        List<Document> textResults = fullTextSearch(question, tenantId, poolSize);

        List<Document> fused = fuseWithRrf(vectorResults, textResults, topK);
        log.info("Hybrid search: {} vector hits, {} full-text hits, {} after RRF fusion",
                vectorResults.size(), textResults.size(), fused.size());
        return fused;
    }

    private List<Document> fullTextSearch(String question, String tenantId, int limit) {
        String orTsQuery = buildOrTsQuery(question);
        if (orTsQuery.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query(FULL_TEXT_SEARCH_SQL,
                (rs, rowNum) -> Document.builder()
                        .id(rs.getString("id"))
                        .text(rs.getString("content"))
                        .metadata(parseMetadata(rs.getString("metadata")))
                        .build(),
                orTsQuery, tenantId, orTsQuery, limit);
    }

    /**
     * Builds a {@code word1 | word2 | ...} tsquery string. Stripping to alphanumerics
     * before joining is what makes this safe to build from raw user input — there's no
     * character left in any token that could alter tsquery syntax (parentheses,
     * {@code &}/{@code |}/{@code !}, weight labels), so this can never become anything
     * other than a flat OR of plain words.
     */
    private String buildOrTsQuery(String question) {
        String sanitized = question.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}\\s]", " ");
        return Arrays.stream(sanitized.split("\\s+"))
                .filter(token -> !token.isBlank())
                .distinct()
                .collect(Collectors.joining(" | "));
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse metadata JSON from full-text search row, using empty metadata", e);
            return Map.of();
        }
    }

    /**
     * RRF score for a document is the sum of {@code 1/(k+rank)} across every list it
     * appears in (1-indexed rank); absence from a list contributes nothing, not a
     * penalty. Fused results carry the RRF score, not the original vector/text score,
     * since the two aren't on comparable scales. Package-private (not private) so it
     * can be unit tested directly with plain document lists, without needing to mock
     * JDBC.
     */
    List<Document> fuseWithRrf(List<Document> vectorResults, List<Document> textResults, int topK) {
        Map<String, Double> scoreById = new LinkedHashMap<>();
        Map<String, Document> documentById = new LinkedHashMap<>();
        accumulateRankScores(vectorResults, scoreById, documentById);
        accumulateRankScores(textResults, scoreById, documentById);

        return scoreById.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> documentById.get(entry.getKey()).mutate().score(entry.getValue()).build())
                .toList();
    }

    private void accumulateRankScores(List<Document> results, Map<String, Double> scoreById,
                                       Map<String, Document> documentById) {
        for (int i = 0; i < results.size(); i++) {
            Document document = results.get(i);
            int rank = i + 1;
            scoreById.merge(document.getId(), 1.0 / (RRF_K + rank), Double::sum);
            documentById.putIfAbsent(document.getId(), document);
        }
    }

    private Filter.Expression tenantFilter(String tenantId) {
        return new FilterExpressionBuilder().eq("tenantId", tenantId).build();
    }
}
