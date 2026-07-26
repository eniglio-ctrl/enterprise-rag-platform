package com.eniglio.ragplatform.rag.controller;

import com.eniglio.ragplatform.rag.dto.AskResponse;
import com.eniglio.ragplatform.rag.dto.ChatRequest;
import com.eniglio.ragplatform.rag.dto.ChatResponse;
import com.eniglio.ragplatform.rag.dto.DiagramResponse;
import com.eniglio.ragplatform.rag.service.RagQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Chat", description = "Ask questions answered from the ingested knowledge base")
public class ChatController {

    private final RagQueryService ragQueryService;

    public ChatController(RagQueryService ragQueryService) {
        this.ragQueryService = ragQueryService;
    }

    @Operation(summary = "Ask anything",
            description = "Single entry point: answers in text, or generates an architecture diagram instead when the question itself asks for one (e.g. mentions \"diagram\", \"draw\", \"flow\")")
    @PostMapping("/api/v1/ask")
    public AskResponse ask(@Valid @RequestBody ChatRequest request,
            @RequestHeader(value = "X-Tenant-Id", required = false, defaultValue = "default") String tenantId) {
        return ragQueryService.ask(request.question(), tenantId);
    }

    @Operation(summary = "Ask a question", description = "Retrieves relevant chunks and generates a cited answer")
    @PostMapping("/api/v1/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request,
            @RequestHeader(value = "X-Tenant-Id", required = false, defaultValue = "default") String tenantId) {
        return ragQueryService.answer(request.question(), tenantId);
    }

    @Operation(summary = "Generate an architecture diagram from ingested data",
            description = "Retrieves relevant chunks and generates a Mermaid.js diagram describing the architecture/flow found in them")
    @PostMapping("/api/v1/diagrams")
    public DiagramResponse diagram(@Valid @RequestBody ChatRequest request,
            @RequestHeader(value = "X-Tenant-Id", required = false, defaultValue = "default") String tenantId) {
        return ragQueryService.diagram(request.question(), tenantId);
    }
}
