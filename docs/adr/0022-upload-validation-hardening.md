# ADR 0022: Upload content validation via magic bytes

## Status
Accepted

## Context
Part of the security hardening rollout registered in
[ADR 0021](0021-security-hardening-baseline.md). Before this change, an uploaded
file was trusted based on its filename extension alone
(`DocumentReaderFactory`'s `filename.endsWith(...)` dispatch) — a file named
`invoice.pdf` containing arbitrary bytes would be handed straight to PDFBox, and
`ingestion.allowedContentTypes` (`IngestionProperties`) was configured but never
actually checked anywhere in the codebase. Nothing verified that a file's declared
MIME type or its actual byte content matched what its extension claimed.

Two options were considered for verifying actual content: Apache Tika's own
`MimeTypes.detect()`/`AutoDetectParser` (already a transitive dependency via
`spring-ai-tika-document-reader`), or a small, hand-rolled magic-byte signature
table. Tika was rejected — `DocumentReaderFactory`'s own javadoc already documents
its content-sniffing as unreliable for plain-text formats like `.md`, and using it
for a security-relevant check would inherit that same unreliability exactly where
it matters most.

## Decision
- **A new `UploadValidationService`** is the single place that decides whether an
  upload is trustworthy, checking, in order: the file isn't empty; its extension
  is recognized; its declared content type is in the now-actually-enforced
  `ingestion.allowed-content-types` list; its declared content type's implied
  `DocumentKind` matches the extension's (catching, e.g., a `.pdf` upload
  declaring `audio/mpeg` even though that MIME type is separately allow-listed);
  and its actual bytes match a hand-rolled magic-byte signature for that format
  (`%PDF-` for PDF, `PK\x03\x04` for DOCX's ZIP container, `RIFF`+`WEBP`/`WAVE` at
  offset 8 for WebP/WAV, `ftyp` at offset 4 for M4A, `OggS`/`fLaC`/`ID3`-or-frame-
  sync for OGG/FLAC/MP3, the EBML header for WebM, standard PNG/JPEG/GIF
  signatures). Markdown and plain text have no fixed signature — accepted only if
  the bytes match no other format's signature and decode cleanly as UTF-8.
- **A new `ValidatedUpload` record** (bytes, filename, canonical `MimeType`,
  `DocumentKind`) is the only input `DocumentReaderFactory` accepts now — it no
  longer takes a raw `MultipartFile` or re-derives anything from the filename.
  "Read an unvalidated upload" is now a compile error, not a convention that could
  be silently skipped at a new call site later.
- **A new `InvalidUploadException` (422 Unprocessable Entity)**, deliberately a
  sibling of the existing `UnsupportedDocumentTypeException` (415 Unsupported
  Media Type), not a subclass — the two map to different HTTP statuses, and a
  subclass relationship risks one `@ExceptionHandler` silently catching both. 415
  means "this kind of file isn't accepted at all" (bad extension, or a MIME type
  never on the allow-list); 422 means "the right kind was claimed, but the content
  doesn't back that up" (wrong magic bytes, or a mismatch between declared type
  and extension).
- **`ingestion.allowed-content-types` was extended** to include the image/audio
  MIME types ADR 0018/0019 already support (`image/png`, `image/jpeg`,
  `image/gif`, `image/webp`, `audio/mpeg`, `audio/wav`, `audio/x-wav`,
  `audio/mp4`, `audio/x-m4a`, `audio/ogg`, `audio/flac`, `audio/webm`) — it was
  never updated when those formats were added, since nothing enforced it until
  now; turning on enforcement without extending the list would have broken image
  and audio upload.
- **The upload size limit is not duplicated here** — `spring.servlet.multipart.
  max-file-size` (25MB) already exists and already maps to 413 via the existing
  `GlobalExceptionHandler`; adding a second size check would add no real
  protection.

## Consequences
- A file whose bytes don't match its claimed type never reaches Tika, PDFBox,
  the vision model, or Whisper — it's rejected before any of those parsers are
  invoked.
- `DocumentReaderFactory` got simpler, not more complex: it no longer holds the
  `IMAGE_EXTENSIONS`/`AUDIO_EXTENSIONS` maps or does its own filename matching —
  it's now a single exhaustive `switch` over an already-known `DocumentKind`.
- `DocumentIngestionService`'s stored `contentType` metadata now comes from the
  validated, canonical `MimeType` rather than the raw (client-controlled)
  declared header — e.g. an `audio/x-wav` upload is stored as `audio/wav`
  consistently, regardless of which alias a given client sent.
- Verified with a real, PDFBox-generated single-page PDF, real magic-byte-correct
  PNG/WAV fixtures (with the vision model and Whisper gateway mocked, so the test
  doesn't depend on a running Ollama/Whisper), and real markdown — all four
  ingest successfully end-to-end through `DocumentIngestionIT`'s full
  upload-to-Postgres/pgvector flow; a corrupted-content-with-a-valid-extension
  case and an entirely unsupported extension are verified to return 422 and 415
  respectively via real HTTP through the same test.
- `./mvnw -pl ingestion-service -am verify` stays green (29 new/updated test
  cases across `UploadValidationServiceTest`, `DocumentReaderFactoryTest`, and
  `DocumentIngestionIT`, alongside every pre-existing test).
