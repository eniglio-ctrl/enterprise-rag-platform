package com.eniglio.ragplatform.ingestion.service;

import com.eniglio.ragplatform.ingestion.config.IngestionProperties;

import java.net.InetAddress;

/**
 * Deliberately in the same package as {@link UrlDocumentFetcher} (physically under
 * {@code src/test/java}) so it can reach the package-private test constructor from a
 * test class in a different package, e.g. {@code DocumentIngestionIT}. The SSRF guard
 * itself is never weakened in production - see {@link UrlDocumentFetcher}'s javadoc.
 */
public final class TestUrlDocumentFetchers {

    private TestUrlDocumentFetchers() {
    }

    /**
     * A fetcher whose SSRF check believes every hostname resolves to a fake public
     * address (8.8.8.8), so a local test {@code HttpServer} on {@code 127.0.0.1} isn't
     * rejected by the loopback guard - the actual TCP connection still goes for real to
     * whatever host/port the test URL names.
     */
    public static UrlDocumentFetcher bypassingSsrfGuardForTests(IngestionProperties properties) {
        return new UrlDocumentFetcher(properties,
                host -> new InetAddress[]{InetAddress.getByAddress(host, new byte[]{8, 8, 8, 8})});
    }
}
