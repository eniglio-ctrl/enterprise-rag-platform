# ADR 0035: Native tool/function calling (Spring AI `@Tool`)

## Status
Accepted

## Context
`docs/MULTI-LLM-ORCHESTRATOR-ROADMAP.md` Phase 9, Tier 1 item #7 of `docs/ROADMAP.md`.
A real, distinct gap from the roadmap's own MCP phase (Phase 6): Spring AI supports
the chat model directly invoking a Java method mid-completion (`@Tool`-annotated
methods) — no external protocol, no separate tool server, just a method on an
existing `@Component` the model can choose to call. The roadmap's own framing is
that this is a cheaper, natural stepping stone before MCP, not a replacement for it.

The plan's suggested first tool was `lookupDocumentById(String documentId)`. Refined
during design: a chat user never sees or types the internal UUID `documentId` this
project generates per upload (`DocumentIngestionService`) — the thing a real question
would actually name is the **filename** (`source`, already shown in every citation).
Implemented as a lookup by exact source filename instead, which is what a question
like "resuma o relatório X.md" actually gives the model to work with.

## Decision

### `DocumentLookupTool.lookupDocumentBySource`
- A new `@Component` in `rag-service` with one `@Tool`-annotated method: given an
  exact source filename, returns the full, concatenated text of every chunk
  belonging to that document (ordered by `chunkIndex`) — not a similarity-ranked
  partial view. Backed by a new `HybridSearchService.findBySource(source, tenantId)`,
  an **exact**, tenant-scoped SQL lookup against `vector_store`, distinct from
  `HybridSearchService.search` (similarity-ranked, never exact-match).
- **`tenantId` comes from `ToolContext`, never from a model-controlled parameter.**
  This is the one security-critical design decision in this phase: the `@Tool`-visible
  method signature only exposes `source` — if `tenantId` were a second `@ToolParam`,
  the model (or a crafted prompt) could ask the tool to fetch a different tenant's
  document by supplying an arbitrary tenant id, defeating the per-tenant isolation
  contract every other retrieval path in this project enforces (ADR 0007). `Ragquery
  Service.answer()`'s single `.tools(documentLookupTool).toolContext(Map.of("tenant
  Id", tenantId))` call site is the only place that ever sets it, always from the
  already-authenticated caller's own JWT-derived tenant.
- Registered only on the main text-answer generation call in `doAnswer` — not on
  routing, diagram generation, or the groundedness/context-relevance checks, none of
  which benefit from a whole-document fetch mid-call.

## Consequences

### Verified for real, per this phase's own "done when" criterion
The roadmap explicitly required proof "confirmed via a log line... not just inferred
from the answer's content" — a real end-to-end test was run against the live local
stack, not just the mocked unit tests:

1. Registered a real user (auto-created tenant, Security Phase 4's flow).
2. Uploaded a short real document (`report-2026.md`, a fabricated quarterly report
   with facts — sales growth, a named internal project — not present anywhere else
   in the corpus) through the real `ingestion-service`.
3. Asked `POST /api/v1/ask`: *"Resuma o documento report-2026.md"* — deliberately
   phrased to match the tool's own description (a request to summarize a document
   named by filename), against the real local `llama3.1` via Ollama.
4. **Confirmed via the actual server log**, not inferred from the answer:
   ```
   Tool lookupDocumentBySource invoked: source=report-2026.md tenantId=<real-uuid> chunksFound=1
   ```
   The `tenantId` logged is the caller's real, auto-generated tenant UUID — direct
   proof `ToolContext` correctly carried the authenticated caller's own tenant into
   the tool call, not a hardcoded or model-supplied value.
5. The final answer correctly summarized all four sections of the real document
   (sales, expenses, the named internal project and its completion percentage,
   conclusion) — the tool's return value demonstrably shaped the generated answer,
   the second half of the roadmap's "done when" requirement.

### A real, honest limitation: tool use isn't reflected in citations
The response's `citations` array is still built solely from `HybridSearchService
.search`'s originally-retrieved chunks (unchanged code path) — a chunk the model saw
only because `DocumentLookupTool` fetched it never gets its own citation entry. A
caller reading the citations list has no way to tell the answer was partly grounded
in a tool-fetched document rather than only what similarity search found. Not fixed
in this phase — plumbing tool results into the citation-building path is a real,
separate change (the citation builder currently only ever sees the pre-generation
`retrieved` list, not anything a mid-call tool invocation added), left as a concrete,
named follow-up rather than silently accepted as invisible scope creep.

### Verified with automated tests too, not just the one live run
- `DocumentLookupToolTest` (3 tests, mocked `HybridSearchService`): concatenation
  order, the empty-result message, and — the security-critical one — that `tenantId`
  passed to `HybridSearchService.findBySource` comes from `ToolContext`, verified by
  asserting the exact mock interaction, not just the return value.
- `RagQueryServiceTest`/`ChatQueryIT` both still pass unchanged (22 and 9 tests
  respectively) — registering a tool on the prompt call doesn't disturb either the
  unit-level mocked `ChatModel` interactions or the Spring-context integration test,
  confirmed by actually running both, not assumed from reading the Spring AI API.
- `./mvnw clean verify` green across all 5 modules.

### Scope: one tool, not a general tool-calling framework
Only one tool was added, matching the roadmap's own "recommended first tool"
framing — this phase doesn't build a generic tool-registration mechanism or expose
tool-calling to `chat-service`/diagram generation. Phase 10 (reframing agents around
capability) and Phase 6 (MCP) are the roadmap's own named next steps once more than
one real tool exists to justify a broader abstraction.
