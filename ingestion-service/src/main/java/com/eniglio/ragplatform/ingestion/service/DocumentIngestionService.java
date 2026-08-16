package com.eniglio.ragplatform.ingestion.service;

import com.eniglio.ragplatform.common.authorization.DocumentVersion;
import com.eniglio.ragplatform.common.authorization.DocumentVisibility;
import com.eniglio.ragplatform.ingestion.dto.IngestResponse;
import com.eniglio.ragplatform.ingestion.gateway.VectorStoreGateway;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    private final UploadValidationService uploadValidationService;
    private final UrlDocumentFetcher urlDocumentFetcher;
    private final DocumentReaderFactory documentReaderFactory;
    private final TokenTextSplitter tokenTextSplitter;
    private final VectorStoreGateway vectorStoreGateway;
    private final Counter documentsIngestedCounter;
    private final Counter chunksIngestedCounter;
    private final Timer ingestionTimer;

    public DocumentIngestionService(UploadValidationService uploadValidationService,
                                     UrlDocumentFetcher urlDocumentFetcher,
                                     DocumentReaderFactory documentReaderFactory,
                                     TokenTextSplitter tokenTextSplitter,
                                     VectorStoreGateway vectorStoreGateway,
                                     MeterRegistry meterRegistry) {
        this.uploadValidationService = uploadValidationService;
        this.urlDocumentFetcher = urlDocumentFetcher;
        this.documentReaderFactory = documentReaderFactory;
        this.tokenTextSplitter = tokenTextSplitter;
        this.vectorStoreGateway = vectorStoreGateway;
        this.documentsIngestedCounter = Counter.builder("rag.documents.ingested")
                .description("Number of documents successfully ingested")
                .register(meterRegistry);
        // Named "ingested", not "created" — Micrometer's Prometheus naming convention
        // silently strips a trailing ".created" from counter names (Prometheus/OpenMetrics
        // reserves the "_created" suffix for a different purpose), which would otherwise
        // collapse this into the confusing name "rag_chunks_total".
        this.chunksIngestedCounter = Counter.builder("rag.chunks.ingested")
                .description("Number of chunks created from ingested documents")
                .register(meterRegistry);
        this.ingestionTimer = Timer.builder("rag.ingestion.duration")
                .description("Time to ingest a document, from upload to chunks stored in the vector store")
                .register(meterRegistry);
    }

    public IngestResponse ingest(MultipartFile file, String tenantId, String userId) {
        return ingestionTimer.record(() -> {
            ValidatedUpload upload = uploadValidationService.validate(file);
            return doIngest(upload, file.getOriginalFilename(), tenantId, userId,
                    null, DocumentVisibility.TENANT, List.of());
        });
    }

    /**
     * docs/EXTERNAL-DATA-INTEGRATION-ROADMAP.md Phase 1. Same pipeline as {@link
     * #ingest(MultipartFile, String, String)} from validation onward — only how the
     * raw bytes/filename/content-type are obtained differs ({@link UrlDocumentFetcher}
     * instead of a {@code MultipartFile}).
     */
    public IngestResponse ingestFromUrl(String url, String tenantId, String userId) {
        return ingestionTimer.record(() -> {
            UrlDocumentFetcher.FetchedContent fetched = urlDocumentFetcher.fetch(url);
            ValidatedUpload upload = uploadValidationService.validate(
                    fetched.bytes(), fetched.filename(), fetched.contentType());
            return doIngest(upload, fetched.filename(), tenantId, userId,
                    null, DocumentVisibility.TENANT, List.of());
        });
    }

    /**
     * docs/adr/0058-document-versioning.md: the one entry point
     * {@link DocumentVersioningService} calls to ingest a new version's chunks —
     * same pipeline as a normal upload, except {@code documentGroupId}/{@code
     * visibility}/{@code sharedWith} are inherited from the document being
     * superseded instead of defaulting to "own group, TENANT-visible" the way a
     * brand new upload always does. {@code isLatestVersion} is deliberately never
     * written here: its absence already means "latest" ({@link
     * com.eniglio.ragplatform.common.authorization.DocumentVersion#isLatestVersion}),
     * so a newly ingested version needs no explicit flag - only the version being
     * superseded needs one, rewritten separately by {@link DocumentVersioningService}.
     */
    public IngestResponse ingestNewVersion(MultipartFile file, String tenantId, String userId,
                                            String documentGroupId, String visibility, List<String> sharedWith) {
        return ingestionTimer.record(() -> {
            ValidatedUpload upload = uploadValidationService.validate(file);
            return doIngest(upload, file.getOriginalFilename(), tenantId, userId,
                    documentGroupId, visibility, sharedWith);
        });
    }

    private IngestResponse doIngest(ValidatedUpload upload, String source, String tenantId, String userId,
                                     String documentGroupId, String visibility, List<String> sharedWith) {
        List<Document> pages = documentReaderFactory.read(upload);

        String documentId = UUID.randomUUID().toString();
        Instant ingestedAt = Instant.now();

        // docs/ROADMAP.md item #24: every document starts out TENANT-visible by
        // default (the original, unchanged authorization model, ADR 0007) - a fresh
        // upload never overrides this; only a new version explicitly inherits its
        // predecessor's own visibility instead (docs/adr/0058-document-versioning.md).
        Map<String, Object> metadata = new HashMap<>(Map.of(
                "documentId", documentId,
                "source", source,
                "contentType", upload.mimeType().toString(),
                "ingestedAt", ingestedAt.toString(),
                "tenantId", tenantId,
                "userId", userId,
                DocumentVisibility.VISIBILITY_KEY, visibility
        ));
        // documentGroupId is deliberately left unwritten for a brand new,
        // never-superseded document - its own documentId already IS its group,
        // matching the "absent key = implicit default" convention every other
        // versioning/visibility metadata key in this codebase already follows.
        if (documentGroupId != null) {
            metadata.put(DocumentVersion.DOCUMENT_GROUP_ID_KEY, documentGroupId);
        }
        if (!sharedWith.isEmpty()) {
            metadata.put(DocumentVisibility.SHARED_WITH_KEY, sharedWith);
        }
        pages.forEach(page -> page.getMetadata().putAll(metadata));

        List<Document> chunks = tokenTextSplitter.apply(pages);
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).getMetadata().put("chunkIndex", i);
        }

        vectorStoreGateway.add(chunks);

        documentsIngestedCounter.increment();
        chunksIngestedCounter.increment(chunks.size());

        log.info("Ingested document source={} documentId={} pages={} chunks={}",
                source, documentId, pages.size(), chunks.size());

        // version is always 1 from this method's own point of view - it has no idea
        // how many prior versions exist in the group. DocumentVersioningService
        // overwrites this field with the real count on the ingestNewVersion path;
        // for a normal, never-versioned upload, 1 is already correct.
        return new IngestResponse(documentId, source, pages.size(), chunks.size(),
                documentGroupId == null ? documentId : documentGroupId, 1);
    }
}
