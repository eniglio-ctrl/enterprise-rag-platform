# Product Differentiation Roadmap — competing with NotebookLM-style tools

> This file is the single source of truth for "what would make this project
> stand out against tools like NotebookLM" — a list the user gave directly,
> 2026-08-06. Same living-document convention as the other four roadmaps
> ([`docs/SECURITY-HARDENING-ROADMAP.md`](SECURITY-HARDENING-ROADMAP.md),
> [`docs/MULTI-LLM-ORCHESTRATOR-ROADMAP.md`](MULTI-LLM-ORCHESTRATOR-ROADMAP.md),
> [`docs/PRODUCTION-READINESS-ROADMAP.md`](PRODUCTION-READINESS-ROADMAP.md),
> [`docs/EXTERNAL-DATA-INTEGRATION-ROADMAP.md`](EXTERNAL-DATA-INTEGRATION-ROADMAP.md)).
>
> **Before adding anything, the 12-item list was checked against the actual
> codebase, not assumed.** Three items are already fully built; three more
> are partially built with a specific, real gap remaining; six are
> genuinely new. Claiming a feature is missing when it already exists (or
> claiming a rougher version is the finished thing) would be dishonest to
> this project's own established ethos — every "already built" and
> "partially built" verdict below names the exact file/ADR that proves it,
> so it can be checked directly rather than taken on faith.

## Status at a glance

| # | Item (user's list) | Verdict | Phase below |
|---|---|---|---|
| 1 | Upload por arrastar e soltar | ✅ Already built | — |
| 2 | Citações com destaque do trecho original | 🟡 Partial — snippet exists, no highlighting/viewer | Phase 1 |
| 3 | Comparação entre documentos | ⬜ New | Phase 2 |
| 4 | Pesquisa híbrida (vetorial + BM25) | ✅ Already built (RRF, not literally BM25 — see note) | — |
| 5 | Agentes especializados (Jurídico, RH, Financeiro, TI) | ⬜ New, extends an existing not-started phase | Phase 3 |
| 6 | Memória por usuário e por organização | 🟡 Partial — per-conversation exists, no cross-session personalization | Phase 4 |
| 7 | Dashboards de uso e custo | 🟡 Partial — usage metrics exist, no $-cost tracking | Phase 5 |
| 8 | Versionamento de documentos | ⬜ New | Phase 6 |
| 9 | OCR para PDFs digitalizados | ⬜ New | Phase 7 |
| 10 | Geração automática de resumos e FAQs | ⬜ New | Phase 8 |
| 11 | Busca federada (SharePoint, Confluence, Drive, GitHub) | ⬜ New, related to but distinct from an existing phase | Phase 9 |
| 12 | Controle fino de permissões por documento | ✅ Already built | — |

## Already built — no new work needed

- **#1, drag-and-drop upload**: `web-ui/index.html`'s `#dropzone` +
  `web-ui/app.js`'s `dragover`/`dragleave`/`drop` handlers. Live since the
  original `web-ui` build.
- **#4, hybrid search**: `HybridSearchService` combines pgvector cosine
  similarity with Postgres full-text search via Reciprocal Rank Fusion —
  [ADR 0012](adr/0012-hybrid-search-rrf-llm-rerank.md). **One honest
  correction to the user's own wording**: Postgres full-text search ranks
  with `ts_rank`, a different formula from BM25 (no term-frequency
  saturation curve or document-length normalization the way BM25 defines
  them) — same spirit (lexical/keyword leg alongside a vector leg), not
  the identical algorithm. Worth a one-line doc fix if "BM25" specifically
  (not just "a keyword-search leg") was a hard requirement.
- **#12, fine-grained per-document permissions**: owner + tenant-wide-
  visible/restricted + explicit per-user sharing list, enforced uniformly
  across every retrieval path (vector leg, full-text leg, and
  `DocumentLookupTool`) — [ADR 0046](adr/0046-resource-level-authorization-abac.md),
  extended with a tenant `ADMIN` role that can manage any document's
  sharing — [ADR 0047](adr/0047-tenant-admin-role.md).

## Phase 1 — Citation highlighting / source viewer ⬜

