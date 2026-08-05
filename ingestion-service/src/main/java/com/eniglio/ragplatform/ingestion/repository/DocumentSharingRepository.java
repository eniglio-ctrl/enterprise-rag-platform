package com.eniglio.ragplatform.ingestion.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * docs/ROADMAP.md item #24: {@code vector_store} has one row per chunk, not one row
 * per document — changing a document's sharing settings means rewriting every chunk
 * row's {@code metadata} that shares its {@code documentId}, not a single-row update.
 * A real, uploaded document's chunk count is small (low tens at most, confirmed by
 * this project's own ingestion pipeline), so one {@code UPDATE} per chunk in a plain
 * loop (see {@code DocumentSharingService}) is simple and fast enough — no batching
 * infrastructure was worth adding for this scale.
 */
@Repository
public class DocumentSharingRepository {

    private static final String SELECT_CHUNKS_SQL = """
            SELECT id, metadata
            FROM vector_store
            WHERE metadata->>'documentId' = ?
              AND tenant_id = ?
            """;

    // metadata is `json`, not `jsonb` (V1 migration) - the explicit ::json cast is
    // required for Postgres to accept a plain string parameter as this column's type.
    // id is `uuid`, not text (V1 migration) - Postgres refuses to compare
    // "uuid = character varying" without an explicit ::uuid cast either, confirmed by
    // a real "operator does not exist" failure against the real Testcontainers
    // Postgres before adding it, not assumed.
    private static final String UPDATE_METADATA_SQL = "UPDATE vector_store SET metadata = ?::json WHERE id = ?::uuid";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DocumentSharingRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<ChunkRow> findChunks(String documentId, String tenantId) {
        return jdbcTemplate.query(SELECT_CHUNKS_SQL,
                (rs, rowNum) -> new ChunkRow(rs.getString("id"), parseMetadata(rs.getString("metadata"))),
                documentId, tenantId);
    }

    public void updateMetadata(String chunkId, Map<String, Object> metadata) {
        jdbcTemplate.update(UPDATE_METADATA_SQL, writeMetadata(metadata), chunkId);
    }

    private Map<String, Object> parseMetadata(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse a vector_store row's own metadata JSON", e);
        }
    }

    private String writeMetadata(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize updated metadata back to JSON", e);
        }
    }

    public record ChunkRow(String id, Map<String, Object> metadata) {
    }
}
