# ADR 0055: OCR fallback for scanned PDFs via the existing vision pipeline

## Status
Accepted

## Context
`DocumentReaderFactory` reads PDFs with Spring AI's `PagePdfDocumentReader`,
which only extracts text that already exists in the PDF's text layer. Reading
its source confirmed the real failure mode: a page with no text layer at all
(a genuinely scanned page) never becomes a `Document` — not a `Document` with
empty text, the page is dropped entirely (`StringUtils.hasText(pageText)`
gates whether a page is even added to the internal group). The practical
consequence, today: uploading a fully scanned PDF returns `201 Created` with
`IngestResponse{pageCount: 0, chunkCount: 0}` — a "successful" upload that
indexed nothing, with no error surfaced anywhere.

The project already has a working vision pipeline (`ImageDescriptionService`,
ADR 0018) that describes an image via a local Ollama vision model
(`VISION_MODEL`, e.g. `llava`), reached through `VisionGateway`'s
circuit-breaker/retry/bulkhead wrapping (ADR 0018/0019). Reusing it for
scanned pages — render the page as an image, describe it the same way an
uploaded image is described — avoids introducing a dedicated OCR engine
(Tesseract or similar) as a new dependency and model to operate.

## Decision

### Detection: two distinct kinds of "page with no usable text"
Confirmed by reading `PagePdfDocumentReader`'s source, there are two cases,
not one:
1. **A page with zero text** (a real scanned page): never produces a
   `Document` at all. Only detectable by comparing the PDF's real total page
   count (via PDFBox, `PDDocument.getNumberOfPages()`) against which
   `page_number` values actually appear in the extracted `Document` list —
   `PagePdfDocumentReader` never sets a `total_page_count` metadata key, so
   this independent PDFBox check is the only way to know a page went
   missing.
2. **A page with junk text** (e.g. only a running header or a page number):
   this does produce a `Document`, just with a very short `getText()`.
   Detected with a configurable minimum-length threshold
   (`ingestion.pdf-ocr.min-text-length-per-page`, default 20).

**A real, subtle bug found only by manual verification against the running
stack (see Consequences below): `PagePdfDocumentReader`'s `page_number`
metadata is 1-indexed, not 0-indexed.** Confirmed by actually running the
reader: a single-page PDF's one `Document` carries `page_number: 1`, and in
a 3-page PDF the third page's `Document` carries `page_number: 3` — not the
raw 0-based physical page index its own field name suggests. The service
matches this convention exactly (comparing `pageIndex + 1` against the
extracted metadata, and stamping the same `pageIndex + 1` on any
vision-derived fallback `Document`) rather than normalizing to 0-indexed
internally, so every `Document` in the final merged list — real-text and
vision-derived alike — sorts correctly under one shared convention.

### New `ScannedPageVisionFallbackService`
Injects the existing `ImageDescriptionService` (not duplicated) and
`IngestionProperties`. `fillMissingPages(byte[] pdfBytes, List<Document>
extracted, String filename)`:
1. Opens `pdfBytes` with PDFBox (`Loader.loadPDF`) to get the real page
   count.
2. Builds the set of already-good page numbers (present in `extracted` AND
   passing the minimum text length).
3. For every remaining page, up to `ingestion.pdf-ocr.max-ocr-pages` (default
   20), renders it via `PDFRenderer.renderImageWithDPI(pageIndex, 200)` to a
   PNG and calls `imageDescriptionService.describe(pngBytes,
   MimeTypeUtils.IMAGE_PNG)`. The result becomes a new `Document` carrying
   the same `page_number` metadata key `PagePdfDocumentReader` already uses,
   so the final list can be sorted back into page order.
4. Merges good pages and vision-derived pages, sorted by `page_number`.

`DocumentReaderFactory.read()`'s `PDF` case now pipes
`PagePdfDocumentReader`'s output through this service before returning — no
change to any other `DocumentKind` branch.

### `max-ocr-pages`: an explicit, documented cap, not unbounded processing
Same "explicit limit enforced during real processing" philosophy already
used for the DOCX zip-bomb guard (`IngestionProperties.Docx`), the URL-import
byte cap (`IngestionProperties.UrlImport`), and
`rag.document-insights.max-chunks`. A scanned PDF larger than the configured
cap gets only its first N pages OCR'd synchronously within the upload
request — an accepted, documented limitation. A genuinely large scanned
document is a case for the asynchronous ingestion infrastructure Production
Readiness Phase 3 already tracks (the same dependency the Audio Overview
phase in the Product Differentiation roadmap already calls out), not
something this phase solves.

