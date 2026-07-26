package com.eniglio.ragplatform.ingestion.service;

import com.eniglio.ragplatform.ingestion.dto.IngestResponse;
import com.eniglio.ragplatform.ingestion.gateway.VectorStoreGateway;
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

    public DocumentIngestionService(DocumentReaderFactory documentReaderFactory,
                                     TokenTextSplitter tokenTextSplitter,
                                     VectorStoreGateway vectorStoreGateway) {
        this.documentReaderFactory = documentReaderFactory;
        this.tokenTextSplitter = tokenTextSplitter;
        this.vectorStoreGateway = vectorStoreGateway;
    }

    public IngestResponse ingest(MultipartFile file, String tenantId, String userId) {
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

        log.info("Ingested document source={} documentId={} pages={} chunks={}",
                source, documentId, pages.size(), chunks.size());

        return new IngestResponse(documentId, source, pages.size(), chunks.size());
    }
}
