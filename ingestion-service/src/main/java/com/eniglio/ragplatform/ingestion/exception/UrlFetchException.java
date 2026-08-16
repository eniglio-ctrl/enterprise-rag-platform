package com.eniglio.ragplatform.ingestion.exception;

/**
 * Thrown by {@link com.eniglio.ragplatform.ingestion.service.UrlDocumentFetcher}
 * for every way a URL-based import can be refused or fail: an unsupported scheme,
 * a host resolving to a private/internal address (SSRF guard), a redirect response
 * (never followed automatically), a response exceeding the configured size limit,
 * a non-2xx status, or a connection/read timeout. One exception type, one HTTP
 * status (400) — the message carries which specific case it was, since none of
 * these need a different status from each other the way, say, a missing resource
 * (404) would.
 */
public class UrlFetchException extends RuntimeException {

    public UrlFetchException(String message) {
        super(message);
    }

    public UrlFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
