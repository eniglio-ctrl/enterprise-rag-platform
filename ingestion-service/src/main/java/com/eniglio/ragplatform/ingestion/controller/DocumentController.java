package com.eniglio.ragplatform.ingestion.controller;

import com.eniglio.ragplatform.common.security.JwtClaims;
import com.eniglio.ragplatform.ingestion.dto.DocumentSummary;
import com.eniglio.ragplatform.ingestion.dto.IngestResponse;
import com.eniglio.ragplatform.ingestion.dto.SharingResponse;
import com.eniglio.ragplatform.ingestion.dto.UpdateSharingRequest;
import com.eniglio.ragplatform.ingestion.service.DocumentIngestionService;
import com.eniglio.ragplatform.ingestion.service.DocumentSharingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@Tag(name = "Documents", description = "Upload and ingestion of source documents")
public class DocumentController {

    private final DocumentIngestionService documentIngestionService;
    private final DocumentSharingService documentSharingService;

    public DocumentController(DocumentIngestionService documentIngestionService,
                               DocumentSharingService documentSharingService) {
        this.documentIngestionService = documentIngestionService;
        this.documentSharingService = documentSharingService;
    }

    @Operation(summary = "Upload a document for ingestion",
            description = "Parses, chunks, embeds and stores a PDF, DOCX, Markdown or plain-text file. "
                    + "PNG/JPEG/GIF/WebP images are described by a vision model instead (ADR 0018), and "
                    + "MP3/WAV/M4A/OGG/FLAC audio is transcribed by a local Whisper server (ADR 0019) — "
                    + "either way, the derived text is what gets embedded, never the original bytes.")
    @ApiResponse(responseCode = "201", description = "Document ingested successfully")
    @ApiResponse(responseCode = "415", description = "Unsupported file extension or declared content type")
    @ApiResponse(responseCode = "422", description = "File content does not match its declared type")
    @PostMapping(value = "/api/v1/documents", consumes = "multipart/form-data")
    public ResponseEntity<IngestResponse> upload(
            @RequestParam("file") @NotNull MultipartFile file,
            @AuthenticationPrincipal Jwt jwt) {
        IngestResponse response = documentIngestionService.ingest(file, JwtClaims.tenantId(jwt), JwtClaims.userId(jwt));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Change a document's sharing settings",
            description = "docs/ROADMAP.md item #24: restricts a document (originally TENANT-visible to "
                    + "everyone in the tenant, ADR 0007) to just its owner plus a specific list of user IDs, "
                    + "or reopens it back to TENANT visibility. Only the document's own owner may call this.")
    @ApiResponse(responseCode = "200", description = "Sharing updated")
    @ApiResponse(responseCode = "400", description = "visibility is neither TENANT nor RESTRICTED")
    @ApiResponse(responseCode = "403", description = "Caller is not the document's owner")
    @ApiResponse(responseCode = "404", description = "No document with this ID exists in the caller's tenant")
    @PatchMapping("/api/v1/documents/{documentId}/sharing")
    public SharingResponse updateSharing(
            @PathVariable("documentId") String documentId,
            @Valid @RequestBody UpdateSharingRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return documentSharingService.updateSharing(documentId, JwtClaims.tenantId(jwt), JwtClaims.userId(jwt),
                JwtClaims.role(jwt), request);
    }

    @Operation(summary = "List every document in the caller's tenant",
            description = "ADR 0047: admin-only, powers the permission-management screen - shows the owner, "
                    + "visibility and sharedWith of every document in the tenant, not just the caller's own.")
    @ApiResponse(responseCode = "200", description = "Tenant documents")
    @ApiResponse(responseCode = "403", description = "Caller is not a tenant admin")
    @GetMapping("/api/v1/documents")
    public List<DocumentSummary> listDocuments(@AuthenticationPrincipal Jwt jwt) {
        return documentSharingService.listDocuments(JwtClaims.tenantId(jwt), JwtClaims.role(jwt));
    }
}
