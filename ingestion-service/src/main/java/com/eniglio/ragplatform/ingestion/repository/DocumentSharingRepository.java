package com.eniglio.ragplatform.ingestion.repository;

import com.eniglio.ragplatform.common.authorization.DocumentVisibility;
import com.eniglio.ragplatform.ingestion.dto.DocumentSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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

    // DISTINCT ON (documentId) picks one representative row per document - every chunk
    // of a document already carries identical visibility/sharedWith (rewritten in
    // lockstep by updateMetadata above), so any one of them is a correct summary. `id`
    // as the tiebreaker ORDER BY key (ADR 0047) makes that choice deterministic across
    // runs instead of leaning on that invariant silently.
    // docs/adr/0058-document-versioning.md: document_group_id defaults to the
    // document's own id (COALESCE) - same "absent key = its own group" convention
    // DocumentIngestionService's write side already follows. is_latest_version mirrors
    // DocumentVersion.isLatestVersion's own logic exactly. ingested_at is only used to
    // compute each document's 1-based `version` number within its group in Java below
    // - it's not exposed on DocumentSummary itself.
    // docs/adr/0059-department-based-sharing.md: shared_departments read the same way
    // shared_with already is - a JSON array (or absent) inside the same metadata blob,
    // no schema change needed here.
    private static final String SELECT_DOCUMENTS_SQL = """
            SELECT DISTINCT ON (metadata->>'documentId')
                   metadata->>'documentId'      AS document_id,
                   metadata->>'source'          AS source,
                   metadata->>'userId'          AS owner_id,
                   metadata->>'visibility'      AS visibility,
                   metadata->'sharedWith'       AS shared_with,
                   metadata->'sharedWithDepartments' AS shared_departments,
                   COALESCE(metadata->>'documentGroupId', metadata->>'documentId') AS document_group_id,
                   CASE WHEN metadata->>'isLatestVersion' = 'false' THEN false ELSE true END AS is_latest_version,
                   metadata->>'ingestedAt'      AS ingested_at
            FROM vector_store
            WHERE tenant_id = ?
            ORDER BY metadata->>'documentId', id
            """;

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

    /**
     * ADR 0047: powers the admin-only document listing. {@code visibility} defaults to
     * {@link DocumentVisibility#TENANT} when the column comes back null - documents
     * ingested before ADR 0046 have no {@code "visibility"} key at all, and this must
     * report the same default {@link DocumentVisibility#isVisibleTo} already applies.
     * docs/adr/0058-document-versioning.md: each document's {@code version} number is
     * computed here, not in SQL - it's 1-based rank by {@code ingestedAt} within the
     * document's own group, and a window function over a JSON-derived, non-indexed
     * timestamp column read as text would be far less readable than grouping the
     * already-fetched rows in Java, and this table is small enough per tenant that it
     * doesn't matter.
     */
    public List<DocumentSummary> findDocuments(String tenantId) {
        List<DocumentRow> rows = jdbcTemplate.query(SELECT_DOCUMENTS_SQL,
                (rs, rowNum) -> new DocumentRow(
                        rs.getString("document_id"),
                        rs.getString("source"),
                        rs.getString("owner_id"),
                        Optional.ofNullable(rs.getString("visibility")).orElse(DocumentVisibility.TENANT),
                        parseStringList(rs.getString("shared_with")),
                        parseStringList(rs.getString("shared_departments")),
                        rs.getString("document_group_id"),
                        rs.getBoolean("is_latest_version"),
                        rs.getString("ingested_at")),
                tenantId);

        Map<String, List<DocumentRow>> byGroup = rows.stream()
                .collect(Collectors.groupingBy(DocumentRow::documentGroupId));
        Map<String, Integer> versionByDocumentId = new HashMap<>();
        for (List<DocumentRow> group : byGroup.values()) {
            List<DocumentRow> ordered = group.stream()
                    .sorted(Comparator.comparing(DocumentRow::ingestedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                    .toList();
            for (int i = 0; i < ordered.size(); i++) {
                versionByDocumentId.put(ordered.get(i).documentId(), i + 1);
            }
        }

        return rows.stream()
                .map(row -> new DocumentSummary(row.documentId(), row.source(), row.ownerId(), row.visibility(),
                        row.sharedWith(), row.documentGroupId(), versionByDocumentId.get(row.documentId()),
                        row.isLatestVersion(), row.sharedDepartments()))
                .toList();
    }

    // docs/adr/0059-department-based-sharing.md: reused for both shared_with and
    // shared_departments - both are a JSON array of strings (or absent) inside the
    // same metadata blob, parsed identically regardless of which key they came from.
    private List<String> parseStringList(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse a vector_store row's own JSON string list", e);
        }
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

    private record DocumentRow(String documentId, String source, String ownerId, String visibility,
            List<String> sharedWith, List<String> sharedDepartments, String documentGroupId,
            boolean isLatestVersion, String ingestedAt) {
    }
}
