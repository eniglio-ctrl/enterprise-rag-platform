package com.eniglio.ragplatform.rag.controller;

import com.eniglio.ragplatform.common.security.JwtClaims;
import com.eniglio.ragplatform.rag.dto.AskResponse;
import com.eniglio.ragplatform.rag.dto.ChatRequest;
import com.eniglio.ragplatform.rag.dto.ChatResponse;
import com.eniglio.ragplatform.rag.dto.DiagramResponse;
import com.eniglio.ragplatform.rag.dto.RetrieveResponse;
import com.eniglio.ragplatform.rag.service.ImageAttachmentValidator;
import com.eniglio.ragplatform.rag.service.RagQueryService;
import com.eniglio.ragplatform.rag.service.ValidatedImage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Validated
@Tag(name = "Chat", description = "Ask questions answered from the ingested knowledge base")
public class ChatController {

    private final RagQueryService ragQueryService;
    private final ImageAttachmentValidator imageAttachmentValidator;

    public ChatController(RagQueryService ragQueryService, ImageAttachmentValidator imageAttachmentValidator) {
        this.ragQueryService = ragQueryService;
        this.imageAttachmentValidator = imageAttachmentValidator;
    }

    @Operation(summary = "Ask anything",
            description = "Single entry point: answers in text, or generates an architecture diagram instead when the question itself asks for one (e.g. mentions \"diagram\", \"draw\", \"flow\")")
    @PostMapping(value = "/api/v1/ask", consumes = MediaType.APPLICATION_JSON_VALUE)
    public AskResponse ask(@Valid @RequestBody ChatRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ragQueryService.ask(request.question(), JwtClaims.tenantId(jwt), JwtClaims.userId(jwt),
                JwtClaims.departments(jwt), request.isGrounded(), request.isRerank(), request.model(),
                request.isUseFallback(), request.fallbackProvider());
    }

    @Operation(summary = "Ask anything, with an image attached",
            description = "Same as the JSON form of this endpoint, plus an optional image (PNG/JPEG/GIF/WebP) "
                    + "described by a vision model and folded into this single question's context — the image "
                    + "itself is never stored or indexed, only used to help answer this one request.")
    @ApiResponse(responseCode = "415", description = "Unsupported image content type")
    @ApiResponse(responseCode = "422", description = "Image content does not match its declared type")
    @PostMapping(value = "/api/v1/ask", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AskResponse askWithImage(
            @RequestParam("question") @NotBlank @Size(max = 8000, message = "question must be at most 8000 characters") String question,
            @RequestParam(value = "grounded", required = false) Boolean grounded,
            @RequestParam(value = "rerank", required = false) Boolean rerank,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "image", required = false) MultipartFile image,
            @AuthenticationPrincipal Jwt jwt) {
        ValidatedImage validatedImage = (image != null && !image.isEmpty())
                ? imageAttachmentValidator.validate(image)
                : null;
        byte[] imageBytes = validatedImage == null ? null : validatedImage.bytes();
        return ragQueryService.ask(question, JwtClaims.tenantId(jwt), JwtClaims.userId(jwt),
                JwtClaims.departments(jwt), Boolean.TRUE.equals(grounded), Boolean.TRUE.equals(rerank), model,
                imageBytes, validatedImage == null ? null : validatedImage.mimeType());
    }

    @Operation(summary = "Ask a question", description = "Retrieves relevant chunks and generates a cited answer")
    @PostMapping("/api/v1/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ragQueryService.answer(request.question(), JwtClaims.tenantId(jwt), JwtClaims.userId(jwt),
                JwtClaims.departments(jwt), request.isGrounded(), request.isRerank(), request.model(),
                request.isUseFallback(), request.fallbackProvider());
    }

    @Operation(summary = "Generate an architecture diagram from ingested data",
            description = "Retrieves relevant chunks and generates a Mermaid.js diagram describing the architecture/flow found in them")
    @PostMapping("/api/v1/diagrams")
    public DiagramResponse diagram(@Valid @RequestBody ChatRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ragQueryService.diagram(request.question(), JwtClaims.tenantId(jwt), JwtClaims.userId(jwt),
                JwtClaims.departments(jwt), request.model());
    }

    @Operation(summary = "Retrieve relevant chunks without generating an answer",
            description = "Used by chat-service to get citations for a question while it generates its own conversation-aware answer (ADR 0013)")
    @PostMapping("/api/v1/retrieve")
    public RetrieveResponse retrieve(@Valid @RequestBody ChatRequest request, @AuthenticationPrincipal Jwt jwt) {
        return new RetrieveResponse(
                ragQueryService.retrieve(request.question(), JwtClaims.tenantId(jwt), JwtClaims.userId(jwt),
                        JwtClaims.departments(jwt)));
    }
}
