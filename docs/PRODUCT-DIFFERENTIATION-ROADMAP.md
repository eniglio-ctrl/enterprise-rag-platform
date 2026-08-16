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
> Overview (a generated two-voice discussion of a document) was a
> concrete, named reason why: it wasn't on the original list at all, and
> is structurally the largest phase in this file. The guiding framing
> since: build an Enterprise AI Knowledge Platform inspired by
> NotebookLM's best capabilities, but with a modular, multi-LLM,
> self-hosted, and extensible architecture.

## Status at a glance

| # | Item (user's list) | Verdict | Phase below |
|---|---|---|---|
| 1 | Upload por arrastar e soltar | ✅ Already built | — |
| 2 | Citações com destaque do trecho original | 🟡 Partial — snippet exists, no highlighting/viewer | Phase 1 |
| 3 | Comparação entre documentos | ✅ Done (2026-08-10, ADR 0057) | Phase 2 |
| 4 | Pesquisa híbrida (vetorial + BM25) | ✅ Already built (RRF, not literally BM25 — see note) | — |
| 5 | Agentes especializados (Jurídico, RH, Financeiro, TI) | ⬜ New, extends an existing not-started phase | Phase 3 |
| 6 | Memória por usuário e por organização | 🟡 Partial — per-conversation exists, no cross-session personalization | Phase 4 |
| 7 | Dashboards de uso e custo | ✅ Done (2026-08-10, ADR 0056) | Phase 5 |
| 8 | Versionamento de documentos | ✅ Done (2026-08-11, ADR 0058) | Phase 6 |
| 9 | OCR para PDFs digitalizados | ✅ Done (2026-08-09, ADR 0055) | Phase 7 |
| 10 | Geração automática de resumos e FAQs | ✅ Done (2026-08-07, ADR 0052) | Phase 8 |
| 11 | Busca federada (SharePoint, Confluence, Drive, GitHub) | ⬜ New, related to but distinct from an existing phase | Phase 9 |
| 12 | Controle fino de permissões por documento | ✅ Already built | — |
| 13 | Audio Overview (added 2026-08-06, follow-up) | ⬜ New — largest, most novel phase in this file | Phase 10 |
| 14 | Remover documento (added 2026-08-11, follow-up) | ⬜ New | Phase 11 |
| 15 | Compartilhamento por departamento, não só restrito/tenant inteiro (added 2026-08-11, follow-up) | ✅ Done (2026-08-11, ADR 0059) | Phase 12 |
| 16 | Múltiplos departamentos por usuário, pedido+aprovação, convite com admin (added 2026-08-11, direct follow-up to #15) | ✅ Done (2026-08-12, ADR 0060) | Phase 13 |

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
  sharing — [ADR 0047](adr/0047-tenant-admin-role.md). **Honest gap, not
  covered by "already built"** (flagged 2026-08-11, item #15): sharing is
  only ever "whole tenant" or "an explicit list of individual users" —
  there's no group/department tier in between, so restricting a document
  to "Finance" today means manually picking every current Finance user
  one at a time, and it silently drifts the moment someone joins or
  leaves that team. See Phase 12.

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

## Phase 2 — Document comparison ✅

**Done (2026-08-10).** See [ADR 0057](adr/0057-document-comparison.md) for
the full design. New `POST /api/v1/documents/compare` on
`DocumentInsightController`, reusing the exact whole-document-retrieval
pattern Phase 8's summarize/FAQ endpoints already established
(`HybridSearchService.findByDocumentId`, no top-K similarity search) —
called once per requested document, fail-closed on the first inaccessible
id. Each document enters the model's context as a `[DOCUMENTO N: nome]`
labeled block; a new prompt template asks for three sections
(concordâncias/contradições/pontos únicos por documento), each point
citing its source document inline by that same label, rather than parsing
the response into a rigid per-document Java structure. A new
`rag.document-comparison.max-documents` cap (default 5) bounds how many
documents one request can compare. `web-ui`'s Documents view gained a
compare-checkbox per document (session history and the admin document
list, mirroring the existing sharing checkboxes) and a "Compare selected"
button rendering into the same shared insight card summarize/FAQ already
use.

**Done when**: comparing two real uploaded documents on a shared topic
produces a structured comparison citing which document each point came
from, not a generic single-document-style answer. Verified: an HTTP-level
test compares two real seeded documents and asserts the response cites
both by source; a service-level test captures the actual prompt sent to
the model and confirms both documents appear as separate labeled blocks;
fail-closed behavior (comparing with one inaccessible id never calls the
model at all) and the `max-documents` cap are both covered.

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

## Phase 5 — Usage and cost dashboards ✅

**Done (2026-08-10).** See [ADR 0056](adr/0056-llm-cost-metering-for-fallback-calls.md)
for the full design. `RagQueryService.callFallbackProvider` — the one place
in this codebase where a real dollar cost is ever incurred, since every
other model call resolves to the genuinely-free local Ollama/LM Studio
providers — now captures each fallback call's real token usage (extended
`GeminiClient` to parse the Generative Language API's `usageMetadata`,
which it silently discarded before) and feeds it to a new
`CostMeteringService`, which multiplies against a configurable
per-provider price table (`FallbackProviderProperties`, illustrative public
list prices by default) and records it to Prometheus (`llm.cost.usd`,
`llm.tokens.consumed`). A new "Custo (LLM fallback)" row in the existing
Grafana dashboard shows estimated USD spend and tokens consumed by
provider.

**Done when**: a Grafana panel shows real, non-zero estimated spend for
public-LLM fallback usage over a time window, and correctly shows zero
(not a fabricated number) for purely local-Ollama usage. Verified: a
fallback call with known token usage records the exact expected cost in
`CostMeteringServiceTest`/`RagQueryServiceTest`; Gemini's free-tier default
pricing correctly yields `$0.00` while still counting real tokens; a
provider reporting no usage metadata increments a distinct
`llm.cost.usage_unavailable` counter instead of silently recording a false
`$0.00` sample (a real finding from reading Spring AI's own source: its
`Usage` defaults to zero, never `null`, so a null-check would never have
caught this).

## Phase 6 — Document versioning ✅

**Done (2026-08-11).** See [ADR 0058](adr/0058-document-versioning.md) for
the full design. `POST /api/v1/documents` gained an optional `supersedes`
query parameter — a new `DocumentVersioningService` reuses
`DocumentSharingRepository`'s existing chunk-rewrite mechanism to link the
new upload to the superseded document's `documentGroupId` and rewrite
every one of the old document's chunks to `isLatestVersion=false`, only
after checking ownership (owner or tenant ADMIN) and that the target is
still the current latest version (superseding an already-superseded
version 409s — the chain is linear, no branching). A new
`HybridSearchService.filterLatestVersion`, mirroring the existing
`filterVisible` ABAC filter exactly, keeps a normal question and a
tool-invoked lookup (`findBySource`) scoped to the latest version only,
while an exact `documentId` lookup (`findByDocumentId`, backing
summarize/FAQ/compare) still reaches any specific version directly. The
admin document list in `web-ui` shows a `v2 (atual)`/`v1` badge per
document, grouped and ordered by version, with a "Nova versão" button on
each group's current version.

**Done when**: uploading a revised file linked to a prior document shows
both as versions of one logical document, and a normal question retrieves
only the latest version by default. Verified: `DocumentIngestionIT` (new
version links to and supersedes the old one, an already-superseded
version can't be superseded again, a non-owner can't version someone
else's document) and `ChatQueryIT` (a normal question against two
versions of the same document only cites the latest one; `summarize`
still reaches a superseded version by its own `documentId`).

## Phase 7 — OCR for scanned PDFs ✅

**Done (2026-08-09).** See [ADR 0055](adr/0055-ocr-fallback-for-scanned-pdfs.md)
for the full design. `DocumentReaderFactory`'s `PDF` case now pipes Spring
AI's `PagePdfDocumentReader` output through a new
`ScannedPageVisionFallbackService`, which detects two distinct gaps — a page
Spring AI silently dropped entirely (a real scanned page, detected via an
independent PDFBox page-count check) and a page whose extracted text is too
short to be real content — and fills both by rendering the page as an image
and reusing the existing vision pipeline (`ImageDescriptionService`, ADR
0018), the same call path standalone image uploads already use. A new
`ingestion.pdf-ocr.max-ocr-pages` cap (default 20) bounds how many pages of
one upload get this synchronous, slower treatment, matching the same
explicit-limit philosophy already used for the DOCX zip-bomb and URL-import
byte caps.

**Done when**: a genuinely scanned (image-only) PDF produces real,
searchable extracted text instead of an empty or near-empty document, and
a normal text-layer PDF is unaffected (no regression, no unnecessary
vision-model calls for pages that already have real text). Verified: the
previously-silent `pageCount: 0, chunkCount: 0` case for a blank-page PDF
fixture now returns real non-zero counts
(`DocumentIngestionIT.uploadingARealPdfIngestsItIntoTheVectorStore`), and a
dedicated test confirms a PDF with only real-text pages never calls the
vision model at all.

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
generated two-voice discussion of a document's content) wasn't on the
original 12-item list at all. The guiding framing since: build an
Enterprise AI Knowledge Platform inspired by NotebookLM's best
capabilities, but with a modular, multi-LLM, self-hosted, and extensible
architecture. This is, by a real margin, the largest and most structurally
new phase in this file — every other phase reuses an existing pipeline end
to end; this one introduces a capability this project has never had in
either direction.

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

## Phase 11 — Document deletion ⬜

**Not started. Added 2026-08-11**, a direct follow-up request ("posso
querer remover um documento"). Confirmed by grep, not assumed: there is
no `@DeleteMapping` anywhere in `ingestion-service` and no `DELETE FROM
vector_store` outside test cleanup code — every row in `vector_store` is
insert-only via `VectorStoreGateway.add()`, with exactly one existing
exception, `DocumentSharingRepository.updateMetadata` (an `UPDATE`, not
a `DELETE`, used by sharing changes and by version-superseding, ADR
0058). A document can be superseded but never actually removed today.

**Design**:
- New `DELETE /api/v1/documents/{documentId}` on `DocumentController`,
  same "owner or tenant ADMIN" ownership check every other mutating
  document endpoint already enforces (`DocumentSharingService
  .updateSharing`, `DocumentVersioningService.ingestNewVersion`) —
  reuses `NotDocumentOwnerException` (already generalized to take an
  `action` string this session) with `action = "delete it"`.
- A real hard delete (`DELETE FROM vector_store WHERE metadata->>
  'documentId' = ? AND tenant_id = ?`), not a soft-delete flag — nothing
  else in this codebase has a "trash"/undo concept, and inventing one
  just for this would add real complexity (a new visibility state every
  retrieval path has to know to exclude) nobody has asked for.
- **Real open question, versioning interaction**: deleting a document
  that's the *latest* version of a group (ADR 0058) leaves that group
  with no `isLatestVersion: true` member, which `HybridSearchService
  .filterLatestVersion` doesn't define behavior for today. Recommended
  default: block deleting a document that is the latest version of a
  group with older versions beneath it (409, same shape as
  `NotLatestVersionException`) — forces deleting from the newest version
  backward, simpler and more explicit than silently auto-promoting the
  next-most-recent version to latest. Deleting a non-latest (already-
  superseded) version, or a document that was never versioned at all,
  needs no special handling.
- **`web-ui`**: the per-row "⋮" menu added this session (session upload
  list and the admin "Gerenciar permissões" list, both via `rowMenuHtml`/
  `wireRowMenu` in `app.js`) already has exactly the right shape for a
  second action — add "Excluir documento" there, gated to the document's
  owner or a tenant ADMIN the same way the "Nova versão" button is
  already gated to `doc.isLatestVersion`. Needs a confirmation step
  before firing (an irreversible action shouldn't fire on the first
  click) — a native `confirm()` is enough, no new component required.

**Done when**: deleting a document the caller owns (or, as tenant ADMIN,
any document in the tenant) removes every one of its chunks from
`vector_store`, it disappears from both `GET /api/v1/documents` and
normal retrieval immediately, and a non-owner/non-admin gets 403.

## Phase 12 — Department-based document sharing ✅

**Done (2026-08-11).** See [ADR 0059](adr/0059-department-based-sharing.md)
for the full design. A new admin-created `departments` registry
(`auth-service`, name-only, no rename/delete — exactly what was asked
for) backs a new `department` field on `User` (JWT claim, same pattern
as `role` but defaulting to `null`, not `MEMBER`, when absent) and a new
`sharedWithDepartments` metadata key alongside the existing `sharedWith`
in `DocumentVisibility` — additive, not a replacement: a document can be
shared with specific users *and* whole departments at once. The
department check threads through every retrieval path
(`HybridSearchService.search`/`findBySource`/`findByDocumentId`, all of
`RagQueryService`'s public methods, `DocumentLookupTool` via
`ToolContext`) the same uniform way `userId` already does, per ADR
0046's own "enforced uniformly across every retrieval path" principle.
`web-ui` extends the two existing admin surfaces rather than adding a
new page: a "Departments" card in Settings (create + list), a
department `<select>` per row in the Team list, and a second checklist
in the Documents sharing UI next to the existing per-user one.

**Done when**: a tenant ADMIN assigns two users to a "Financeiro"
department, shares a document with that department (not with either user
individually), and both can retrieve it while a third user in a
different (or no) department cannot. Verified: `AuthIT` (create/list
departments, duplicate name rejected case-insensitively, non-admin
blocked, assign/clear a teammate's department, unknown department name
404s, department names isolated per tenant), `DocumentVisibilityTest`
(department-shared document visible only to a caller in that
department), `DocumentLookupToolTest` (department reaches the tool only
via server-side `ToolContext`), `ChatQueryIT` (a real HTTP round trip:
document restricted to "Financeiro" invisible to a user with no
department and to one in "TI," visible to one in "Financeiro") — 271
tests green across all four touched modules, no regressions. Manual
verification against the real running stack: created a department,
assigned two users to it, restricted a document to that department, and
confirmed both could ask a question and retrieve it while a third,
unassigned user could not.

**Superseded by Phase 13** (below) for the single-department-per-user and
admin-only-direct-assign parts specifically — the department *sharing*
mechanism itself (`sharedWithDepartments` on a document, the intersection
check in `DocumentVisibility`) is unchanged.

## Phase 13 — Multi-department membership, self-service requests, invite-time role grant ✅

**Done (2026-08-12).** See [ADR 0060](adr/0060-multi-department-membership-and-approval.md)
for the full design. Direct evolution of Phase 12, requested right after
trying it live: a user can now belong to **several** departments at once
(`user_departments` join table with `PENDING`/`APPROVED` status, replacing
the single `users.department` column), joining one goes through a
**request + admin-approval** step rather than being immediate — requestable
both at registration (a new checkbox picker on the register form, fetched
from a token-scoped, unauthenticated endpoint) and afterward, self-service,
from a new "My departments" Settings card any member can use. A rejected
request is a plain delete, no history kept, by explicit request. Separately,
the tenant's bootstrap admin can now grant the ADMIN role directly when
creating an invitation (`role: "ADMIN"` on `POST /invitations`, restricted
to callers who are already admins — a plain-member invitation stays open to
anyone, preserving ADR 0031's flat model). The JWT `department` claim
(nullable string) became `departments` (always-present array), and the same
"every retrieval path, no shortcuts" rethread ADR 0059 already established
was repeated end to end in `rag-service` for the new type.

**Done when**: a user can hold two departments simultaneously; a
self-requested department is invisible to retrieval until an admin
approves it and the user logs in again; an admin can revoke one specific
department from a user without touching their others; an invitation
created with `role: "ADMIN"` by a non-admin is rejected, by an admin
succeeds. Verified: `AuthIT` (10 new cases covering invite-role-grant,
pending/approve/reject, self-service request, idempotent re-request,
unknown-department 404, `GET /departments` now open to members, own
profile), `DocumentVisibilityTest` (multi-department intersection),
`ChatQueryIT` (a user in two departments still sees a document shared
with only one of them), full suite green across all four modules,
no regressions. Manual verification against the real running stack (see
below).

## Sequencing note

None of the not-started phases below block each other in a
hard-dependency sense the way `docs/EXTERNAL-DATA-INTEGRATION-ROADMAP.md`'s
phases do — they're independent surface-area additions, with two
exceptions. Phases 1 and 5 share
`docs/PRODUCTION-READINESS-ROADMAP.md`'s async/storage work as a *soft*
dependency (worth having first, not strictly required); Phase 3 should
follow `docs/MULTI-LLM-ORCHESTRATOR-ROADMAP.md`'s own Phase 3 sequencing
once that starts, rather than forking a parallel agent design. **Phase
10 (Audio Overview) is the one real hard-dependency exception**: its
dependency on that same production-readiness async/storage work is a
genuine blocker, not a nice-to-have, since generated audio has nowhere
durable to live and generation cannot run synchronously in an HTTP
request the way most other phases' work can.

**Phases 11/12 (added 2026-08-11)**: Phase 11 (document deletion) is
fully self-contained, no different from how Phases 2/5/6/7/8 (already
landed) or the External Data Integration roadmap's own Phase 2
(folder import, also just landed) worked — a good next candidate by the
same "no blocker listed" criterion those used. **Phase 12 (department
sharing) is done** (ADR 0059) — it turned out to be the first phase in
this file that wasn't self-contained to a single service (touched
`auth-service` for the new field/claim, `platform-common` for
`DocumentVisibility`/`JwtClaims`, `ingestion-service` for the write side,
and `rag-service` for the read side), but still needed no new infra and
no external account, so it wasn't blocked the way Phases 1/5/9/10 are —
just larger in surface area. Landing it before Phase 3 (specialized
domain agents) was deliberate, since Phase 3 already assumes this
primitive exists. **Phase 13 (multi-department + approval + invite-time
role grant) is done** too, landed the day after Phase 12 as a direct
follow-up once the user tried it live — same four-module surface area,
no new infra needed there either.
