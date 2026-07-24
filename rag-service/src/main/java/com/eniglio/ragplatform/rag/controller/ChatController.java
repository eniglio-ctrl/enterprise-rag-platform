package com.eniglio.ragplatform.rag.controller;

import com.eniglio.ragplatform.rag.dto.ChatRequest;
import com.eniglio.ragplatform.rag.dto.ChatResponse;
import com.eniglio.ragplatform.rag.service.RagQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Chat", description = "Ask questions answered from the ingested knowledge base")
public class ChatController {

    private final RagQueryService ragQueryService;

    public ChatController(RagQueryService ragQueryService) {
        this.ragQueryService = ragQueryService;
    }

    @Operation(summary = "Ask a question", description = "Retrieves relevant chunks and generates a cited answer")
    @PostMapping("/api/v1/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return ragQueryService.answer(request.question());
    }
}
