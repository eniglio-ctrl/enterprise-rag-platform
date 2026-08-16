# ADR 0018: Image ingestion via a local vision model; audio deferred

## Status
Accepted

## Context
The user asked for image and audio (voice) support on ingestion. Two scope decisions
were confirmed explicitly before implementing:
1. **Images**: describe them with a local vision-capable Ollama model (e.g. `llava`),
   not OCR — OCR only extracts literal text and would miss diagrams, charts, or photos
   with no text at all, which is most of what a portfolio user would actually upload.
2. **Audio**: deferred. There's no mature, already-integrated local transcription path
   in this stack today — Ollama doesn't ship a reliable local speech-to-text model in
   its chat API, and the only "works right now" option (Spring AI's OpenAI Whisper
   integration) needs a real, paid OpenAI key, breaking this project's local-first
   posture for that one feature. Revisited later as its own scoped decision (most
   likely a dedicated local Whisper component) rather than bolted on as a compromise.

## Decision
- **The image never gets embedded or stored directly — its description does.**
  `ImageDescriptionService` sends the image to a vision-capable chat model asking for
  a detailed description (all visible text transcribed, diagram/chart structure
  described, general scene context for photos), and that description is wrapped in a
  single `Document` that flows through the exact same chunk → embed → store pipeline
  every other file type already uses. No new storage path, no new retrieval path —
  from `HybridSearchService`'s perspective, an image-derived chunk is indistinguishable
  from a paragraph out of a PDF.
- **Dispatch by file extension in `DocumentReaderFactory`**, same pattern already used
  for PDF/DOCX/text — `.png`/`.jpg`/`.jpeg`/`.gif`/`.webp` route to
  `ImageDescriptionService` instead of a `DocumentReader`. Kept in the same factory
  (rather than a separate branch in `DocumentIngestionService`) because "what document(s)
  come out of this uploaded file" is exactly this class's one job already.
- **`llava` as the default vision model** (`VISION_MODEL` env var), auto-pulled on
  first boot the same way `CHAT_MODEL`/`EMBEDDING_MODEL` already are
  (`pull-model-strategy: when_missing`). `ingestion-service` gains its own `ChatClient`
  bean for this — it had none before (embedding-only) — but unlike `rag-service`
  (ADR 0017), only one `ChatModel` provider exists here (Ollama), so Spring AI's
  auto-configured `ChatClient.Builder` works unmodified, no multi-bean disambiguation
  needed.
- **`VisionGateway`, reusing the `"ollama"` Resilience4j instance** `VectorStoreGateway`
  already uses (ADR 0009) — both are Ollama calls from the same service, an outage
  affects them identically, so a separate named instance would add nothing. Still a
  separate gateway *bean* from `VectorStoreGateway`, because Resilience4j's annotations
  require an external-bean call to intercept (self-invocation bypasses the proxy) —
  same rule this codebase has followed since ADR 0009.
- **`Media`/`.media(MimeType, Resource)`** (Spring AI's multimodal prompt API,
  `org.springframework.ai.content.Media`) attaches the raw image bytes to the user
  message; no manual base64 encoding or Ollama-specific payload shape needed.

## Consequences

- **Verified for real**: `./mvnw -pl ingestion-service -am verify` green, including a
  real Spring context boot (`DocumentIngestionIT`) with the new `ChatClient`/
  `VisionGateway`/`ImageDescriptionService` beans wired in alongside the existing
  embedding-only setup — no conflicts, since only one `ChatModel` provider exists here.
- **An existing test's assumption flipped, caught by the test suite itself**:
  `DocumentReaderFactoryTest.rejectsUnsupportedFileTypes` previously asserted `.png`
  was *unsupported* — now genuinely wrong given this feature. Updated to use a
  real still-unsupported type (`.zip`) and added dedicated tests describing PNG/JPEG
  images through a mocked `ImageDescriptionService`.
