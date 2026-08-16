# ADR 0006: Single "ask" endpoint with keyword-based routing

## Status
Accepted

## Context
The UI originally had two separate inputs: one to ask a question (`POST /api/v1/chat`)
and one to request a diagram (`POST /api/v1/diagrams`). Users don't think in terms of
which internal endpoint handles their request — they just want to ask something, whether
that's a question or "draw me the architecture described here".

## Decision
Added `POST /api/v1/ask`, a single entry point used by the web UI. `RagQueryService.ask()`
strips accents from the question (so "gráfico" and "grafico" match the same way) and
checks it for diagram-intent keywords (`diagrama`, `diagram`, `desenh`, `draw`, `fluxo`,
`flow`, `arquitetura`, `architecture`, `imagem`, `picture`, `esquema`, `flowchart`,
`grafico`, `chart`, `grafo`, `mapa mental`, `mindmap`, `ilustra`) and routes to
`diagram()` if any match, otherwise to `answer()`. The response carries a `type`
discriminator (`"answer"` or `"diagram"`) so the caller knows which field (`answer` or
`mermaid`) is populated.

Routing is a plain substring check, not an extra LLM call, so it adds no latency and no
extra failure mode. The original single-purpose endpoints (`/api/v1/chat`,
`/api/v1/diagrams`) were kept for callers that already know what they want.

## Consequences
- One input box in the UI instead of two; users don't need to know the platform has two
  different generation modes.
- Keyword matching is simple and fast, but not a real intent classifier: a question that
  happens to contain a matching word (e.g. "what's the *flow* of funds in this budget?")
  would be routed to diagram generation even though a text answer was wanted. Given the
  keywords are fairly specific to requesting a visual, this is an accepted tradeoff for
  the MVP.
- Roadmap: if false-positive routing becomes a real problem, replace the keyword check
  with a cheap classification prompt (or a small local classifier) — the `ask()` method
  is the single place that would need to change.

## Update: the predicted false positive became a real one — replaced with an actual
## LLM classification call

Exactly the failure mode this ADR's "Consequences" section predicted did happen, found
by a real user, not hypothetically: ADR 0023 added the ability to attach an image to a
question, and the single most natural question to ask about an attached photo —
"O que tem nessa imagem?" ("what's in this image?") — contains "imagem", which was one
of this ADR's own keywords. Every such question silently misrouted to diagram
generation instead of using the vision description to actually answer.

The keyword list is gone. `RagQueryService.ask()` now makes a real, temperature-0,
single-word-output classification call before routing — see
[ADR 0024](0024-llm-based-ask-routing.md) for the detail. `/api/v1/ask` now costs one
more LLM call than before on every request; the original single-purpose endpoints
(`/api/v1/chat`, `/api/v1/diagrams`) are completely unaffected, since neither ever
routed through `ask()`'s keyword check to begin with.
