package com.eniglio.ragplatform.ingestion.service;

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
import java.util.List;
import java.util.UUID;

@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    private final DocumentReaderFactory documentReaderFactory;
    private final TokenTextSplitter tokenTextSplitter;
    private final VectorStoreGateway vectorStoreGateway;
    private final Counter documentsIngestedCounter;
    private final Counter chunksIngestedCounter;
    private final Timer ingestionTimer;

    public DocumentIngestionService(DocumentReaderFactory documentReaderFactory,
                                     TokenTextSplitter tokenTextSplitter,
                                     VectorStoreGateway vectorStoreGateway,
                                     MeterRegistry meterRegistry) {
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
        return ingestionTimer.record(() -> doIngest(file, tenantId, userId));
    }

    private IngestResponse doIngest(MultipartFile file, String tenantId, String userId) {
        List<Document> pages = documentReaderFactory.read(file);

        String documentId = UUID.randomUUID().toString();
        String source = file.getOriginalFilename();
        Instant ingestedAt = Instant.now();

        pages.forEach(page -> page.getMetadata().putAll(java.util.Map.of(
                "documentId", documentId,
                "source", source,
                "contentType", String.valueOf(file.getContentType()),
                "ingestedAt", ingestedAt.toString(),
                "tenantId", tenantId,
                "userId", userId
        )));

        List<Document> chunks = tokenTextSplitter.apply(pages);
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).getMetadata().put("chunkIndex", i);
        }

        vectorStoreGateway.add(chunks);

        documentsIngestedCounter.increment();
        chunksIngestedCounter.increment(chunks.size());

        log.info("Ingested document source={} documentId={} pages={} chunks={}",
                source, documentId, pages.size(), chunks.size());

        return new IngestResponse(documentId, source, pages.size(), chunks.size());
    }
}
