package com.eniglio.ragplatform.rag.dto;

import java.util.List;

public record ChatResponse(String answer, List<Citation> citations) {
}
