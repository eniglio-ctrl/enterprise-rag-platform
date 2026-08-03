# ADR 0039: `web-ui` confirmation dialog + provenance badge (Multi-LLM Phase 2d)

## Status
Accepted

## Context
`docs/MULTI-LLM-ORCHESTRATOR-ROADMAP.md` Phase 2d. Depends on Phase 2c (ADR
0038, the backend's `fallbackAvailable`/`source` contract). This phase is
`web-ui`-only: give a real user the two-step confirm flow and a visible,
unmistakable signal when an answer wasn't grounded in their own documents.

## Decision

### A confirmation card, not a browser `confirm()` dialog
`index.html` gained `#fallback-confirm-card` (hidden by default), shown
whenever a response comes back with `fallbackAvailable: true`, using the
roadmap's own suggested copy verbatim: *"Não encontrei uma resposta nos seus
documentos. Buscar em um modelo de IA pública (OpenAI/Gemini)? A resposta não
será baseada nos seus documentos, e isso usa uma API paga/com limite de
uso."* Two buttons: "Buscar em IA pública" (confirm) and "Cancelar". A native
`confirm()` was deliberately not used — it can't be styled, and this warning
needs to read clearly next to the app's own visual language, not look like a
browser chrome dialog a user might reflexively dismiss.

### `performAsk`: one function, three call sites
`app.js`'s original `askForm` submit handler inlined the whole fetch +
response-branching logic. Extracted into `performAsk({ question, model,
attachedImage, useFallback })` so the same logic serves: the initial form
submit (`useFallback: false`), the confirm button (`useFallback: true`,
re-sending the exact question the user already typed — `pendingFallbackQuestion`
holds it between the offer and the confirm click), and — implicitly — a normal
answer or a diagram, all still going through the one function. `attachedImage`
is always `null` on the confirm path: `rag-service` never offers the fallback
at all when an image is attached (ADR 0038), so there is never anything to
resend there.

### Provenance badge: reads `source` directly, never inferred
`#answer-provenance-badge` inside `#answer-card`, hidden unless
`source === "public-llm"`: *"⚠️ Resposta de IA pública, não verificada com
seus documentos"*. Deliberately keyed off the explicit `source` field rather
than "citations array is empty" — an empty citations array can also happen on
the normal local path (e.g. an image-only answer), so inferring provenance
from it would have produced false positives.

### Styling
New CSS: `.fallback-confirm-card` (a bordered, tinted panel using the
project's existing `color-mix(in srgb, var(--color-primary) N%, transparent)`
convention, already used elsewhere in `style.css`), `button.secondary` (a
plain outlined variant for "Cancelar", visually secondary to the confirm
button), and `.provenance-badge` (an error-toned pill, same `color-mix`
pattern against `var(--color-error)`) — different enough from the normal
citations UI that it can't be mistaken for a grounded answer at a glance, per
the roadmap's own requirement.

## Consequences

### Verified for real in the browser, exactly per this phase's "done when"
Registered a real user (fresh tenant, empty corpus), then:

1. Asked a question guaranteed to miss the empty corpus → the confirmation
   card appeared with the exact copy above, no answer card shown yet.
2. Clicked "Buscar em IA pública" → a real `gemini-flash-latest` answer
   appeared (correctly noting it had no attached document, then answering
   from general knowledge), **with the provenance badge visibly rendered**
   above the answer text, and `citations` showing "No sources were retrieved
   for this question."
3. Uploaded a real document to the same tenant and asked a normal question
   about it (no fallback) → the normal answer card rendered, retrieval found
   the right chunk (visible in the citations list, same RRF score scale
   confirmed in ADR 0037/0038), and **the provenance badge correctly did not
   appear** — confirming the negative case, not just the positive one.
   `llama3.1`'s own answer to that single terse chunk was mediocre (an
   unrelated, already-documented model-quality limitation, not a defect in
   this phase's UI logic).

### An incidental docker-compose build issue found and worked around, unrelated to the code change
`docker compose up -d --build web-ui` hung indefinitely partway through
rebuilding `chat-service`'s Maven dependencies — `web-ui`'s own Dockerfile
(a static `nginx:alpine` image, no Maven step at all) has nothing to do with
that build stage. Worked around by building narrowly instead:
`docker compose build web-ui` (completes in under a second, entirely
cache-hit) followed by `docker compose up -d --no-deps web-ui` (recreates only
the `web-ui` container, skipping its `depends_on` chain entirely). Not
investigated further or fixed here — a pre-existing Docker Compose/BuildKit
interaction, not something this phase's own change caused, and out of scope
for a `web-ui`-only phase.

### Scope: `web-ui` only, and only the JSON `/api/v1/ask` path
The image-attachment multipart form was not touched — images never trigger
the fallback at all (ADR 0038), so there was nothing for it to do here.
`chat-service`'s own UI (none exists; it's an API-only service per ADR 0013)
is unaffected. Provider choice (OpenAI vs. Gemini) is not exposed in the UI —
the confirm button always lets the backend default to Gemini (the only
provider verified working end-to-end, ADR 0036); exposing a picker was judged
unnecessary scope for what the roadmap asked (a yes/no confirmation, not a
provider-selection UI).

### This closes the entire Phase 2 fallback sub-sequence except Anthropic
2a (ADR 0036), 2b (ADR 0037), 2c (ADR 0038), and 2d (this ADR) are all done.
Only 2e (Anthropic wiring, deliberately deferred until the user generates
`ANTHROPIC_API_KEY`) remains open in the Phase 2 sequence.
