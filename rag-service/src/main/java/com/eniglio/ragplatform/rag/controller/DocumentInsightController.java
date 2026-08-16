package com.eniglio.ragplatform.rag.controller;

import com.eniglio.ragplatform.common.security.JwtClaims;
import com.eniglio.ragplatform.rag.dto.ComparisonRequest;
import com.eniglio.ragplatform.rag.dto.ComparisonResponse;
import com.eniglio.ragplatform.rag.dto.FaqResponse;
import com.eniglio.ragplatform.rag.dto.SummaryResponse;
import com.eniglio.ragplatform.rag.service.RagQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/PRODUCT-DIFFERENTIATION-ROADMAP.md Phase 8. Separate from {@link
 * ChatController} on purpose: those endpoints are all question-driven
 * ({@code ChatRequest.question}), while these two are document-driven — no question,
 * just "summarize/generate FAQ for this one document," a different resource shape.
 */
@RestController
@Tag(name = "Document Insights", description = "Per-document summaries and FAQs, generated from the document's entire indexed content")
public class DocumentInsightController {

    private final RagQueryService ragQueryService;

    public DocumentInsightController(RagQueryService ragQueryService) {
        this.ragQueryService = ragQueryService;
    }

    @Operation(summary = "Summarize a document",
            description = "Generates a short summary from the document's entire indexed content (not a top-K similarity search)")
    @ApiResponse(responseCode = "404", description = "No document with this id is visible to the caller")
    @PostMapping("/api/v1/documents/{documentId}/summarize")
    public SummaryResponse summarize(@PathVariable("documentId") String documentId,
            @RequestParam(value = "model", required = false) String model, @AuthenticationPrincipal Jwt jwt) {
        return ragQueryService.summarizeDocument(documentId, JwtClaims.tenantId(jwt), JwtClaims.userId(jwt),
                JwtClaims.departments(jwt), model);
    }

    @Operation(summary = "Generate a FAQ for a document",
            description = "Generates a list of question/answer pairs from the document's entire indexed content (not a top-K similarity search)")
    @ApiResponse(responseCode = "404", description = "No document with this id is visible to the caller")
    @ApiResponse(responseCode = "500", description = "The model's response could not be parsed into a valid FAQ")
    @PostMapping("/api/v1/documents/{documentId}/faq")
    public FaqResponse faq(@PathVariable("documentId") String documentId,
            @RequestParam(value = "model", required = false) String model, @AuthenticationPrincipal Jwt jwt) {
        return ragQueryService.generateFaq(documentId, JwtClaims.tenantId(jwt), JwtClaims.userId(jwt),
                JwtClaims.departments(jwt), model);
    }

    @Operation(summary = "Compare two or more documents",
            description = "Generates a structured comparison (agreements, contradictions, unique points) from each "
                    + "document's entire indexed content (not a top-K similarity search)")
    @ApiResponse(responseCode = "400", description = "Fewer than 2 documentIds, or more than rag.document-comparison.max-documents")
    @ApiResponse(responseCode = "404", description = "One or more documentIds is not visible to the caller")
    @PostMapping("/api/v1/documents/compare")
    public ComparisonResponse compare(@Valid @RequestBody ComparisonRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ragQueryService.compareDocuments(request.documentIds(), JwtClaims.tenantId(jwt),
                JwtClaims.userId(jwt), JwtClaims.departments(jwt), request.model());
    }
}
