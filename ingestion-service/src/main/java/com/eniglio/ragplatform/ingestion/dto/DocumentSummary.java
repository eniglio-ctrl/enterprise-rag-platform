package com.eniglio.ragplatform.ingestion.dto;

import java.util.List;

public record DocumentSummary(String documentId, String source, String ownerId, String visibility,
        List<String> sharedWith) {
}