**Not started.** Today's citation (`renderCitations` in `web-ui/app.js`,
`Citation` DTO in `rag-service`) already shows source filename, chunk
index, relevance score, and a plain-text snippet of the matched chunk —
real data, not decoration. What's missing is the NotebookLM-style
experience of *opening the original document and seeing the exact passage
highlighted in place*. This needs a document viewer that doesn't exist
yet (today a citation is metadata + a text snippet, never the original
file rendered back to the user) — realistically requires the original
file bytes to be retrievable (relates to
[`docs/PRODUCTION-READINESS-ROADMAP.md`](PRODUCTION-READINESS-ROADMAP.md)
Phase 3's object storage, since today's pipeline only persists derived
chunks, not the original bytes, for most file types).

**Done when**: clicking a citation opens the source document and scrolls
to / visually highlights the cited passage, for at least PDF and
Markdown/text sources.

## Phase 2 — Document comparison ⬜

**Not started.** A new capability, not an extension of `/api/v1/ask`: given
two (or more) documents already in the tenant's knowledge base, generate a
structured comparison (agreements, contradictions, unique points per
document) rather than a single grounded answer. Reuses retrieval and the
existing chat model wiring; needs a new prompt template and a new response
shape (per-document sections, not a flat answer + citations list) and a
new `web-ui` view to pick 2+ documents and trigger it.

**Done when**: comparing two real uploaded documents on a shared topic
produces a structured comparison citing which document each point came
from, not a generic single-document-style answer.

## Phase 3 — Specialized domain agents (Legal / HR / Finance / IT) ⬜

**Not started — extends `docs/MULTI-LLM-ORCHESTRATOR-ROADMAP.md`'s own
Phase 3 (`PlannerAgent`, not started), rather than being a competing,
separate agent concept.** That phase already anticipated "decides which
specialist handles a request" in the abstract; this gives it the concrete
shape the user is actually asking for: a small set of named domain
personas (starting with the four listed), each a distinct system prompt/
tool configuration over the *same* underlying knowledge base and
retrieval pipeline — not a separate model or a separate index per domain.
Realistically also benefits from **#5's own resource-level authorization**
already in place: a domain agent scoped to "Financeiro" naturally
constrains which documents it should even consider, which the existing
sharing/visibility model can express without new plumbing.

**Done when**: selecting a domain agent changes both the system prompt/
tone and (where sharing metadata supports it) which documents are
eligible to ground the answer, verified with two agents against the same
tenant producing visibly different framing for the same underlying facts.

## Phase 4 — Cross-session personalization memory ⬜

