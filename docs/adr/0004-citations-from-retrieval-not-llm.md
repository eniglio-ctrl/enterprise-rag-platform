# ADR 0004: Citations are derived from retrieval, not parsed from the LLM

## Status
Accepted

## Context
A common RAG failure mode is the model inventing or misattributing a
citation. If citations were extracted by parsing the LLM's free-text answer,
a hallucinated `[3]` would be indistinguishable from a real one.

## Decision
`rag-service` builds the `citations` array directly from the `Document`
objects returned by `VectorStore.similaritySearch(...)` — source file name,
chunk index and similarity score all come from data that was actually
retrieved. The LLM is asked (via the system prompt) to reference `[n]`
markers in its prose purely for readability; those markers are never used to
compute the returned citations.

## Consequences
- The `citations` field is always accurate to what was retrieved — it cannot
  contain a source that wasn't actually searched.
- This does not guarantee that a specific sentence in the answer is
  faithfully grounded in the specific citation the model chose to reference
  inline — the model could still misattribute which chunk supports which
  claim within the prose.
- Roadmap item: add a groundedness/NLI check that verifies each claim in the
  answer against the retrieved chunks before returning it.
