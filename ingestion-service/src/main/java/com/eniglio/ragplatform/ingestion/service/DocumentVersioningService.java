package com.eniglio.ragplatform.ingestion.service;

import com.eniglio.ragplatform.common.authorization.DocumentVersion;
import com.eniglio.ragplatform.common.authorization.DocumentVisibility;
import com.eniglio.ragplatform.common.security.Role;
import com.eniglio.ragplatform.ingestion.dto.IngestResponse;
import com.eniglio.ragplatform.ingestion.exception.DocumentNotFoundException;
import com.eniglio.ragplatform.ingestion.exception.NotDocumentOwnerException;
import com.eniglio.ragplatform.ingestion.exception.NotLatestVersionException;
import com.eniglio.ragplatform.ingestion.repository.DocumentSharingRepository;
import com.eniglio.ragplatform.ingestion.repository.DocumentSharingRepository.ChunkRow;
import com.eniglio.ragplatform.ingestion.repository.DocumentVersioningRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * docs/adr/0058-document-versioning.md. Uploads a new version of an existing
 * document: reuses {@link DocumentSharingRepository} directly for the "read/rewrite
 * every chunk of a documentId" operations both this and sharing need, and {@link
 * DocumentIngestionService#ingestNewVersion} for the actual parse/chunk/embed
 * pipeline — this class only adds the version-specific rules around those two.
 */
@Service
public class DocumentVersioningService {

    private static final Logger log = LoggerFactory.getLogger(DocumentVersioningService.class);

    private final DocumentSharingRepository sharingRepository;
    private final DocumentVersioningRepository versioningRepository;
    private final DocumentIngestionService documentIngestionService;

    public DocumentVersioningService(DocumentSharingRepository sharingRepository,
                                      DocumentVersioningRepository versioningRepository,
                                      DocumentIngestionService documentIngestionService) {
        this.sharingRepository = sharingRepository;
        this.versioningRepository = versioningRepository;
        this.documentIngestionService = documentIngestionService;
    }

    public IngestResponse ingestNewVersion(MultipartFile file, String supersedesDocumentId, String tenantId,
                                            String callerUserId, Role callerRole) {
        List<ChunkRow> previousChunks = sharingRepository.findChunks(supersedesDocumentId, tenantId);
        if (previousChunks.isEmpty()) {
            throw new DocumentNotFoundException(supersedesDocumentId);
        }

        Map<String, Object> previousMetadata = previousChunks.get(0).metadata();
        Object ownerId = previousMetadata.get(DocumentVisibility.OWNER_KEY);
        // Same ownership rule as DocumentSharingService.updateSharing - superseding a
        // document is just as significant an action on its identity as changing its
        // sharing, so it gets the same "owner or tenant ADMIN" bar, not the lower bar
        // a fresh upload has (any tenant member may upload a brand new document).
        if (!callerUserId.equals(ownerId) && callerRole != Role.ADMIN) {
            throw new NotDocumentOwnerException(supersedesDocumentId, "upload a new version of it");
        }
        if (!DocumentVersion.isLatestVersion(previousMetadata)) {
            throw new NotLatestVersionException(supersedesDocumentId);
        }

        String documentGroupId = stringOrDefault(
                previousMetadata.get(DocumentVersion.DOCUMENT_GROUP_ID_KEY), supersedesDocumentId);
        String visibility = stringOrDefault(
                previousMetadata.get(DocumentVisibility.VISIBILITY_KEY), DocumentVisibility.TENANT);
        List<String> sharedWith = stringListOrEmpty(previousMetadata.get(DocumentVisibility.SHARED_WITH_KEY));

        IngestResponse ingested = documentIngestionService.ingestNewVersion(
                file, tenantId, callerUserId, documentGroupId, visibility, sharedWith);

        for (ChunkRow chunk : previousChunks) {
            Map<String, Object> metadata = new HashMap<>(chunk.metadata());
            metadata.put(DocumentVersion.IS_LATEST_VERSION_KEY, false);
            sharingRepository.updateMetadata(chunk.id(), metadata);
        }

        int version = versioningRepository.countVersionsInGroup(documentGroupId, tenantId);
        log.info("Superseded document {} with new version {} (documentGroupId={}, version={})",
                supersedesDocumentId, ingested.documentId(), documentGroupId, version);

        return new IngestResponse(ingested.documentId(), ingested.source(), ingested.pageCount(),
                ingested.chunkCount(), documentGroupId, version);
    }

    private static String stringOrDefault(Object value, String defaultValue) {
        return value instanceof String s ? s : defaultValue;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringListOrEmpty(Object value) {
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }
}
