package com.eniglio.ragplatform.rag.dto;

import com.eniglio.ragplatform.common.web.RetrievedChunk;

import java.util.List;

public record RetrieveResponse(List<RetrievedChunk> chunks) {
}
