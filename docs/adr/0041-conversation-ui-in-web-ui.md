# ADR 0041: Multi-turn conversation UI in `web-ui`

## Status
Accepted

## Context
`docs/ROADMAP.md` item #15. `chat-service` (ADR 0013) has offered real
multi-turn conversation memory on top of `rag-service`'s retrieval since the
service was first built, but `README.md` said outright that it "isn't wired
into `web-ui` yet ... it's reachable today via its own API." A fully-built,
tested capability with zero visible demonstration in the one flow anyone
reviewing this project actually clicks through is a portfolio-narrative gap,
not just a technical one.

Scope, confirmed while reading `chat-service`'s existing controller
(`ConversationController`) before writing any UI code: it already exposes
everything this needed — `POST /api/v1/conversations` (create),
`POST /api/v1/conversations/{id}/messages` (send, conversation-aware),
`GET /api/v1/conversations/{id}/messages` (list history), all JWT-authenticated
via the same `platform-common` resource-server config as every other
service, CORS already configured (`chat.web-ui.allowed-origin`, matching
`docker-compose.yml`'s `WEB_UI_ORIGIN`). So this is `web-ui`-only, no new
backend design.

## Decision

### A new panel, not a redesign of "Ask"
`index.html` gained `#conversation-panel` ("3. Conversation") as a third,
independent panel alongside "1. Upload" and "2. Ask" — deliberately not
merged into the existing single-shot `/api/v1/ask` flow. "Ask" and
"Conversation" answer a genuinely different question (stateless one-off vs.
stateful multi-turn) and conflating them would have made both harder to
reason about for a reviewer skimming the UI.

### Minimal state: one active conversation at a time
`app.js` tracks a single `currentConversationId` (module-level state, same
pattern as `pendingFallbackQuestion` from ADR 0039). "Start a new
conversation" calls `POST /api/v1/conversations`, stores the returned id,
and reveals the message thread (`#conversation-thread`, hidden until a
conversation exists). No conversation list/history-of-conversations UI —
out of scope; the roadmap item asked for "create/continue a conversation,
send a message, show the running history," not a full conversation
manager.

### Rendering: reuse the existing bubble/citation conventions
Each message renders as an `.conversation-message` list item, `.role-user`
or `.role-assistant` (right/left-aligned, tinted background via the
project's established `color-mix(in srgb, var(--color-X) N%, transparent)`
pattern — same convention as `.fallback-confirm-card`/`.provenance-badge`
from ADR 0039). Assistant messages carry a `.conversation-sources` line
("Sources: a.md, b.md") built from the response's `citations` array, giving
the same at-a-glance grounding signal as the "Ask" panel's citations list,
without duplicating that panel's full citation-card UI — a one-line summary
is enough here since the thread is already dense.

### Hidden under `DEMO_MODE`, same as upload/invite
`chat-service` is explicitly not part of the public demo deployment (ADR
0020 — only `rag-service` and `web-ui` are deployed there). `renderAuthState()`
already hides `#upload-panel`/`#invite-panel` when `DEMO_MODE` is true;
`#conversation-panel` was added to that same hide list rather than inventing
a new visibility mechanism.

### Test coverage padded alongside the UI work
Scoping this surfaced that `chat-service` had real but thin test coverage
(2 test files) relative to the other services. Rather than treating this as
frontend-only, added two real, previously-untested edge cases to
`ConversationIT`:
- `listingMessagesForAnUnknownConversationReturnsNotFound` — the existing
  `unknownConversationReturnsNotFound` test only ever exercised the POST
  endpoint; a caller who never sent a message but tries to list one deserves
  the same 404, not a silent empty list.
- `sendingABlankMessageReturnsBadRequest` — `SendMessageRequest`'s
  `@NotBlank` had never been exercised by a real HTTP request; a validation
  annotation with no test proving it's enforced at the controller layer
  isn't verified, it's just typed.

Module test count: 8 → 10.

## Consequences

### Verified for real in the browser, exactly per this item's "done when"
Registered a fresh user (empty tenant), uploaded a real Markdown document
about the SAGA pattern via `ingestion-service`, then in the browser:

1. Started a new conversation — `chat-service` returned a real conversation
   id, the thread UI appeared.
2. Asked "Quais são os dois modelos principais de SAGA?" — got back
   Choreography/Orchestration, correctly grounded, with a rendered
   "Sources: saga-notes.md" line.
3. Asked the deliberately context-only follow-up "E o que mais?" — on its
   own this question is meaningless; it only resolves against "more of
   what?" from the prior turn. The answer correctly discussed
   **compensação** (a real topic from the same document, never mentioned in
   either message so far in the conversation), proving `chat-service`'s
   conversation memory — not just retrieval — was actually driving the
   answer, and that the UI was correctly forwarding conversation state
   rather than sending each message as if it were the first.

### `chat-service`'s own automated tests, run independently of the browser check
`./mvnw -pl chat-service -am verify` — all 10 tests passing (up from 8),
confirming the two new edge cases before ever touching a browser.

### Scope not taken: no conversation history list, no delete/rename
A user can start a new conversation but has no way in this UI to return to
a previous one, rename it, or delete it — `chat-service`'s API doesn't
offer a "list my conversations" endpoint either, so adding this to the UI
alone would have meant designing new backend surface, which is explicitly
out of scope for this item (`chat-service` already does everything this
needs). Worth a future, separate item if this ever matters for the
portfolio narrative — not tracked as an open gap here since it was never
promised.
