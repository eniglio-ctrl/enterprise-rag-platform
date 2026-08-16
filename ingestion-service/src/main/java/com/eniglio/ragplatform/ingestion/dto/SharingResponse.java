package com.eniglio.ragplatform.ingestion.dto;

import java.util.List;

public record SharingResponse(String documentId, String visibility, List<String> sharedWith,
        List<String> sharedDepartments) {
}