### Configuration
```yaml
ingestion:
  pdf-ocr:
    min-text-length-per-page: 20
    max-ocr-pages: 20
```
No new model configuration key: `ImageDescriptionService` already reads
`spring.ai.ollama.chat.options.model` (`VISION_MODEL`) and
`ingestion.ollama.*` for timeouts, and this feature reuses that same call
path unchanged.

### New metric
`ingestion.pdf.ocr_pages` — a `Counter` incremented once per page that
actually needed the vision fallback, registered in
`ScannedPageVisionFallbackService`'s constructor the same way
`DocumentIngestionService` already registers
`rag.documents.ingested`/its chunk counter.

## Consequences

### A real bug found only by manual verification against the running stack
Every automated test written for this phase originally passed with a
hand-built `extracted` list (`Document.builder().metadata("page_number",
0)...`) instead of the real `PagePdfDocumentReader` output — which hid the
1-indexed `page_number` finding above completely. Manually uploading a
genuinely normal, real-text single-page PDF to the actual running stack
surfaced it immediately: the upload took over two minutes (a real
Ollama `llava` call that should never have happened) and came back with
`pageCount: 2, chunkCount: 2` for what was really a one-page document — the
service treated the already-good page as "missing" (comparing its
1-indexed `page_number: 1` against a 0-indexed loop expecting `0`),
rendered and described it a second time via the vision model, and appended
a duplicate `Document` alongside the original.

Fixed by comparing `pageIndex + 1` (not `pageIndex`) against the extracted
metadata, and by rewriting every unit test to run the real
`PagePdfDocumentReader` against an actual PDFBox-built PDF instead of
fabricating `page_number` metadata by hand — a hand-fabricated value cannot
catch a bug in what that same value should have been. A new
`DocumentIngestionIT` test (`uploadingAPdfWithRealTextNeverCallsTheVisionModel`)
asserts `chatModel` is never called for a real single-page, real-text PDF,
specifically to keep this regression caught at the IT level too.

Re-verified against the real running stack after the fix: the same
real-text PDF now returns `pageCount: 1, chunkCount: 1` in under 3 seconds
(no vision-model call), and a genuinely blank/scanned PDF still correctly
returns `pageCount: 1, chunkCount: 1` via the real vision fallback (~110s,
a real Ollama `llava` call).

### Verified
- Full `./mvnw -pl ingestion-service test` suite green (64 tests across 7
  classes, including the Testcontainers-backed `DocumentIngestionIT`), with
  new/extended coverage:
  - `ScannedPageVisionFallbackServiceTest` — runs the real
    `PagePdfDocumentReader` against an actual 2-page PDF (PDFBox, one page
    with real drawn text, one genuinely blank page) to confirm the text
    page passes through untouched and the blank page gets a vision-derived
    `Document` with the correct (1-indexed) `page_number`; a second test
    confirms the vision model is never called when every page already has
    real text.
  - `DocumentReaderFactoryTest` gained its first PDF test case (previously
    zero PDF coverage existed at all).
  - `DocumentIngestionIT.uploadingARealPdfIngestsItIntoTheVectorStore` now
    stubs `chatModel` (matching the existing PNG-image test's pattern) and
    asserts `pageCount`/`chunkCount` are non-zero for its blank-page PDF
    fixture — before this phase, that same fixture silently produced
    `pageCount: 0, chunkCount: 0`.
  - `DocumentIngestionIT.uploadingAPdfWithRealTextNeverCallsTheVisionModel`
    (new) — regression coverage for the bug above, using a genuinely
    real-text PDF and asserting the vision model is never invoked.

### A normal, real-text PDF is unaffected
`fillMissingPages` only reaches `imageDescriptionService.describe(...)` for
pages that fail the "already good" check — a PDF whose every page has real
text never triggers a single vision-model call, so there is no behavior
change or added cost for the common case.

### Known limitation, accepted
Scanned PDFs beyond `max-ocr-pages` only get their first N pages processed
in a given synchronous upload request. Documented, not silently truncated —
the same trade-off this project already accepts elsewhere for synchronous,
request-scoped processing limits.
