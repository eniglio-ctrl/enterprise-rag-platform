# ADR 0023: Ephemeral image attachment on `/api/v1/ask`

## Status
Accepted

## Context
The platform already supports images as a *document* — uploaded via
`/api/v1/documents`, described once by a vision model, and permanently indexed
(ADR 0018). The user asked for a second, different capability: attaching an image
directly to a single question (via a paperclip icon in the chat input), with the
image interpreted just to help answer that one question — not saved, not indexed,
gone once the response comes back. This needed to work both in the local
docker-compose stack and in the read-only public demo (ADR 0020).

Four scope decisions were confirmed with the user before implementing:
- **Ephemeral, not permanent.** The attached image is described once and folded
  into that single request's context; it never reaches the vector store. A
  second option — routing the attachment through the existing
  `/api/v1/documents` ingestion pipeline — was considered and rejected, since it
  doesn't match "interpreted for this question."
- **Images only** (PNG/JPEG/GIF/WebP) — not PDF/DOCX/audio, keeping this
  feature's scope matched to what was explicitly asked for.
- **Only `/api/v1/ask`**, not `/api/v1/chat` (chat-service's conversation-aware
  path, ADR 0013) — `/api/v1/ask` is the one endpoint both the local stack and
  the public demo share, and chat-service isn't part of the demo deployment at
  all.
