# ADR 0019: Audio ingestion via a local Whisper ASR server

## Status
Accepted

## Context
ADR 0018 deferred audio support because no mature local transcription path existed
in the stack yet — the only "works right now" option was OpenAI's paid Whisper API,
which breaks this project's local-first posture. The user asked for it explicitly
again shortly after, so this phase picks a concrete local approach instead of
deferring further.

## Decision
- **`onerahmet/openai-whisper-asr-webservice`, a dedicated container, not a
  Java-native library**: there's no mature local speech-to-text model runnable
  through Ollama the way vision models are (ADR 0018) — Whisper's actual model
  weights and inference need a Python runtime. Rather than embedding that into
  `ingestion-service` itself, it runs as its own `docker-compose` service
  (`whisper`), the same "separate concern, separate container" shape `postgres` and
  `ollama` already have.
- **API contract confirmed against the real container before writing any code**: a
  spoken test `.wav` (generated locally via `say`/`afconvert`, no external dependency)
  was POSTed to a running `whisper` container first — `POST /asr?output=txt` with the
  audio as multipart field `audio_file` returns the plain-text transcript as the
  response body, verified with a real 200 and a correct transcript, not assumed from
  the project's documentation.
- **The audio itself never gets embedded or stored — its transcript does**, exactly
  the same shape as ADR 0018's image handling: `AudioTranscriptionService` produces a
  transcript, `DocumentReaderFactory` wraps it in a single `Document`, and it flows
  through the same chunk → embed → store pipeline as any PDF or paragraph of text.
  Dispatch is by file extension (`.mp3`/`.wav`/`.m4a`/`.ogg`/`.flac`/`.webm`), same
  pattern already used for PDF/DOCX/images.
- **`AudioTranscriptionGateway` builds its own `RestClient`** (base-url +
  connect/read timeouts from `ingestion.whisper.*` config), mirroring how
  `chat-service`'s `RagServiceGateway` already talks to a sibling HTTP service —
  consistent style for "this service calls another HTTP API directly" across the
  codebase, rather than introducing a different pattern for this one case.
- **Own Resilience4j instance, `"whisper"`, separate from `"ollama"`**: Whisper being
  down is a different failure than an Ollama outage — same reasoning ADR 0017 already
  used to keep the LM Studio breaker separate from Ollama's, applied again here for a
  third, unrelated dependency.
- **`ASR_MODEL: base`**: a reasonable middle ground for CPU-only local inference —
  `tiny` is faster but noticeably less accurate, `small`/`medium` cost meaningfully
  more time per file for a portfolio demo's purposes.

## Consequences

- **Verified for real, twice**: once directly against the bare `whisper` container via
  curl (confirming the API contract before any code depended on it), and again through
  `./mvnw -pl ingestion-service -am verify`, including a real Spring context boot
  (`DocumentIngestionIT`) with `AudioTranscriptionGateway`'s `@Value`-configured
  `RestClient` wiring correctly alongside every other bean.
- **A fourth external dependency for `ingestion-service` to reach**: Postgres, Ollama,
  auth-service, and now Whisper. `docker-compose.yml`'s `depends_on: whisper:
  condition: service_healthy` keeps the same "don't accept traffic before your
  dependencies are up" discipline already applied to every other service.
- **Model weights persist in a named volume** (`whisper-data`), the same reasoning as
  `ollama-data` — without it, the `base` model would re-download on every container
  recreation, not just every restart.
- **No speaker diarization, timestamps, or language auto-detection tuning** — `output=txt`
  returns a single flat transcript, which is exactly what the chunk/embed pipeline
  needs and nothing more. If a future use case needs per-speaker or per-segment
  structure, `output=json` exposes it, but that's out of scope for "make audio
  searchable," which is what this ADR set out to do.
- **First-boot cost, again**: `whisper`'s own image + model download joins
  `ollama`'s `nomic-embed-text`/`llama3.1`/`llava` pulls as one more thing a fresh
  `docker compose up` has to wait on before the stack is fully healthy. Documented
  as more of the same pattern (ADR 0014/0018), not a new kind of problem.

## Update: the real root cause of the initial 422 "field required" bug

The first working implementation of `AudioTranscriptionGateway` returned
`422 Unprocessable Entity: {"detail":[{"loc":["body","audio_file"],"msg":"field
required"...}]}` for every upload, despite the exact same request — built with
Spring's `MultipartBodyBuilder`, `filename()` and `contentType()` set explicitly —
working correctly when replayed via plain `curl`. Root cause, found by capturing the
literal bytes Java put on the wire (a raw TCP socket server, not a higher-level HTTP
library — those don't reliably decode chunked transfer encoding and produced
misleading "empty body" readings during earlier debugging attempts) and replaying
them byte-for-byte against the real `whisper` container:

- Spring Boot's default `RestClient` request factory — what
  `ClientHttpRequestFactoryBuilder.detect()` resolves to on this stack, and what this
  gateway used before the fix — is backed by the JDK's `java.net.http.HttpClient`.
  For a request with an unknown-length streamed body (any multipart upload), that
  client sends `Connection: Upgrade, HTTP2-Settings` / `Upgrade: h2c` alongside the
  chunked body, attempting an HTTP/2 cleartext upgrade.
- Replaying the exact same captured bytes twice — once as originally sent (with the
  `h2c` upgrade attempt) and once through `SimpleClientHttpRequestFactory` (no
  upgrade attempt, otherwise byte-identical) — reproduced the bug in the first case
  and got a correct `200` transcript in the second, every time. `whisper`'s
  underlying uvicorn/Starlette server mishandles the upgrade-attempt-plus-chunked-body
  combination: the multipart parser sees the request as missing the `audio_file`
  part entirely, even though the bytes are present on the wire.
- **Fix**: `AudioTranscriptionGateway`'s `RestClient` is now built with
  `ClientHttpRequestFactoryBuilder.simple()` instead of `.detect()` — same
  connect/read timeout configuration, just without the JDK `HttpClient`'s HTTP/2
  upgrade attempt. Verified fully fixed: real end-to-end upload of a spoken test
  `.wav` through the authenticated API now transcribes correctly, indexes the
  transcript, and is retrievable via a real question with a correct, grounded answer.
- **This is not whisper-specific.** Spring AI's `OllamaChatModel` builds its
  `RestClient` the same way by default, so the identical fix (`.simple()` instead of
  `.detect()`) was applied defensively to `rag-service` and `ingestion-service`'s
  shared `ChatClientConfig` `RestClient.Builder` beans too, in case any Ollama call
  with a large-enough body triggers the same server-side mishandling. See ADR 0018's
  update section for why that defensive fix did *not* turn out to be the explanation
  for this project's separate, still-flaky image-description behavior — that was
  independently traced to memory pressure and small-model output variability, not
  this transport issue.
