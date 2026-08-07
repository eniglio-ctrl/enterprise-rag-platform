# Product Differentiation Roadmap — competing with NotebookLM-style tools

> This file is the single source of truth for "what would make this project
> stand out against tools like NotebookLM" — a list the user gave directly,
> 2026-08-06. Same living-document convention as the other four roadmaps
> ([`docs/SECURITY-HARDENING-ROADMAP.md`](SECURITY-HARDENING-ROADMAP.md),
> [`docs/MULTI-LLM-ORCHESTRATOR-ROADMAP.md`](MULTI-LLM-ORCHESTRATOR-ROADMAP.md),
> [`docs/PRODUCTION-READINESS-ROADMAP.md`](PRODUCTION-READINESS-ROADMAP.md),
> [`docs/EXTERNAL-DATA-INTEGRATION-ROADMAP.md`](EXTERNAL-DATA-INTEGRATION-ROADMAP.md)).
>
> **Before adding anything, the user's original 12-item list was checked
> against the actual codebase, not assumed.** Three items are already
> fully built; three more are partially built with a specific, real gap
> remaining; six are genuinely new. Claiming a feature is missing when it
> already exists (or claiming a rougher version is the finished thing)
> would be dishonest to this project's own established ethos — every
> "already built" and "partially built" verdict below names the exact
> file/ADR that proves it, so it can be checked directly rather than
> taken on faith.
>
> **Item 13 (Audio Overview) added 2026-08-06, same day**, after a direct
> follow-up question — "will this end up equivalent to NotebookLM once
> everything above is built?" — the honest answer was no, and Audio
> Overview (a generated two-voice discussion of a document, NotebookLM's
> own flagship feature) was a concrete, named reason why: it wasn't on the
> original list at all, and is structurally the largest phase in this file.

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
| 10 | Geração automática de resumos e FAQs | ✅ Done (2026-08-07, ADR 0052) | Phase 8 |
| 11 | Busca federada (SharePoint, Confluence, Drive, GitHub) | ⬜ New, related to but distinct from an existing phase | Phase 9 |
| 12 | Controle fino de permissões por documento | ✅ Already built | — |
| 13 | Audio Overview (added 2026-08-06, follow-up) | ⬜ New — largest, most novel phase in this file | Phase 10 |

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

## Phase 8 — Automatic summaries and FAQs ✅

**Done (2026-08-07).** See ADR 0052 for the full design. Two new
`rag-service` endpoints, `POST /api/v1/documents/{documentId}/summarize`
and `.../faq`, both retrieving the document's *entire* indexed content
(`HybridSearchService.findByDocumentId`, not a top-K similarity search)
and reusing `RagQueryService`'s existing model-resolution/`ChatClient`
wiring — no new constructor dependencies needed. FAQ generation asks the
model for plain delimited text (`P:`/`R:` pairs), not JSON, parsed
defensively — the same "don't trust an LLM to produce a structured format
reliably" precedent `doDiagram`'s Mermaid post-processing already
established. `web-ui`'s Documents view (admin document list, and the
current session's own upload history) gained "Resumir"/"Gerar FAQ"
buttons rendering into a shared result card.

**Done when**: a real uploaded document produces a coherent summary and a
plausible FAQ list, both grounded in and traceable back to that specific
document. Verified with a real Ollama model against a real URL-imported
document (`CONTRIBUTING.md`, ADR 0051) end-to-end in the browser.

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

## Phase 10 — Audio Overview (generated podcast-style discussion) ⬜

**Not started. Added 2026-08-06, after a direct follow-up question about
whether this project would end up equivalent to NotebookLM once Phases 1-9
land** — the honest answer was no, partly because Audio Overview (a
generated two-voice discussion of a document's content) is NotebookLM's
own flagship feature and wasn't on the original 12-item list at all. This
is, by a real margin, the largest and most structurally new phase in this
file — every other phase reuses an existing pipeline end to end; this one
introduces a capability this project has never had in either direction.

- **What exists today, and why it isn't reusable as-is**: `AudioTranscriptionService`
  (ADR 0019) already does the opposite direction — real, local, Whisper-based
  **speech-to-text**, for transcribing an uploaded audio file into text to
  index. Audio Overview needs **text-to-speech (TTS)**, which does not
  exist anywhere in this codebase in either service. No shortcut through
  existing code here — this is genuinely new infrastructure, not a
  reuse-and-extend like Phases 1-9 mostly are.
- **Two-stage pipeline**:
  1. **Script generation** — an LLM call (reuses the existing chat model
     wiring) turns the target document(s)' content into a two-persona
     dialogue script (e.g. a "host" asking questions, an "expert"
     answering from the material) — a new prompt template, not a new
     model.
  2. **Speech synthesis** — the generated script's lines are rendered to
     actual audio, one voice per persona, then concatenated into a single
     audio file.
- **Real, undecided design fork — TTS provider**: consistent with this
  project's strong local-first precedent (Ollama for chat, Whisper for
  transcription, both self-hosted and free), a **local, self-hostable TTS
  engine is the recommended starting point** (e.g. Piper or Coqui TTS —
  both run comfortably on CPU, unlike a local LLM), over a paid cloud TTS
  API (ElevenLabs, OpenAI TTS, Google Cloud TTS) as a first choice. A
  cloud TTS provider is a legitimate *optional* addition later, mirroring
  how the Multi-LLM fallback providers sit alongside local Ollama today —
  not a replacement for the local path. This decision should be confirmed
  before implementation starts, the same way every other provider choice
  in this project's history was made explicit rather than assumed.
- **Async by necessity, not just by preference**: generating several
  minutes of two-voice audio from a real document is slow — this phase
  has a **hard dependency**, not a soft one, on
  [`docs/PRODUCTION-READINESS-ROADMAP.md`](PRODUCTION-READINESS-ROADMAP.md)
  Phase 3's async queue and object storage: the generated audio file
  itself needs somewhere durable to live and be streamed from, and
  generation must not block an HTTP request the way today's synchronous
  pipelines do.
- **`web-ui`**: a new "Audio Overview" action per document (or per a
  small selection of documents), a generating/pending state, and an audio
  player once the file is ready — new UI surface, no existing component to
  extend.

**Done when**: requesting an Audio Overview for a real uploaded document
produces an actual playable audio file with two distinguishable voices
discussing that document's real content (not generic filler), generated
asynchronously (the request returns immediately, the UI polls or is
notified when ready), using the locally-hosted TTS path with zero paid
API cost.

## Sequencing note

None of these ten phases are started, and none block each other in a
hard-dependency sense the way `docs/EXTERNAL-DATA-INTEGRATION-ROADMAP.md`'s
phases do — they're independent surface-area additions, with one
exception. Phases 1 and 5 share
`docs/PRODUCTION-READINESS-ROADMAP.md`'s async/storage work as a *soft*
dependency (worth having first, not strictly required); Phase 3 should
follow `docs/MULTI-LLM-ORCHESTRATOR-ROADMAP.md`'s own Phase 3 sequencing
once that starts, rather than forking a parallel agent design. **Phase
10 (Audio Overview) is the one real exception**: its *hard* dependency on
that same production-readiness async/storage work is a genuine blocker,
not a nice-to-have, since generated audio has nowhere durable to live and
generation cannot run synchronously in an HTTP request the way the other
nine phases' work mostly can.
