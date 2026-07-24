package com.eniglio.ragplatform.ingestion.dto;

public record IngestResponse(
        String documentId,
        String source,
        int pageCount,
        int chunkCount
) {
}
