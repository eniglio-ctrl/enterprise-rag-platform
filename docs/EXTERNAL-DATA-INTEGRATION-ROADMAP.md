# External Data Integration Roadmap

> This file is the single source of truth for getting knowledge into the
> platform from somewhere other than a manual, one-file-at-a-time upload —
> a URL, a whole local folder, a cloud drive, or a database the user
> already owns — plus exposing that same data through the Model Context
> Protocol (MCP) for external clients. Same living-document convention as
> [`docs/MULTI-LLM-ORCHESTRATOR-ROADMAP.md`](MULTI-LLM-ORCHESTRATOR-ROADMAP.md)
> and [`docs/PRODUCTION-READINESS-ROADMAP.md`](PRODUCTION-READINESS-ROADMAP.md)
> — see those files if this structure looks unfamiliar. Update the status
> marker and "what was done" notes as each phase lands.
>
> **Created 2026-08-05**, planned (not implemented) in response to a direct
> request: "planeje uma forma" (plan a way), not "build it now." Every
> phase below is `⬜ Not started` — this file exists so a future session
> can pick up any one phase with full context, the same way every other
> phase in this project's history got an ADR/roadmap entry before code.
>
> **Deliberately not duplicated here**: Phase 5 (async ingestion
> infrastructure) is `docs/PRODUCTION-READINESS-ROADMAP.md`'s own Phase 3,
> and Phase 7 (a real MCP server) is `docs/MULTI-LLM-ORCHESTRATOR-ROADMAP
> .md`'s own Phase 6 — both cross-referenced below, not rewritten, so
> there is exactly one place that owns each phase's implementation detail.

## Status at a glance

| Phase | What | Status | Blocked by |
|---|---|---|---|
| 1 | Import a document from a URL | ✅ Done (2026-08-06, ADR 0051) | — |
| 2 | Import a whole local folder (client-side, multi-file) | ⬜ Not started | — |
| 3 | Batch import connector for external databases (Postgres/MySQL) | ⬜ Not started | A credential-storage decision (made below: encrypted in-app Postgres) |
| 4 | Live query tool: LLM consults an external database at answer-time | ⬜ Not started | Phase 3 (reuses its stored connections) |
| 5 | Async ingestion infra (queue + object storage + status) | ⬜ Not started, tracked in [`docs/PRODUCTION-READINESS-ROADMAP.md`](PRODUCTION-READINESS-ROADMAP.md) Phase 3 | A queue-technology decision |
| 6 | Cloud drive import (Google Drive) | ⬜ Not started | Phase 5 |
| 7 | A real MCP server exposing this platform's data/tools | ⬜ Not started, tracked in [`docs/MULTI-LLM-ORCHESTRATOR-ROADMAP.md`](MULTI-LLM-ORCHESTRATOR-ROADMAP.md) Phase 6 | Phases 3/4 landing first, for a concrete resource/tool to expose |

## Context: why these seven, in this order

The user asked for two things in one request: more flexible ways to get
*documents* in (a URL, a local folder, a cloud drive — not just one file
at a time), and integration with *external databases* the user owns,
specifically asking for all three shapes that can take — a batch import,
a live query tool the LLM calls, and a real MCP server — plus a settings
screen to type in connection parameters. Asked directly which pieces to
build and in what order (three separate clarifying questions, all
answered "all of it" except credential storage), the phases below are
sequenced by **dependency and blast radius**, not by asking again:

- Phases 1-2 need no new infrastructure and touch only existing, already-
  understood pipelines — they come first.
- Phase 3 introduces the one genuinely new piece of infrastructure this
  whole roadmap needs (storing a user-supplied credential) — it comes
  before anything that depends on it.
- Phase 4 reuses Phase 3's stored connections; it cannot come before it.
- Phase 5 is a hard prerequisite for Phase 6 specifically because a cloud
  drive fetch is exactly the kind of large/slow/rate-limited operation
  `docs/PRODUCTION-READINESS-ROADMAP.md` Phase 3 already exists to handle
  — building Phase 6 without it would mean either blocking an HTTP
  request on a slow third-party API call, or quietly re-inventing an ad
  hoc queue.
