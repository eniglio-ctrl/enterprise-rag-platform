# ADR 0052: Per-document summaries and FAQ generation

## Status
Accepted

## Context
`docs/PRODUCT-DIFFERENTIATION-ROADMAP.md` Phase 8: no endpoint in this codebase
generates a standalone summary or FAQ list for a document. `/api/v1/ask` answers a
specific question against a top-K similarity search; `/api/v1/diagrams` does the same
for a Mermaid diagram. Neither fits "summarize this whole document" or "generate a
FAQ for this whole document" — those need the entire document as context, not a
question-driven partial retrieval.

## Decision

### Whole-document retrieval, not similarity search
`HybridSearchService` gained `findByDocumentId(documentId, tenantId, userId)` —
same shape as the existing `findBySource` (an exact match, all chunks, ordered by
`chunkIndex`, same `filterVisible` ABAC check), just keyed by `documentId` instead of
filename. `documentId` is the identifier `web-ui` actually has on hand
(`IngestResponse`/`DocumentSummary` both carry it); a filename can repeat across
documents, a `documentId` can't.

### Extended `RagQueryService`, not a new service class
`summarizeDocument`/`generateFaq` were added directly to `RagQueryService` — no new
constructor dependencies were needed (`hybridSearchService`, the `ChatClient`s,
`llmGateway`, `ragProperties` were all already there), and the resulting methods are
the same shape as the existing `diagram()`/`doDiagram()` pair: resolve model → build
context → call the LLM via `clientFor`/`callLlm`/`modelOptions` → build the response.
Splitting this into a separate service would have meant either duplicating that
trio or exposing it as new public API surface for no real benefit.

No groundedness check (unlike `doAnswer`) and no public-LLM fallback (same scope as
`diagram()`): the entire indexed document is the context here, not a top-K
similarity result that might not actually contain the answer, so "is this answer
backed by the context" isn't a meaningful question to ask.

### FAQ asks for plain delimited text, not JSON
The model is asked for `P: <question>` / `R: <answer>` pairs, separated by blank
lines — not a JSON schema. Local models (Ollama's llama3.1 8b, this project's
default) don't reliably follow a requested JSON structure, and this codebase already
has a direct precedent for the alternative: `doDiagram` asks for plain Mermaid text
and sanitizes it afterward (`stripCodeFences`/`quoteBracketLabels`/
`fixMalformedEdgeLabels`) rather than trusting the model's compliance. `parseFaqItems`
does the same kind of defensive regex-based parsing for the FAQ format. Unlike
`doDiagram`'s silent fallback to an empty diagram on failure, an unparseable FAQ
response throws `FaqGenerationException` (mapped to 500): an empty FAQ list would be
indistinguishable from "this document genuinely has nothing to ask," which isn't a
useful thing to fail into silently.

### An explicit, configured cap on how much of a document gets sent to the model
`rag.document-insights.max-chunks` (default 40) bounds the context — a whole document
can have far more chunks than `topK` ever retrieves for a single question. A document
over the limit is truncated to its first N chunks (document order), same "explicit,
documented limit" philosophy as `ingestion-service`'s own upload/URL-fetch size caps,
not an unbounded "send everything and hope it fits" approach.

### A separate `DocumentInsightController`, not new methods on `ChatController`
`ChatController`'s existing endpoints are all question-driven (`ChatRequest.question`
in the body); `summarize`/`faq` are document-driven — no question, just a
`documentId` path variable and an optional `model` query param. Different enough
resource shape to warrant its own controller.

### A dedicated, tighter rate-limit bucket
`rag-document-insights` (shared bucket name between `/summarize` and `/faq`, same
"don't let spreading requests across equivalent endpoints multiply the budget"
reasoning as the existing `rag-query` bucket) — a lower capacity than `rag-query`
since processing a whole document is heavier than retrieving top-K chunks for one
question.

## Consequences

### A real bug caught by this work, unrelated to the feature's own logic
`DocumentInsightController`'s first version declared
`@RequestParam(required = false) String model` and `@PathVariable String
documentId` with no explicit name. Both failed at runtime with
`IllegalArgumentException: Name for argument of type [java.lang.String] not
specified` — Spring's parameter-name resolution needs either the `-parameters`
javac flag (not enabled in this build) or an explicit name on the annotation. Every
other `@RequestParam`/`@PathVariable` already in this codebase supplies an explicit
name (`ChatController.askWithImage`, `ingestion-service`'s `DocumentController`) —
this was a real, if easy to miss, deviation from that established convention. Fixed
by adding `@RequestParam(value = "model", required = false)` /
`@PathVariable("documentId")`.

### A pre-existing test-isolation bug surfaced by adding more tests
`ChatQueryIT`'s `@BeforeEach` seeds an `"aula12.md"` document on every test method but
never cleared the shared, class-level Testcontainers Postgres instance between
methods — every test's own `vectorStore.add` calls accumulated permanently across the
whole run. This had gone unnoticed because there weren't yet enough accumulated
duplicate rows to actually crowd out a real result. Adding 5 new test methods for this
phase tipped it over: `restrictedDocumentIsInvisibleToANonOwnerNonSharedUserButVisible
ToItsOwnerAndASharedUser` (an existing, unrelated test) started failing because the
now-larger pile of duplicate `"aula12.md"` chunks pushed a genuine single-row match
out of the top-K fused result. Fixed at the root: `@BeforeEach` now runs `DELETE FROM
vector_store` before reseeding, so every test method starts from a clean slate — this
class's tests were always written to seed everything they individually need, so
nothing relied on the accumulated cross-test state that got removed.

### Verified for real against the running stack, both success and failure paths
Full `rag-service` test suite green (unit tests mocking the chat model; `ChatQueryIT`
against real Postgres/pgvector via Testcontainers). Manually verified against the
real docker-compose stack with real Ollama inference (not mocked): summarizing and
generating a FAQ for a real URL-imported document (`CONTRIBUTING.md`, ADR 0051)
produced coherent, real answers traceable to that document; an unknown `documentId`
returned 404; a transient real Ollama failure ("timed out waiting for llama-server to
start") surfaced as a 500 through the existing Resilience4j-wrapped `LlmGateway` path,
same as it would for `/ask` or `/diagrams` — not a new failure mode this phase
introduced. Verified visually in the browser (Documents view, admin document list):
clicking "Resumir"/"Gerar FAQ" renders the real generated content into a shared
result card.
