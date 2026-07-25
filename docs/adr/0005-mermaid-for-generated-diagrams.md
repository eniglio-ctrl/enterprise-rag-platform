# ADR 0005: LLM-generated Mermaid diagrams instead of a fixed layout engine

## Status
Accepted

## Context
Some ingested documents describe an architecture, a process or a data flow in prose
(e.g. a talk transcript walking through a disaster-recovery setup on AWS). Turning that
prose into a diagram means extracting entities and relationships and then laying them
out visually.

## Decision
`rag-service` asks the chat model to produce a [Mermaid.js](https://mermaid.js.org/)
flowchart definition (plain text, e.g. `flowchart LR\n  A["Amazon S3"] --> B["AWS
Lambda"]`) directly from the retrieved context, instead of extracting structured
coordinates/shapes and laying them out in code. The `web-ui` renders that text
client-side with `mermaid.render(...)`, producing an SVG. No custom layout algorithm was
written — Mermaid owns node positioning entirely.

Several defensive measures were added because the local model (`llama3.1`) doesn't always
follow formatting instructions perfectly:
- The raw response is stripped of Markdown code fences (` ```mermaid ... ``` `) before
  being treated as a diagram definition.
- Every rectangle-node label (`[...]`) is force-quoted (`["..."]`) server-side. An
  unquoted label containing punctuation — e.g. `B[Multi-AZ (alta disponibilidade)]` —
  breaks Mermaid's parser, since parentheses are meaningful in its flowchart syntax.
  Quoting is always valid Mermaid, so this is a safe blanket fix rather than a targeted
  one.
- A stray `>` the model sometimes appends after a pipe-delimited edge label (e.g.
  `A -->|Backup|> B`, which Mermaid rejects — the valid form is `A -->|Backup| B`) is
  stripped with a regex.
- Diagram generation uses `temperature: 0.0` (overriding the service-wide `0.2` used for
  prose answers) via a per-call `OllamaOptions` override. The same question sent twice
  was observed to sometimes produce a diagram and sometimes fall back to the
  "insufficient data" node at `0.2`; greedy decoding makes that decision (and the
  resulting structure) far more consistent, at the cost of a slightly longer, more
  exhaustive response.

## Consequences
- No diagramming/layout library or algorithm to maintain — Mermaid.js, already used for
  the diagrams in this repo's own documentation, does all of it.
- Diagram quality depends on the model actually connecting the entities it lists with
  edges; a small local model sometimes produces a list of correct but under-connected
  nodes rather than one coherent flow. This is a model-capability limitation, not a
  mechanism failure — the generated definition still renders correctly.
- If the retrieved context doesn't describe an architecture/process/flow, the model is
  instructed to return a single "insufficient data" node rather than inventing one; the
  UI treats that sentinel as "no diagram" and shows a message instead of a fake diagram.