- **The public demo needed its own vision provider.** The demo's text-chat model
  (Groq's `llama-3.3-70b-versatile`, ADR 0020) has no vision capability, and
  there's no Ollama reachable from the deployed demo. Mistral AI — already the
  demo's embedding provider — turned out to also offer a vision-capable model
  (`pixtral-12b-2409`), reused here for a second, unrelated reason rather than
  adding a fourth external account.

## Decision
- **`rag-service` gains its first multipart endpoint.** `POST /api/v1/ask` now
  has two mappings on the same path, dispatched by `consumes`: the existing
  `application/json` form (unchanged, `ChatRequest`), and a new
  `multipart/form-data` form (`question`/`grounded`/`rerank`/`model` as form
  fields, plus an optional `image` file part). Two methods on one path rather
  than changing the JSON contract — every existing JSON caller keeps working
  exactly as before.
- **`ImageAttachmentValidator`** (new) checks the attached image the same way
  ingestion-service's `UploadValidationService` checks a document (ADR 0022):
  declared content type must be one of the four accepted, and the actual bytes
  must match a magic-byte signature for that type — scoped down to just images,
  since that's the only kind this ephemeral path accepts.
  `UnsupportedImageTypeException` (415)/`InvalidImageAttachmentException` (422)
  mirror `ingestion-service`'s exception pair and HTTP-status reasoning exactly.
- **`VisionDescriptionService`** (new interface) describes the validated image
  once, ephemerally. Exactly one implementation is active per Spring profile —
  `OllamaVisionDescriptionService` (`@Profile("!demo")`, reuses the existing
  `ollama` `ChatClient` bean with a per-call model override to `llava`, the same
  `VISION_MODEL` env var ingestion-service already uses) and
  `MistralVisionDescriptionService` (`@Profile("demo")`, a new `mistralVision`
  `ChatClient` bean wrapping Mistral's Pixtral model). `ChatClientConfig` gained
  one new `@Profile("demo")`-scoped bean for this; `application-demo.yml` had to
  stop excluding `MistralAiChatAutoConfiguration` (it stays excluded in the base
  `application.yml` — local image attachments use Ollama, no Mistral chat model
  needed there at all). Adding a third `ChatModel` bean to the demo profile is
  safe here specifically because nothing in this app injects `ChatModel`
  unqualified (ADR 0017 already established qualified-bean injection
  everywhere), so Spring AI's own single-candidate auto-configuration — which
  would otherwise back off with more than one `ChatModel` bean — was never
  relied on.
- **The description folds into `RagQueryService`'s existing context, not a new
  code path.** `doAnswer`/`doDiagram` both gained an optional `imageDescription`
  parameter; when present, it's prepended to the numbered `[1]`, `[2]`...
  retrieved-chunk context as a distinct `[IMAGEM]` block, with an extra
  instruction line telling the model not to cite it with a bracket number — the
  image has no corresponding entry in the `citations` array returned to the
  caller, so letting the model invent a citation index for it would produce a
  citation the response doesn't actually have.
- **`web-ui`'s question box also accepts a pasted screenshot directly**
  (Cmd/Ctrl+V), not just the 📎 file picker — a `paste` listener on the
  textarea checks `event.clipboardData.items` for an image, and if found,
  turns it into a `File` the exact same way the file input already does
  (`setAttachedImage`, shared by both paths), so the preview chip and the
  multipart submit logic are identical regardless of how the image arrived.
- **An attached image can answer a question even with zero retrieved chunks.**
  Both `doAnswer` and `doDiagram` previously short-circuited to "not enough
  information"/an empty diagram whenever retrieval came back empty. That's
  wrong when an image is attached — "what does this screenshot show?" needs no
  match in the knowledge base at all. The early-return now only fires when
  *both* retrieval is empty *and* there's no image.
- **`rag-service/application.yml` gained its first `spring.servlet.multipart.*`
  config** (10MB cap — smaller than `ingestion-service`'s 25MB document limit,
  since this is always a single ephemeral image, never a document).

## Consequences
- `web-ui`'s ask form gained a 📎 icon (a hidden `<input type="file">`, same
  idiom the existing upload dropzone already used) and a small attachment-name
  preview chip with a clear button. The submit handler sends `multipart/form-data`
  only when a file is actually attached; a plain question still sends the
  original JSON body, completely unchanged.
- `RagQueryService`'s constructor gained a `VisionDescriptionService` parameter;
  `RagQueryServiceTest`'s test double setup was updated accordingly. New tests
  cover: an attached image answering with zero retrieved chunks, the
  `[IMAGEM]` block reaching the model's system prompt without numbered-citation
  confusion, and that no image attached never touches
  `VisionDescriptionService` at all. `ImageAttachmentValidatorTest` covers all
  four accepted formats plus rejection of an unsupported type, a missing
  content type, corrupted bytes, and a JPEG disguised as a PNG.
  `ChatQueryIT` gained three real HTTP tests through the new multipart mapping:
  a successful attached-image answer, a 415 for an unsupported attachment type,
  and a 422 for bytes that don't match the declared type.
- **Not routed through a Resilience4j circuit breaker/retry**
  (`MistralVisionDescriptionService`, unlike the Ollama path which reuses the
  existing `"ollama"` `LlmGateway` instance) — the only Mistral chat call in the
  app, and a dedicated instance for one demo-only, best-effort capability wasn't
  judged worth the extra config. A real, revisitable trade-off if this proves
  flaky in practice, not an oversight.
- The public demo (`ag-service-demo.onrender.com`) needs `SPRING_PROFILES_ACTIVE=demo`
  redeployed with this change and its existing `MISTRAL_API_KEY` (already
  required for embeddings) reused — no new required environment variable.

## Update: `llava`'s known reliability limitation (ADR 0018) also affects this
## ephemeral path — same conclusion, no new mitigation

A real user attached a real screenshot locally and got "não posso fornecer
informações sobre a imagem" back — not a code bug: `rag-service`'s own log
confirmed the request was received, validated, and processed
(`"Answered question using N retrieved chunks and an attached image"`), so
`OllamaVisionDescriptionService.describe(...)` genuinely ran and `llava` itself
returned a refusal-shaped response instead of a real description. That
refusal became the `[IMAGEM]` context block verbatim, and `llama3.1` correctly
reported back that it had nothing to work with.

This is the exact, already-documented limitation from
[ADR 0018](0018-image-ingestion-via-vision-model.md)'s update section —
*"Description quality is bounded by the vision model, not by this code ...
No mitigation attempted here (e.g. a larger vision model, or a
retry-with-different-prompt strategy) — flagged as a real, known limitation
rather than silently accepted."* That decision carries over unchanged to this
ephemeral attachment path, since it's the identical model behind the identical
call. Confirmed during this investigation: the demo profile's Mistral Pixtral
path answered the same class of question correctly every time it was tried,
while local `llava` succeeded on simple synthetic test images (a plain
two-color shape) but failed on a real screenshot — consistent with the smaller,
quantized local model's variance already described in ADR 0018, not a new or
different failure mode this feature introduced.

**No retry-on-refusal or prompt-tuning mitigation was added**, matching ADR
0018's explicit prior decision not to engineer around this specific model's
unreliability. If `llava`'s failure rate on real photos becomes a bigger
practical problem, the fix belongs at the same place ADR 0018 already pointed
to (a larger/different local vision model), not a workaround layered on top of
a small one.
