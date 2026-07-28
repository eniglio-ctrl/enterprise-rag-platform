package com.eniglio.ragplatform.ingestion.controller;

import com.eniglio.ragplatform.common.security.JwtClaims;
import com.eniglio.ragplatform.ingestion.dto.IngestResponse;
import com.eniglio.ragplatform.ingestion.service.DocumentIngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Tag(name = "Documents", description = "Upload and ingestion of source documents")
public class DocumentController {

    private final DocumentIngestionService documentIngestionService;

    public DocumentController(DocumentIngestionService documentIngestionService) {
        this.documentIngestionService = documentIngestionService;
    }

    @Operation(summary = "Upload a document for ingestion",
            description = "Parses, chunks, embeds and stores a PDF, DOCX, Markdown or plain-text file. "
                    + "PNG/JPEG/GIF/WebP images are described by a vision model instead (ADR 0018), and "
                    + "MP3/WAV/M4A/OGG/FLAC audio is transcribed by a local Whisper server (ADR 0019) — "
                    + "either way, the derived text is what gets embedded, never the original bytes.")
    @ApiResponse(responseCode = "201", description = "Document ingested successfully")
    @ApiResponse(responseCode = "415", description = "Unsupported file type")
    @PostMapping(value = "/api/v1/documents", consumes = "multipart/form-data")
    public ResponseEntity<IngestResponse> upload(
            @RequestParam("file") @NotNull MultipartFile file,
            @AuthenticationPrincipal Jwt jwt) {
        IngestResponse response = documentIngestionService.ingest(file, JwtClaims.tenantId(jwt), JwtClaims.userId(jwt));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
