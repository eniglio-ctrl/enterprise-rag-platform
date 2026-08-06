package com.eniglio.ragplatform.ingestion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "ingestion")
public record IngestionProperties(int chunkSizeTokens, List<String> allowedContentTypes, Docx docx,
                                   UrlImport urlImport) {

    /**
     * ADR 0022's "known gap" fix: {@code maxEntryCount}/{@code maxUncompressedBytes}
     * bound a `.docx` upload's real ZIP structure, enforced by {@link
     * com.eniglio.ragplatform.ingestion.service.UploadValidationService} while
     * actually reading each entry — not by trusting the archive's own (attacker-
     * controlled) size headers, which is what makes this a real zip-bomb defense
     * rather than a check an attacker could simply lie past.
     */
    public record Docx(int maxEntryCount, long maxUncompressedBytes) {
    }

    /**
     * {@code maxBytes} is enforced by {@link
     * com.eniglio.ragplatform.ingestion.service.UrlDocumentFetcher} while actually
     * reading the response body, not by trusting the remote server's own
     * {@code Content-Length} header — same "don't trust attacker-controlled size
     * claims" principle as {@link Docx} above, just against a different kind of lie.
     */
    public record UrlImport(long maxBytes, Duration timeout) {
    }
}
