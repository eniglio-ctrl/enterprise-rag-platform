package com.eniglio.ragplatform.ingestion.service;

import com.eniglio.ragplatform.common.authorization.DocumentVisibility;
import com.eniglio.ragplatform.common.security.Role;
import com.eniglio.ragplatform.ingestion.dto.DocumentSummary;
import com.eniglio.ragplatform.ingestion.dto.SharingResponse;
import com.eniglio.ragplatform.ingestion.dto.UpdateSharingRequest;
import com.eniglio.ragplatform.ingestion.exception.DocumentNotFoundException;
import com.eniglio.ragplatform.ingestion.exception.InvalidSharingRequestException;
import com.eniglio.ragplatform.ingestion.exception.NotDocumentOwnerException;
import com.eniglio.ragplatform.ingestion.exception.NotTenantAdminException;
import com.eniglio.ragplatform.ingestion.repository.DocumentSharingRepository;
import com.eniglio.ragplatform.ingestion.repository.DocumentSharingRepository.ChunkRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * docs/ROADMAP.md item #24: the one write path for the ABAC model
 * {@link DocumentVisibility} defines. Deliberately a separate action from upload
 * (every document starts {@code TENANT}-visible, {@code DocumentIngestionService}) —
 * only the document's own owner, or a tenant ADMIN (ADR 0047), may narrow it
 * afterward, checked here against every chunk's shared {@code "userId"} metadata key,
 * not against the caller's tenant alone (tenant membership already lets you upload
 * here; it doesn't make you the owner of someone else's document).
 */
@Service
public class DocumentSharingService {

    private static final Logger log = LoggerFactory.getLogger(DocumentSharingService.class);

    private final DocumentSharingRepository repository;

    public DocumentSharingService(DocumentSharingRepository repository) {
        this.repository = repository;
    }

    public SharingResponse updateSharing(String documentId, String tenantId, String callerUserId, Role callerRole,
                                          UpdateSharingRequest request) {
        List<ChunkRow> chunks = repository.findChunks(documentId, tenantId);
        if (chunks.isEmpty()) {
            throw new DocumentNotFoundException(documentId);
        }

        Object ownerId = chunks.get(0).metadata().get(DocumentVisibility.OWNER_KEY);
        // ADR 0047: a tenant ADMIN may change the sharing of any document in their own
        // tenant, not just ones they own themselves - the one bypass to the ownership
        // check this service otherwise enforces. findChunks already scoped the lookup
        // to the caller's own tenantId, so this never crosses a tenant boundary.
        if (!callerUserId.equals(ownerId) && callerRole != Role.ADMIN) {
            throw new NotDocumentOwnerException(documentId, "change its sharing settings");
        }

        String visibility = normalizeVisibility(request.visibility());
        List<String> sharedWith = request.sharedWith() == null ? List.of() : List.copyOf(request.sharedWith());
        List<String> sharedDepartments = request.sharedDepartments() == null
                ? List.of() : List.copyOf(request.sharedDepartments());

        for (ChunkRow chunk : chunks) {
            Map<String, Object> metadata = new HashMap<>(chunk.metadata());
            metadata.put(DocumentVisibility.VISIBILITY_KEY, visibility);
            metadata.put(DocumentVisibility.SHARED_WITH_KEY, sharedWith);
            metadata.put(DocumentVisibility.SHARED_WITH_DEPARTMENTS_KEY, sharedDepartments);
            repository.updateMetadata(chunk.id(), metadata);
        }

        log.info("Updated sharing for document {}: visibility={} sharedWithCount={} sharedDepartmentsCount={}",
                documentId, visibility, sharedWith.size(), sharedDepartments.size());
        return new SharingResponse(documentId, visibility, sharedWith, sharedDepartments);
    }

    /** ADR 0047: admin-only - lists every document in the caller's own tenant. */
    public List<DocumentSummary> listDocuments(String tenantId, Role callerRole) {
        if (callerRole != Role.ADMIN) {
            throw new NotTenantAdminException();
        }
        return repository.findDocuments(tenantId);
    }

    private String normalizeVisibility(String raw) {
        String upper = raw == null ? "" : raw.toUpperCase(Locale.ROOT);
        if (!DocumentVisibility.TENANT.equals(upper) && !DocumentVisibility.RESTRICTED.equals(upper)) {
            throw new InvalidSharingRequestException(
                    "visibility must be \"" + DocumentVisibility.TENANT + "\" or \""
                            + DocumentVisibility.RESTRICTED + "\", got: " + raw);
        }
        return upper;
    }
}
