package com.eniglio.ragplatform.rag.service;

import com.eniglio.ragplatform.common.authorization.DocumentVisibility;
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

    // unaccent_simple (docs/ROADMAP.md item #16, V3 migration): copies 'simple' but
    // folds accents/diacritics before comparison, so a question typed without accents
    // still matches indexed content that has them, and vice versa. content_tsv is
    // generated using this same config - querying with a different one here would
    // silently reintroduce the exact mismatch this exists to close.
    private static final String FULL_TEXT_SEARCH_SQL = """
            SELECT id, content, metadata
            FROM vector_store
            WHERE content_tsv @@ to_tsquery('unaccent_simple', ?)
              AND tenant_id = ?
            ORDER BY ts_rank(content_tsv, to_tsquery('unaccent_simple', ?)) DESC
            LIMIT ?
            """;

    // Multi-LLM Phase 9: exact lookup, not similarity search - a tenant-scoped exact
    // match on the source filename, every chunk of the document in original order.
    // (metadata->>'chunkIndex')::int, not a plain text sort, since "10" would
    // otherwise sort before "2".
    private static final String LOOKUP_BY_SOURCE_SQL = """
            SELECT id, content, metadata
            FROM vector_store
            WHERE metadata->>'source' = ?
              AND tenant_id = ?
            ORDER BY (metadata->>'chunkIndex')::int
            """;

    // docs/PRODUCT-DIFFERENTIATION-ROADMAP.md Phase 8 (summaries/FAQ): same shape as
    // LOOKUP_BY_SOURCE_SQL above, but keyed by documentId - the identifier web-ui
    // actually has on hand (IngestResponse/DocumentSummary), and the one that's
    // actually unique (two documents can share a filename, never a documentId).
    private static final String LOOKUP_BY_DOCUMENT_ID_SQL = """
            SELECT id, content, metadata
            FROM vector_store
            WHERE metadata->>'documentId' = ?
              AND tenant_id = ?
            ORDER BY (metadata->>'chunkIndex')::int
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
     * {@code 'unaccent_simple'} (not {@code 'portuguese'}/{@code 'english'}) is
     * deliberate: content can be in either language, and a fixed stemming config
     * would silently degrade matches in whichever language it wasn't tuned for. It's
     * a custom config copying Postgres's built-in {@code 'simple'} but folding
     * accents/diacritics first (V3 migration, ADR 0011/0012), so "informação" and
     * "informacao" tokenize the same way on both the indexed side and the query side.
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
     * <p>
     * docs/ROADMAP.md item #24: {@code userId} enforces the ABAC check
     * ({@link DocumentVisibility#isVisibleTo}) on top of the {@code tenantId} filter
     * above — applied in Java, to both legs' candidate lists, <em>before</em> RRF
     * fusion runs, not after. Filtering first means a restricted chunk the caller
     * can't see never occupies one of {@code topK}'s slots in the fused result at
     * all; filtering the vector leg here in Java rather than via Spring AI's own
     * filter DSL was a deliberate choice — that DSL's {@code in}/{@code nin}
     * operators check a scalar metadata field against a list of candidate values,
     * not "does this document's own {@code sharedWith} array contain this one
     * caller ID," the opposite direction this check actually needs.
     */
    public List<Document> search(String question, String tenantId, String userId, int topK) {
        int poolSize = ragProperties.rerankCandidatePoolSize();

        SearchRequest vectorRequest = SearchRequest.builder()
                .query(question)
                .topK(poolSize)
                .similarityThreshold(ragProperties.similarityThreshold())
                .filterExpression(tenantFilter(tenantId))
                .build();
        List<Document> vectorResults = filterVisible(vectorStoreGateway.search(vectorRequest), userId);
        List<Document> textResults = filterVisible(fullTextSearch(question, tenantId, poolSize), userId);

        List<Document> fused = fuseWithRrf(vectorResults, textResults, topK);
        log.info("Hybrid search: {} vector hits, {} full-text hits, {} after RRF fusion",
                vectorResults.size(), textResults.size(), fused.size());
        return fused;
    }

    /**
     * Multi-LLM Phase 9: backs {@code DocumentLookupTool}, the model-invokable
     * {@code @Tool} that fetches a whole document by its exact source filename —
     * distinct from {@link #search} (similarity-ranked, partial, never exact-match).
     * {@code tenantId}/{@code userId} always come from server-side {@code
     * ToolContext}, never from the model - the boundary this enforces is the entire
     * reason this method takes them as parameters here rather than trusting the
     * source string alone. docs/ROADMAP.md item #24: a restricted document's chunks
     * are filtered out here exactly as in {@link #search}, so an LLM tool call can't
     * bypass the ABAC check that a normal question is already subject to.
     */
    public List<Document> findBySource(String source, String tenantId, String userId) {
        List<Document> chunks = jdbcTemplate.query(LOOKUP_BY_SOURCE_SQL,
                (rs, rowNum) -> Document.builder()
                        .id(rs.getString("id"))
                        .text(rs.getString("content"))
                        .metadata(parseMetadata(rs.getString("metadata")))
                        .build(),
                source, tenantId);
        return filterVisible(chunks, userId);
    }

    /**
     * docs/PRODUCT-DIFFERENTIATION-ROADMAP.md Phase 8: backs the per-document
     * summarize/FAQ endpoints ({@code RagQueryService.summarizeDocument}/{@code
     * generateFaq}) - same exact-match-and-ABAC shape as {@link #findBySource}, just
     * keyed by {@code documentId} instead of filename. A restricted/not-shared-with-you
     * document is filtered out here exactly as in {@link #search}/{@link #findBySource},
     * so it's indistinguishable from "no such document" to the caller.
     */
    public List<Document> findByDocumentId(String documentId, String tenantId, String userId) {
        List<Document> chunks = jdbcTemplate.query(LOOKUP_BY_DOCUMENT_ID_SQL,
                (rs, rowNum) -> Document.builder()
                        .id(rs.getString("id"))
                        .text(rs.getString("content"))
                        .metadata(parseMetadata(rs.getString("metadata")))
                        .build(),
                documentId, tenantId);
        return filterVisible(chunks, userId);
    }

    private List<Document> filterVisible(List<Document> documents, String userId) {
        return documents.stream()
                .filter(document -> DocumentVisibility.isVisibleTo(document.getMetadata(), userId))
                .toList();
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