**Not started.** `chat-service`'s conversation memory
(`ConversationService.createConversation(tenantId, userId)`, ADR 0013) is
already scoped per user *and* per tenant — "memória por organização" (tenant
isolation) and "memória por usuário" (a conversation belongs to exactly one
user) both already exist in that sense. The real gap is memory that
survives *across* separate conversations — e.g. a preference or fact
stated once ("respond in formal Portuguese," "I work in the Legal
department") that a brand-new conversation should still know, which
today's per-conversation `ChatMemory` does not provide. This is the
concrete case `docs/MULTI-LLM-ORCHESTRATOR-ROADMAP.md`'s own Phase 5
(long-term memory beyond pgvector, e.g. Redis — not started, blocked on
"a concrete `what does this add` answer") was waiting for — cross-
referenced here as its answer, not a competing new store.

**Done when**: a fact or preference stated in one conversation is
available to a brand-new conversation started later by the same user, and
is never visible to a different user in the same tenant.

## Phase 5 — Usage and cost dashboards ⬜

**Not started.** Prometheus + Grafana already exist (Fase 6 of the
original roadmap) with real usage counters (documents ingested, diagrams
generated, messages exchanged, etc.) — confirmed no metric anywhere today
converts an LLM call into an actual cost figure (checked directly: no
`cost`/`tokenUsage` tracking exists in `rag-service` or `chat-service`
beyond prose comments about latency "cost," not money). Adding real
$-cost needs, at minimum, capturing token counts per call (available from
most chat model responses' usage metadata) and a per-provider price table
(local Ollama is genuinely free; the public-LLM fallback providers are
not) to multiply against — plus a new Grafana panel/row, not a new
observability stack.

**Done when**: a Grafana panel shows real, non-zero estimated spend for
public-LLM fallback usage over a time window, and correctly shows zero
(not a fabricated number) for purely local-Ollama usage.

## Phase 6 — Document versioning ⬜

**Not started.** Today, re-uploading a file with the same name creates an
entirely new, unrelated `documentId` (`DocumentIngestionService.ingest`
always mints a fresh UUID) — there is no concept of "this is a new version
of that existing document." Needs a new relationship (e.g. a
`supersedes`/`documentGroupId` metadata field) so the UI can show version
history and retrieval can default to the latest version while still
allowing an explicit "ask against version N" query.

**Done when**: uploading a revised file linked to a prior document shows
both as versions of one logical document, and a normal question retrieves
only the latest version by default.

## Phase 7 — OCR for scanned PDFs ⬜

**Not started.** `DocumentReaderFactory` reads PDFs via Spring AI's
`PagePdfDocumentReader`, which extracts the PDF's existing text layer — a
scanned PDF (image-only pages, no embedded text) yields empty or
near-empty extracted text today, silently producing a near-useless
document rather than a clear error. The project already has a working
vision-model pipeline for standalone image uploads
(`ImageDescriptionService`, ADR 0018) — the natural design is detecting a
PDF page with no extractable text and routing that page's rendered image
through the same vision-model path, rather than building a separate OCR
engine from scratch.

**Done when**: a genuinely scanned (image-only) PDF produces real,
searchable extracted text instead of an empty or near-empty document, and
a normal text-layer PDF is unaffected (no regression, no unnecessary
vision-model calls for pages that already have real text).

## Phase 8 — Automatic summaries and FAQs ⬜

**Not started.** No summarization or FAQ-generation endpoint exists today
— `/api/v1/ask` answers a specific question, `/api/v1/diagrams` draws an
architecture/flow; neither produces a standalone summary or a FAQ list for
a document on its own. New, small addition: a per-document "Summarize" and
"Generate FAQ" action, reusing the existing chat model wiring and the
same context-building already used for grounded answers, with its own
prompt templates and response shape (a summary paragraph, or a list of
Q/A pairs) rather than the citation-heavy answer format.

**Done when**: a real uploaded document produces a coherent summary and a
plausible FAQ list, both grounded in and traceable back to that specific
document.

## Phase 9 — Federated search (SharePoint, Confluence, Google Drive, GitHub) ⬜

**Not started — related to, but a distinct capability from,
[`docs/EXTERNAL-DATA-INTEGRATION-ROADMAP.md`](EXTERNAL-DATA-INTEGRATION-ROADMAP.md)
Phase 6 (Google Drive import).** That phase *imports and indexes* a
user-selected file into this platform's own knowledge base — a one-time
(or re-triggered) copy. **Federated search**, as the term is normally
used, means querying the external system *live*, at answer-time, without
ever copying/indexing its content — closer in shape to that same
roadmap's Phase 4 (live external-database query tool) than to its import
phases. Four different external systems, each with its own auth model and
API shape (SharePoint/Confluence via Microsoft/Atlassian OAuth and their
respective search APIs, Google Drive via the OAuth already planned for
import, GitHub via its own API/token) — realistically needs its own
per-provider scoping decision (which one first, matching how the external
database work started with Postgres only) rather than building all four
at once.

**Done when**: at least one of the four systems can be queried live at
question-time, with the answer clearly marked as coming from that live
external source rather than this platform's own indexed knowledge base —
same honesty principle as the existing public-LLM-fallback provenance
badge.

## Sequencing note

None of these nine phases are started, and none block each other in a
hard-dependency sense the way `docs/EXTERNAL-DATA-INTEGRATION-ROADMAP.md`'s
phases do — they're independent surface-area additions. Phases 1 and 5
share `docs/PRODUCTION-READINESS-ROADMAP.md`'s async/storage work as a
soft dependency (worth having first, not strictly required); Phase 3
should follow `docs/MULTI-LLM-ORCHESTRATOR-ROADMAP.md`'s own Phase 3
sequencing once that starts, rather than forking a parallel agent design.
