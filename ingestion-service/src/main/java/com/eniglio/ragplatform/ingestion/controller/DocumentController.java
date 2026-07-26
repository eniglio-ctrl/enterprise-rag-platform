package com.eniglio.ragplatform.ingestion.controller;

import com.eniglio.ragplatform.ingestion.dto.IngestResponse;
import com.eniglio.ragplatform.ingestion.service.DocumentIngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
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
            description = "Parses, chunks, embeds and stores a PDF, DOCX, Markdown or plain-text file")
    @ApiResponse(responseCode = "201", description = "Document ingested successfully")
    @ApiResponse(responseCode = "415", description = "Unsupported file type")
    @PostMapping(value = "/api/v1/documents", consumes = "multipart/form-data")
    public ResponseEntity<IngestResponse> upload(
            @RequestParam("file") @NotNull MultipartFile file,
            @RequestHeader(value = "X-Tenant-Id", required = false, defaultValue = "default") String tenantId,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "default") String userId) {
        IngestResponse response = documentIngestionService.ingest(file, tenantId, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
