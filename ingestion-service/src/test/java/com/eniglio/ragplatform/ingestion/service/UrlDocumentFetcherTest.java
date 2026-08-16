package com.eniglio.ragplatform.ingestion.service;

import com.eniglio.ragplatform.ingestion.config.IngestionProperties;
import com.eniglio.ragplatform.ingestion.exception.UrlFetchException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Uses a real, local {@link HttpServer} (JDK built-in, no new test dependency) so every
 * case here exercises an actual socket round trip, not a mocked one. The single-arg
 * constructor (real DNS resolution) proves the SSRF guard genuinely blocks
 * {@code 127.0.0.1}. The remaining cases need a real connection to *succeed* against the
 * local test server, which a strict loopback-blocking guard would otherwise also reject
 * - those use the package-private constructor to stub only the "what IP does this host
 * resolve to" check with a fake public address, while the TCP connection itself still
 * goes for real to the local server.
 */
class UrlDocumentFetcherTest {

    private static final IngestionProperties.UrlImport LIMITS =
            new IngestionProperties.UrlImport(1024, Duration.ofSeconds(5));
    private static final IngestionProperties PROPERTIES =
            new IngestionProperties(800, java.util.List.of(), null, LIMITS, new IngestionProperties.PdfOcr(20, 20));

    private final UrlDocumentFetcher fetcher = new UrlDocumentFetcher(PROPERTIES);

    private final UrlDocumentFetcher fetcherWithGuardBypassedForLocalTesting =
            new UrlDocumentFetcher(PROPERTIES, host -> new InetAddress[]{
                    InetAddress.getByAddress(host, new byte[]{8, 8, 8, 8})
            });

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fetchesRealBytesFromALocalServer() throws IOException {
        String body = "# Notes\n\nSome real content served over a real socket.";
        server = startServer("/notes.md", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/markdown; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        UrlDocumentFetcher.FetchedContent fetched = fetcherWithGuardBypassedForLocalTesting.fetch(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/notes.md");

        assertThat(new String(fetched.bytes(), StandardCharsets.UTF_8)).isEqualTo(body);
        assertThat(fetched.filename()).isEqualTo("notes.md");
        assertThat(fetched.contentType()).isEqualTo("text/markdown");
    }

    @Test
    void refusesToFetchFromLoopbackEvenWithoutALocalServerRunning() {
        assertThatThrownBy(() -> fetcher.fetch("http://127.0.0.1:9/blocked"))
                .isInstanceOf(UrlFetchException.class)
                .hasMessageContaining("private/internal address");
    }

    @Test
    void rejectsAFileScheme() {
        assertThatThrownBy(() -> fetcher.fetch("file:///etc/passwd"))
                .isInstanceOf(UrlFetchException.class)
                .hasMessageContaining("Unsupported URL scheme");
    }

    @Test
    void rejectsARedirectInsteadOfFollowingItAutomatically() throws IOException {
        server = startServer("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1/somewhere-else");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        assertThatThrownBy(() -> fetcherWithGuardBypassedForLocalTesting.fetch(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect"))
                .isInstanceOf(UrlFetchException.class)
                .hasMessageContaining("redirect");
    }

    @Test
    void rejectsAResponseLargerThanTheConfiguredLimit() throws IOException {
        byte[] tooLarge = new byte[(int) LIMITS.maxBytes() + 1];
        server = startServer("/big.txt", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, tooLarge.length);
            exchange.getResponseBody().write(tooLarge);
            exchange.close();
        });

        assertThatThrownBy(() -> fetcherWithGuardBypassedForLocalTesting.fetch(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/big.txt"))
                .isInstanceOf(UrlFetchException.class)
                .hasMessageContaining("exceeds the maximum allowed size");
    }

    private static HttpServer startServer(String path, com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, handler);
        server.start();
        return server;
    }
}
