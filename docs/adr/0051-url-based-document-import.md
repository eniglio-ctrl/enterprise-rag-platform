# ADR 0051: URL-based document import (SSRF guard design)

## Status
Accepted

## Context
`docs/EXTERNAL-DATA-INTEGRATION-ROADMAP.md` Phase 1: `POST
/api/v1/documents/from-url` lets a user hand `ingestion-service` a URL instead
of multipart bytes. This is the first place in the codebase that makes an
outbound HTTP request to a host it doesn't control on the user's behalf, so
unlike every other feature so far, the server itself becomes the attacker's
vantage point if the fetch isn't guarded — a URL like
`http://169.254.169.254/latest/meta-data` or `http://10.0.0.5:5432` would let
a user probe or reach infrastructure the caller has no direct network access
to (classic SSRF).

## Decision

### Reuse the existing validate/chunk/embed pipeline, not a parallel one
`UploadValidationService.validate(MultipartFile)` and
`DocumentIngestionService.ingest(MultipartFile, ...)` were both refactored to
extract a byte-array-based core (`validate(byte[], filename,
contentType)` / a private `doIngest(ValidatedUpload, source, ...)`), with the
original `MultipartFile` methods becoming thin adapters. `ingestFromUrl`
fetches bytes via the new `UrlDocumentFetcher`, then calls the exact same
core — same accepted file types, same magic-byte/DOCX-structure checks, same
chunking/metadata/vector-store code, zero duplicated logic, zero behavior
change for the existing multipart path (proven by the full existing test
suite passing unmodified after the refactor).

### SSRF guard by resolved IP, not by string-matching the hostname
A hostname is attacker-influenced input — DNS rebinding means "this host
isn't 127.0.0.1" can be true at check time and false at connect time if the
check only looks at the string. `UrlDocumentFetcher` instead resolves the
host via `InetAddress.getAllByName` and rejects the request if **any**
resolved address is loopback, link-local (covers the `169.254.169.254` cloud
metadata endpoint), site-local (RFC 1918), multicast, or any-local. Known,
accepted gap: IPv6 unique-local (`fc00::/7`) has no dedicated `InetAddress`
check and isn't hand-rolled — real gap for a deployment with real IPv6
egress, out of scope for this project.

### Redirects are never followed automatically
`HttpClient.Redirect.NEVER`. Auto-following would let a URL that legitimately
passes the address check above redirect the *second* request straight into a
blocked address, silently defeating the guard. A 3xx response is surfaced as
a client error asking for the final URL directly, not silently resolved.

### The byte-size cap is enforced while reading, not by trusting `Content-Length`
Same principle as ADR 0022's DOCX zip-bomb defense: a remote server's declared
size is an attacker-controlled claim, not a fact. `UrlDocumentFetcher` reads
up to `maxBytes + 1` bytes and rejects if that many were actually read,
regardless of what `Content-Length` said.

### `java.net.http.HttpClient`, zero new dependency
No outbound generic HTTP client existed anywhere in `ingestion-service`
before this (only Spring AI-managed Ollama/Whisper clients). The JDK's own
client needed no new Maven dependency and gives direct control over
redirect policy and connect timeout, both load-bearing for the guards above.

### One exception type, one HTTP status
`UrlFetchException` covers every fetch failure mode (bad scheme, blocked
address, timeout, non-2xx, redirect, oversized body) with a specific message
per case, mapped once in `GlobalExceptionHandler` to `400 Bad Request` —
same one-exception-type-per-concern convention as `InvalidUploadException`/
`UnsupportedDocumentTypeException`.

### Content-type is derived from the extension, not trusted from the response header
Found during manual verification, not anticipated in planning: `.md` and `.txt`
have no distinguishing magic bytes, so `UploadValidationService` relies
entirely on the declared content type to tell `MARKDOWN` and `TEXT` apart.
`raw.githubusercontent.com` — about as canonical a "URL a user would import"
example as exists — serves *both* as `text/plain`, which made the very first
real test of this feature fail with a content-type-mismatch 422.
`UploadValidationService` gained a small `canonicalContentTypeFor(filename)`
lookup against its own existing extension→MIME table; `UrlDocumentFetcher`
now prefers that over the response's `Content-Type` header, falling back to
the header only when the extension isn't recognized at all (in which case
validation rejects it as an unsupported extension regardless). Trusting the
one MIME mapping this codebase already owns over an arbitrary remote
server's header is the same "don't trust externally-controlled metadata"
instinct behind the SSRF and byte-cap guards above — just applied to a
correctness problem instead of a security one. The multipart upload path is
completely unaffected: it never calls this new lookup.

