# ADR 0057: Document comparison

## Status
Accepted

## Context
Given two or more documents already indexed in a tenant's knowledge base,
generate a structured comparison (agreements, contradictions, unique points
per document) instead of a single grounded answer over top-K retrieval.
The pattern to reuse already exists and is proven:
`HybridSearchService.findByDocumentId` (added for per-document
summaries/FAQs, ADR 0052/Phase 8) retrieves a single document's entire
indexed content, in order, with no groundedness check needed — the
document itself is the context, so it can't fail to "contain" an answer
the way a top-K similarity result might. Comparison only needs to call
this once per document and concatenate the results with a per-document
label in the combined context sent to the model.

## Decision

### Reuses the summarize/FAQ flow exactly, not a new pattern
`RagQueryService.doSummarize`/`doGenerateFaq` already establish the whole
shape: `findByDocumentId` → 404 (`DocumentNotFoundException`) if empty →
`buildWholeDocumentContext` (truncates at `rag.document-insights.max-chunks`,
already existing) → `resolveModel`/`clientFor`/`callLlm`/`modelOptions`,
no public-LLM fallback (same scope as `diagram()` — local providers only).
`compareDocuments`/`doCompareDocuments` repeats this once per
`documentId`, fail-closed: the first inaccessible id (nonexistent or
restricted to another user) throws 404 for the whole request rather than
silently comparing a subset — otherwise a caller could infer a restricted
document's existence by noticing it quietly dropped out of the result.

### Context labeled per document
Each document enters the combined context as a labeled block:
```
[DOCUMENTO 1: aula12.md]
<content, truncated at max-chunks>

[DOCUMENTO 2: 2pc.md]
<content, truncated at max-chunks>
```
A new `COMPARISON_SYSTEM_TEMPLATE` (same Portuguese-language convention as
every other prompt in this file) asks for exactly three sections
("CONCORDÂNCIAS", "CONTRADIÇÕES", "PONTOS ÚNICOS POR DOCUMENTO"), each
point citing the document it came from by its `[DOCUMENTO N: nome]` label
— the same bracket-citation convention `SYSTEM_TEMPLATE` already uses for
chunks, just at the document level instead.

### Structured prose, not a DTO parsed per document
Unlike FAQ generation (which must become a list of Q&A items for the UI to
render one at a time, hence `parseFaqItems`'s defensive regex parsing),
comparison is closer to summarization: free text the UI only needs to
display. A rigid parser splitting "agreements vs. contradictions vs.
N-documents'-worth of unique points" would scale worse and be more
fragile against a local model than FAQ's already-defensive single-document
parsing. The roadmap's "structured comparison citing which document each
point came from" requirement is satisfied by the model's own inline
`[DOCUMENTO N: nome]` citations, which the user reads directly — no
Java-side parsing needed. `ComparisonResponse` therefore has no
per-document sub-DTOs, mirroring `SummaryResponse`'s lack of a `citations`
list for the same underlying reason.

### Explicit cap on document count, separate axis from `max-chunks`
`RagProperties.DocumentComparison(int maxDocuments)`, default 5
(`application.yml`). `max-chunks` already bounds each document's own
content (depth); this bounds how many documents can be compared in one
request (breadth) — same "explicit, documented limit" philosophy as every
other cap in this codebase. Exceeding it throws a new
`TooManyDocumentsToCompareException` → 400, registered in
`GlobalExceptionHandler` the same way `DocumentNotFoundException`/
`FaqGenerationException` already are. The minimum of 2 documents is
enforced at the request boundary instead (`@NotEmpty @Size(min = 2)` on
`ComparisonRequest.documentIds`) since it's a fixed rule, not a
configurable one.

### New endpoint on the existing controller
`POST /api/v1/documents/compare`, body `ComparisonRequest(List<String>
documentIds, String model)` — a request body rather than a path variable
because it's 2+ ids, but the same `DocumentInsightController` (still
document-driven, not question-driven). Its own, tighter rate-limit bucket
(`rag-document-comparison`, 5/min vs. `rag-document-insights`'s 10/min) —
comparing processes 2+ whole documents in one call, heavier than
summarizing or FAQ-ing a single one.

### web-ui: reuses the existing checkbox pattern, not new UI conventions
`renderAdminDocuments` already renders a checkbox per list item for
sharing; both it and `addHistoryEntry` already render summarize/FAQ
buttons per item. A `.compare-checkbox` was added to both, plus one
"Compare selected" button per list (session history and admin document
list each read only their own checked boxes — no cross-list selection).
`#insight-card` gained a third mutually-exclusive child
(`#insight-comparison`, same pattern as `#insight-summary`) rather than a
dedicated card, keeping the single shared result-card convention already
established.

## Consequences

### Verified
- Full `./mvnw -pl rag-service test` suite green (the one pre-existing
  failure, `RagQualityBenchmark`'s real-Ollama quality-score threshold, is
  unrelated to this phase).
- `RagQueryServiceTest`: comparing two documents produces a combined,
  per-document-labeled context (asserted directly against the captured
  prompt); comparing fails closed on the first inaccessible id without
  ever calling the model; exceeding `max-documents` throws before any
  retrieval happens at all.
- `ChatQueryIT`: full HTTP-level coverage — a real two-document comparison,
  404 for an unknown id, 400 for fewer than 2 ids (Bean Validation) and
  for more than `max-documents` (runtime check).

### No fallback, same scope as summarize/FAQ/diagram
Comparison never offers the public-LLM fallback — consistent with every
other document-driven (non-question) endpoint in this codebase.