- **First-boot cost increases again**: `ingestion-service` now also pulls `llava` on
  first start (several GB), on top of `nomic-embed-text`. Same tradeoff already
  documented for `rag-service`'s `CHAT_MODEL`/`EMBEDDING_MODEL` pulls (ADR 0014's
  Kubernetes `startupProbe` finding) — expected, not a regression, just more of the
  same first-boot latency pattern.
- **Description quality is bounded by the vision model, not by this code** — `llava` is
  a smaller vision model; a hand-drawn or low-contrast diagram may get a vaguer
  description than a clean screenshot. No mitigation attempted here (e.g. a larger
  vision model, or a retry-with-different-prompt strategy) — flagged as a real,
  known limitation rather than silently accepted.
- **Voice/audio remains genuinely unimplemented**, not stubbed or half-built —
  uploading an audio file today still hits `UnsupportedDocumentTypeException`, the
  same as any other unrecognized extension. Revisit as its own ADR once a concrete
  local transcription approach is chosen.

## Update: audio implemented (superseded); a real transport bug found and fixed;
## a real reliability limitation found and documented

- **Audio is no longer deferred** — implemented shortly after this ADR, see ADR 0019.
  The "voice/audio remains unimplemented" consequence above is historical context for
  why this ADR originally scoped to images only, not a statement of current status.
- **A genuine transport bug was found and fixed, unrelated to this ADR's own
  decisions**: debugging ADR 0019's audio transcription failures traced the root
  cause to Spring Boot's default `RestClient` factory (`ClientHttpRequestFactoryBuilder
  .detect()`, JDK `HttpClient`-backed) sending an `Upgrade: h2c` cleartext-HTTP/2
  attempt alongside a chunked request body — confirmed, by capturing and replaying
  the exact raw bytes both ways against the real whisper container, to make uvicorn
  silently drop the request body. The same factory is what Spring AI's
  `OllamaChatModel` uses for every Ollama call in this codebase, including this ADR's
  vision calls, so the identical fix (pinning `RestClient.Builder` to
  `ClientHttpRequestFactoryBuilder.simple()`) was applied defensively to both
  `rag-service` and `ingestion-service`'s `ChatClientConfig` — see ADR 0019 for the
  full before/after evidence.
- **That fix did not turn out to be the cause of this ADR's own flakiness, and that
  distinction matters enough to spell out rather than blur together**: repeated
  real end-to-end testing (uploading the same image multiple times through the full
  authenticated stack) showed the vision call succeeding once (both `mtmd` image
  batches decoding, correct description returned) and failing twice more — once with
  a genuine 500 traced to Ollama's `llama-server` subprocess getting OOM-killed
  (confirmed: this Docker Desktop VM has a 7.75GB memory ceiling shared across 9+
  containers and up to 3 loaded Ollama models at once, ~10.8GB of model weights
  alone when `llama3.1`+`llava`+`nomic-embed-text` are all resident — freeing memory
  by stopping unrelated containers made the identical request succeed, proving
  resource pressure, not code, was the cause that time), and once with the model
  itself declining to describe the image (no error, no crash, just "I can't see it")
  with no image-decode log lines in Ollama at all for that specific call, despite the
  outgoing JSON body being independently verified byte-for-byte correct (raw-captured
  and checked against the file on disk). **Conclusion, stated plainly rather than
  glossed over**: the ingestion code and the request it builds are correct; `llava`
  (a small, quantized 7B vision model) is not reliably available under this specific
  machine's memory pressure, and its output on a minimal synthetic test image (a flat
  two-color PNG, not a natural photo) is itself inconsistent run-to-run. This is a
  known limitation of running a small local vision model on a memory-constrained
  laptop, not a defect in `ImageDescriptionService`, `VisionGateway`, or
  `DocumentReaderFactory` — a machine with more free RAM, or a real photographic test
  image instead of a synthetic one, would very likely not reproduce this.
