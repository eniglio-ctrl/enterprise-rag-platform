package com.eniglio.ragplatform.ingestion.service;

import com.eniglio.ragplatform.ingestion.config.IngestionProperties;
import com.eniglio.ragplatform.ingestion.exception.UrlFetchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * docs/EXTERNAL-DATA-INTEGRATION-ROADMAP.md Phase 1. Fetches a document's bytes
 * from a user-supplied URL for {@link DocumentIngestionService#ingestFromUrl} —
 * the one place in this codebase that makes an outbound HTTP request to a host it
 * doesn't control, so the guards below are the actual security boundary, not a
 * formality:
 * <ul>
 *   <li><b>Only http/https</b> — rejected before any connection attempt.</li>
 *   <li><b>SSRF guard by resolved IP, not by string matching the host</b> — a
 *   hostname is meaningless for this check (DNS is attacker-influenced input);
 *   what matters is where the connection would actually go. Rejects loopback,
 *   link-local (covers the {@code 169.254.169.254} cloud-metadata endpoint),
 *   site-local (RFC 1918), multicast, and any-local addresses. Known,
 *   accepted gap: IPv6 unique-local ({@code fc00::/7}) has no dedicated
 *   {@code InetAddress} check and isn't hand-rolled here — a real gap for a
 *   production deployment with real IPv6 egress, not for this project's scope.</li>
 *   <li><b>Never follows redirects automatically</b> — a 3xx is a failure, not
 *   silently resolved, since auto-following would let a "safe" URL redirect
 *   straight past the check above.</li>
 *   <li><b>Enforces the byte cap while reading</b>, not by trusting the remote
 *   server's own {@code Content-Length} — same "don't trust an attacker-
 *   controlled size claim" principle as {@code UploadValidationService}'s DOCX
 *   zip-bomb guard.</li>
 * </ul>
 */
@Service
public class UrlDocumentFetcher {

    private static final Logger log = LoggerFactory.getLogger(UrlDocumentFetcher.class);
    private static final List<String> ALLOWED_SCHEMES = List.of("http", "https");

    private final IngestionProperties ingestionProperties;
    private final HttpClient httpClient;
    private final HostResolver hostResolver;

    @Autowired
    public UrlDocumentFetcher(IngestionProperties ingestionProperties) {
        this(ingestionProperties, InetAddress::getAllByName);
    }

    /**
     * Package-private seam for tests only. The SSRF guard itself always uses real DNS
     * resolution in production ({@link #UrlDocumentFetcher(IngestionProperties)}); this
     * overload lets a test stub out just the "what IP does this hostname claim to be"
     * check while the actual TCP connection made by {@code httpClient} below still goes
     * for real over the network — so a test server on {@code 127.0.0.1} can be told to
     * look like a public address without the guard's real-world behavior changing at all.
     * {@code @Autowired} on the other constructor is what tells Spring to use it for DI
     * instead of getting confused between two candidate constructors.
     */
    UrlDocumentFetcher(IngestionProperties ingestionProperties, HostResolver hostResolver) {
        this.ingestionProperties = ingestionProperties;
        this.hostResolver = hostResolver;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(ingestionProperties.urlImport().timeout())
                // Deliberate: see class javadoc - auto-following a redirect would let a
                // request that passed the private-address check below be silently
                // re-pointed at an internal host by the very server we just fetched.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    public record FetchedContent(byte[] bytes, String filename, String contentType) {
    }

    public FetchedContent fetch(String url) {
        URI uri = parseUri(url);
        assertAllowedScheme(uri);
        assertNotPrivateAddress(uri);

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(ingestionProperties.urlImport().timeout())
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new UrlFetchException("Failed to fetch URL: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UrlFetchException("Fetching the URL was interrupted", e);
        }

        int status = response.statusCode();
        if (status >= 300 && status < 400) {
            throw new UrlFetchException(
                    "The URL returned a redirect (status " + status + ") - redirects are not followed "
                            + "automatically; please supply the final URL directly.");
        }
        if (status < 200 || status >= 300) {
            throw new UrlFetchException("The URL returned status " + status + ", expected a successful response");
        }

        byte[] bytes = readWithLimit(response.body(), ingestionProperties.urlImport().maxBytes());
        String filename = filenameFromPath(uri);
        // Prefer the extension's own canonical MIME type over the remote server's
        // declared Content-Type: real hosts get this wrong for plain-text-ish formats
        // (raw.githubusercontent.com sends "text/plain" for both .md and .txt, the one
        // signal that tells UploadValidationService MARKDOWN from TEXT apart, since
        // neither has distinguishing magic bytes) - see UploadValidationService
        // .canonicalContentTypeFor's javadoc. Falls back to the header only when the
        // extension isn't recognized at all, in which case validation rejects it as an
        // unsupported extension either way.
        String contentType = UploadValidationService.canonicalContentTypeFor(filename)
                .orElseGet(() -> response.headers().firstValue("Content-Type")
                        // A real Content-Type header can carry a charset parameter
                        // ("text/plain; charset=utf-8") that would never match the exact
                        // strings in ingestion.allowed-content-types - stripped here so
                        // this input is compared on equal footing with a multipart
                        // upload's MultipartFile.getContentType(), which browsers send bare.
                        .map(value -> value.split(";", 2)[0].trim())
                        .orElse(null));

        log.info("Fetched URL for import: url={} filename={} contentType={} bytes={}",
                url, filename, contentType, bytes.length);
        return new FetchedContent(bytes, filename, contentType);
    }

    private static URI parseUri(String url) {
        try {
            return new URI(url);
        } catch (java.net.URISyntaxException e) {
            throw new UrlFetchException("Not a valid URL: " + url);
        }
    }

    private static void assertAllowedScheme(URI uri) {
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme)) {
            throw new UrlFetchException("Unsupported URL scheme: " + uri.getScheme() + " (only http/https allowed)");
        }
    }

    private void assertNotPrivateAddress(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            throw new UrlFetchException("URL has no host: " + uri);
        }
        InetAddress[] addresses;
        try {
            addresses = hostResolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new UrlFetchException("Could not resolve host: " + host, e);
        }
        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isMulticastAddress() || address.isAnyLocalAddress()) {
                throw new UrlFetchException(
                        "Refusing to fetch from a private/internal address: " + host + " resolved to " + address);
            }
        }
    }

    private static String filenameFromPath(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isBlank() || path.equals("/")) {
            return "download";
        }
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    private static byte[] readWithLimit(InputStream input, long maxBytes) {
        try (input) {
            byte[] limited = input.readNBytes(Math.toIntExact(maxBytes) + 1);
            if (limited.length > maxBytes) {
                throw new UrlFetchException("Response exceeds the maximum allowed size of " + maxBytes + " bytes");
            }
            return limited;
        } catch (IOException e) {
            throw new UrlFetchException("Failed to read the fetched URL's content: " + e.getMessage(), e);
        }
    }
}