- Phase 7 (a real MCP server) is last on purpose, matching
  `docs/MULTI-LLM-ORCHESTRATOR-ROADMAP.md` Phase 6's own explicit guidance
  ("pick 1-2 concrete, genuinely useful tools first... not build generic
  MCP plumbing speculatively") — Phases 3/4 give it exactly the concrete
  resource that guidance was waiting for.

## Phase 1 — Import a document from a URL ✅

**Done (2026-08-06).** See ADR 0051 for the full design (SSRF guard by
resolved IP, no auto-redirect, read-time byte cap) and its test evidence.
`ingestion-service`'s `UploadValidationService` and
`DocumentIngestionService` were both refactored to extract a byte-array
core shared by the existing multipart path and the new URL path — none of
the downstream chunking/embedding code needed to change.

- New endpoint: `POST /api/v1/documents/from-url`, body `{"url": "..."}`.
- New, small fetch step ahead of validation: HTTP GET the URL with an
  explicit timeout and a maximum byte-count cap (reject early via
  `Content-Length` when present, and hard-stop mid-stream when absent —
  never trust a remote server's header alone), sniff the response's
  `Content-Type`, then build the same `ValidatedUpload`
  `UploadValidationService` already knows how to validate.
- No change to `DocumentIngestionService`, `DocumentReaderFactory`, or
  anything embedding/chunking-related.
- Real risk to design against, not an afterthought: this is a
  server-side-request-forgery (SSRF) surface — a URL fetch initiated by
  the server, potentially reachable to internal-network addresses
  (`localhost`, cloud metadata endpoints, other containers on the same
  docker network). The fetch must reject non-`http(s)` schemes and
  private/link-local IP ranges before connecting, not just after.

**Done when**: a real public URL (e.g. a raw GitHub file link) imports
end-to-end through the existing pipeline with a real integration test,
and a request targeting `http://localhost:8081/...` or a private IP is
rejected before any connection is attempted.

## Phase 2 — Import a whole local folder ⬜

**Not started.** `web-ui` only, no backend change. Browsers support
`<input type="file" webkitdirectory>` — selecting a folder gives a
`FileList` of every file inside it (recursively), each still a normal
`File` object. The existing upload handler
(`web-ui/app.js`'s `uploadForm` submit listener, `POST
${INGESTION_BASE}/api/v1/documents` per file) already knows how to send
one file; this phase adds a second entry point next to the existing
dropzone that loops the same call once per selected file, with its own
per-file progress/status list (reusing `#upload-history`'s existing
pattern) rather than a single opaque "uploading..." state for the whole
batch.

**Done when**: selecting a folder with a mix of supported and unsupported
file types imports every supported file, skips (with a visible reason)
every unsupported one, and the existing single-file dropzone still works
unchanged.

## Phase 3 — Batch import connector for external databases ⬜

**Not started.** New settings screen, new stored-connection concept, new
one-time-import mechanism. Modeled directly on the existing
"invite a teammate" form (`web-ui/index.html`'s `#invite-panel`/
`#invite-form`, `web-ui/app.js`'s `inviteForm` submit handler) for UI/UX
consistency — same card/form/status-div shape, same `fetch` +
`setStatus()` pattern.

- **UI**: new card in Settings, `#external-db-panel` — engine
  (`Postgres` / `MySQL`, a fixed dropdown, not free text — see security
  note below), host, port, database name, username, password, a
  **"Test connection"** button (round-trips before saving anything), and
  once saved, an **"Import"** action taking either a table name or a
  user-written `SELECT`-only query.
- **New table**, in the application's own Postgres:
  `external_connections(id, tenant_id, engine, host, port, database_name,
  username, encrypted_password, created_by, created_at)`. The password is
  encrypted at rest (AES-GCM, application-level symmetric key from an env
  var — same "read from env, never commit it" convention every other
  secret in this project already follows) and is **never** sent back to
  the frontend after creation, only a masked placeholder (`••••••••`) —
  same principle as never re-exposing a JWT signing key once set.
- **New service**, e.g. `ExternalDatabaseImportService`
  (`ingestion-service` or a new small module — decide at implementation
  time based on how much it ends up sharing with `DocumentIngestionService`):
  opens a short-lived JDBC connection using the stored, decrypted
  credential, runs the requested table/query, and converts each row into
  a synthetic "document" (a text rendering of the row plus its column
  names) that enters the **same** chunking/embedding tail end
  `DocumentIngestionService` already has — no second embedding pipeline.
- **Security decision made now, not asked**: "pluggable driver" means a
  short, code-reviewed allowlist of supported JDBC drivers bundled with
  the app (Postgres and MySQL to start) — **never** a user-supplied
  driver JAR or arbitrary JDBC URL scheme. Accepting an arbitrary driver
  from a user is a real remote-code-execution vector (a malicious driver
  class runs inside the application's own JVM), not a hypothetical one.
  Flag to the user if a more open plugin model was actually intended —
  this roadmap assumes the safe reading.
- **Also apply the same SSRF-style caution as Phase 1**: the host a user
  types in is exactly as untrusted as a URL — the connection attempt
  needs the same private/internal-network guardrails before connecting,
  or this becomes a way to port-scan the application's own internal
  network via a "test connection" button.

**Done when**: a real external Postgres and a real external MySQL
instance (e.g. throwaway Testcontainers instances in the test suite, or
manually for local verification) each connect, list-and-import a small
table end-to-end into pgvector, the stored password is genuinely
unreadable directly from the database (verified by querying the raw
column, not just trusting the code), and a connection attempt at a
private IP is rejected the same way Phase 1's URL fetch is.

## Phase 4 — Live query tool: LLM consults an external database live ⬜

**Not started. Depends on Phase 3** (reuses `external_connections`).
Modeled directly on `DocumentLookupTool`
(`rag-service/src/main/java/com/eniglio/ragplatform/rag/tool/
DocumentLookupTool.java`) — the existing precedent for "give the LLM a
callable capability": a plain `@Component` with a Spring AI `@Tool`-
annotated method, registered on the `ChatClient` prompt
(`RagQueryService.java:395-396`'s `.tools(...).toolContext(...)`), where
`tenantId`/scope comes **only** from server-side `ToolContext`, never a
model-visible parameter the LLM could tamper with (ADR 0007's tenant-
isolation principle, unchanged here).

- New tool, e.g. `ExternalDatabaseQueryTool`: given the user's question
  and a specific stored connection (resolved via `ToolContext`, same
  tenant-scoping discipline), builds and validates a **read-only** query.
  Enforcement, not just prompting the model to behave: reject any query
  containing `INSERT`/`UPDATE`/`DELETE`/`DROP`/`ALTER`/`CREATE`/`GRANT`
  (a real SQL-statement-type check, not a string-contains check the model
  could phrase around) and run it against a database user/role that is
  itself read-only wherever the target database supports that, as a
  second, independent enforcement layer.
- Response must carry a real, visible signal that this answer came from a
  live query, not indexed/embedded knowledge — same honesty principle as
  the existing "Resposta de IA pública, não verificada com seus
  documentos" badge for the public-LLM fallback (ADR 0038/0039).

**Done when**: a question the tool can answer returns a real result from
a real external database with the live-query badge visible, and a
question crafted to attempt a write (e.g. "delete the old rows and tell
me it's done") is rejected before reaching the database, verified by a
test that asserts the query was never executed, not just that the answer
text looks refused.

## Phase 5 — Async ingestion infrastructure ⬜

**This is `docs/PRODUCTION-READINESS-ROADMAP.md`'s own Phase 3** — queue
+ object storage + `PENDING`/`PROCESSING`/`READY`/`FAILED` status —
cross-referenced here as a hard prerequisite for Phase 6, not duplicated.
See that file for the full design and "done when" criteria. The reason it
matters *here*: a cloud-drive fetch (Phase 6) or a large external-database
import (Phase 3, at scale) is exactly the kind of slow, potentially-rate-
limited operation that shouldn't block an HTTP request the way today's
synchronous upload does.

## Phase 6 — Cloud drive import (Google Drive) ⬜

**Not started. Depends on Phase 5.** Google Drive chosen as the first (and
only, for now) cloud-drive provider — its picker API is the most mature
for a "let the user point at a specific file/folder without this app
needing broad, standing access to their whole drive" flow, keeping the
OAuth scope as narrow as the actual feature needs.

- OAuth app registration with Google (a real external dependency and a
  review-process cost, why this is Tier 3-weight, not Tier 2), consent
  flow, and a stored refresh token — encrypted the same way Phase 3
  encrypts a database password, not a second, separate mechanism.
- Selected file(s) are handed to Phase 5's async queue, not processed
  synchronously — a Drive API call is exactly the kind of external,
  variable-latency dependency Phase 5 exists to decouple from the HTTP
  request/response cycle.

**Done when**: a real Google account can authorize, pick a real file via
the picker, and see it appear in the knowledge base after async
processing completes, with the OAuth token never visible to the frontend
after the initial consent redirect.

**Not to be confused with** — and cross-referenced from —
[`docs/PRODUCT-DIFFERENTIATION-ROADMAP.md`](PRODUCT-DIFFERENTIATION-ROADMAP.md)
Phase 9 (federated search across SharePoint/Confluence/Drive/GitHub):
this phase *imports and indexes* a chosen file once; federated search
means querying an external system *live*, at answer-time, never copying
its content in — a materially different capability, closer in shape to
this file's own Phase 4 (live external-database query tool) than to this
phase.

## Phase 7 — A real MCP server ⬜

**This is `docs/MULTI-LLM-ORCHESTRATOR-ROADMAP.md`'s own Phase 6 ("Tools
via MCP")** — cross-referenced here, not duplicated, now with the
concrete use case that phase's own text says it was waiting for: expose
this platform's RAG retrieval and the external-database connections from
Phases 3/4 as real Model Context Protocol tools/resources, so an external
MCP client (Claude Desktop, or any other MCP-speaking tool) can connect
to this platform directly. Needs its own access-control model (who is
allowed to connect as an MCP client at all, separate from the existing
per-tenant JWT model built for this platform's own `web-ui`) and an MCP
SDK dependency not present anywhere in the codebase today. Deliberately
last: everything it would expose already needs to exist and be proven
useful on its own first.

**Done when**: an external MCP client can list and successfully call at
least one tool from Phases 3/4 (or existing RAG retrieval) against a real
running instance of this platform, respecting the same tenant isolation
every other access path already enforces.
