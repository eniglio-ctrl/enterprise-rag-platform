# ADR 0024: Replace keyword-based `/api/v1/ask` routing with an LLM classification call

## Status
Accepted

## Context
[ADR 0006](0006-unified-ask-endpoint-with-keyword-routing.md) routed `/api/v1/ask`
between a text answer and a Mermaid diagram using a fixed keyword list
(`diagrama`, `desenh`, `fluxo`, `imagem`, `picture`, ...) — deliberately chosen over
an LLM call for zero added latency, with its own "Consequences" section explicitly
flagging the risk: *"a question that happens to contain a matching word ... would be
routed to diagram generation even though a text answer was wanted"*.

That risk stopped being theoretical the moment [ADR 0023](0023-ephemeral-image-attachment-on-ask.md)
shipped image attachments. A real user attached a photo and asked "O que tem nessa
imagem?" ("what's in this image?") — the single most natural question to ask about
an attachment — and got a Mermaid diagram assembled from unrelated retrieved
documents instead of a description of the actual photo. "Imagem" was one of the
keywords. The user's own framing of what was needed, verbatim: *"quero que o
contexto seja interpretado, não fique com palavras fixas para um contexto"* (I want
the context to be interpreted, not stuck with fixed words for a context) — the fix
needed to be genuine intent understanding, not another patch to the word list
(removing "imagem" alone would have "fixed" this one report while leaving the same
class of bug for the next incidental keyword match).

## Decision
`RagQueryService.wantsDiagram(...)` no longer does substring matching at all. It now
makes a real classification call: a short system prompt asks the resolved chat model
to answer with exactly one word, `"DIAGRAMA"` or `"RESPOSTA"`, given the question
(plus a one-line note if an image was attached — not its full description, keeping
the classification prompt minimal). Temperature 0, same reasoning as the existing
groundedness-check and diagram-generation calls: this is a classification, not
prose, so deterministic output matters more than variety.

The prompt explicitly tells the model that incidental keyword overlap doesn't mean
diagram intent — the same instruction the fixed list could never encode:

> Responda "RESPOSTA" em todos os outros casos — incluindo perguntas sobre o
> conteúdo de uma imagem ou anexo ... mesmo que a pergunta contenha palavras como
> "imagem", "diagrama" ou "arquitetura" de forma incidental.

`ask()` now resolves the model once (previously routing didn't need a model at all)
and passes it to both the classification call and whichever generation path follows.
The classification call is routed through the same `LlmGateway`/circuit-breaker path
as every other model call in this class (ADR 0009), so a provider outage affects
routing the same way it affects generation, not a separate untracked failure mode.

## Consequences
- **`/api/v1/ask` costs one more LLM call than before, on every request.** This is
  the deliberate trade this ADR makes: ADR 0006 chose zero latency over correctness
  for the MVP; this ADR chooses correctness now that the MVP has real users hitting
  the failure case. `/api/v1/chat` and `/api/v1/diagrams` — the original
  single-purpose endpoints — are completely unaffected, since neither ever routed
  through `ask()`'s classification at all.
- All of `RagQueryServiceTest`'s `ask(...)`-exercising tests were rewritten to mock
  two distinct calls (the classification call and the generation call that follows),
  distinguished by checking for the classification prompt's marker text — the same
  idiom the existing groundedness tests already used to distinguish the groundedness
  verdict call from the answer-generation call. A new regression test
  (`askingWhatIsInTheAttachedImageRoutesToAnswerNotDiagram`) pins down the exact
  question the user hit, asserting the classifier is actually asked and its verdict
  honored — not that a word happens to be absent from a list.
- Verified for real against the exact failure: a generated test image (blue
  background, red square) attached with the question "O que tem nessa imagem?"
  previously produced a diagram built from unrelated retrieved documents; after this
  change it correctly answers with a description of the actual attached image.
- Still not a perfect classifier — an LLM classification call can misjudge an
  unusually ambiguous question the way any classifier can. That's an accepted,
  qualitatively different risk than a keyword list's guaranteed false positive on
  any question containing one of its exact words.