### A dedicated rate-limit rule, not reuse of the existing upload rule
`AntPathMatcher`'s exact-path rule for `/api/v1/documents` does not cover
`/api/v1/documents/from-url`. A URL fetch also does real outbound network
I/O this project doesn't otherwise do, worth its own (tighter) budget
independent of ordinary multipart upload volume — added as a second rule in
`application.yml`.

## Consequences

### Tested for real against a real loopback target and a real local server
`UrlDocumentFetcherTest` uses a real local `com.sun.net.httpserver.HttpServer`
(JDK built-in, no new test dependency) — a genuine socket round trip for the
success case, and a genuine attempt against `127.0.0.1` for the blocked case
(not a mock standing in for either). Since the guard correctly rejects
loopback, the success/redirect/oversized cases needed a small testability
seam: a package-private `UrlDocumentFetcher(IngestionProperties,
HostResolver)` constructor lets a test stub only "what IP does this hostname
claim to be" while the real `HttpClient` still makes a real TCP connection to
the local server. Production code only ever uses the public constructor
(real `InetAddress.getAllByName`) — the seam does not weaken the guard that
actually ships. `DocumentIngestionIT` reuses the same seam (via a
`@TestConfiguration`-scoped `@Primary` bean override, in
`TestUrlDocumentFetchers`, kept in `UrlDocumentFetcher`'s own package under
`src/test/java` so it can reach the package-private constructor from a
different test package) to prove `POST /api/v1/documents/from-url` produces
the same `IngestResponse` shape end-to-end against real Postgres/pgvector
that the multipart path already does.

### Full existing suite re-verified, not just the new tests
`./mvnw -pl ingestion-service -am test` passes in full after the
`UploadValidationService`/`DocumentIngestionService` extraction refactor —
direct evidence the multipart path's behavior didn't change.

### A real deployment bug the automated suite never caught
`UrlDocumentFetcher` ended up with two constructors (the production one, and
a package-private test-only overload for the `HostResolver` seam above).
Every automated test — unit and IT alike — still passed, because the IT's
`@TestConfiguration` bean method calls the Java constructor directly rather
than going through Spring's reflective autowiring. Only rebuilding and
restarting the real `docker compose` stack surfaced
`UnsatisfiedDependencyException: No default constructor found`: with two
declared constructors and neither marked `@Autowired`, Spring's
`AutowiredAnnotationBeanPostProcessor` refuses to guess and falls back to a
no-arg constructor that doesn't exist. Fixed by marking the public
constructor `@Autowired` explicitly. Concrete evidence for this project's
standing "verify against the real stack, not just the test suite" practice —
this specific failure mode was invisible to every layer of automated testing
written for this phase.

### Manual, end-to-end verification against the real running stack
After both fixes above, rebuilt `ingestion-service` via `docker compose up
-d --build`, registered a real test user via `auth-service`, and called
`POST /api/v1/documents/from-url` with a real public URL
(`raw.githubusercontent.com/kubernetes/kubernetes/master/CONTRIBUTING.md`) —
`201 Created`. Confirmed the row landed in the real `vector_store` table
under the correct `tenantId`. Asking `rag-service`'s `/api/v1/ask` a question
about the imported content produced a hybrid-search log line confirming
real retrieval (`1 vector hits, 1 full-text hits, 1 after RRF fusion`) under
the same tenant — the document is genuinely findable, which is this phase's
actual "done when" bar. The local answer itself came back ungrounded (this
project's existing local-LLM groundedness gate, ADR 0024, offering the
public-LLM fallback instead) — a pre-existing local-model-quality behavior
unrelated to URL import, reproducible the same way with an ordinary
multipart upload, and out of this phase's scope to fix.
