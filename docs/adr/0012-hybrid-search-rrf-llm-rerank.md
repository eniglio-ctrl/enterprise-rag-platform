# ADR 0012: Hybrid search (vector + full-text via RRF), opt-in LLM rerank

## Status
Accepted

## Context
Pure vector similarity search misses documents that use different wording than the
question even when they're the right answer, and — the opposite failure — it can miss
an exact match on a rare term (a proper noun, a product name) if the embedding model
happens not to place it near the question in vector space. ADR 0011 already added a
`content_tsv` column specifically to prepare for this. Spring AI 1.0.0 has no native
Reciprocal Rank Fusion (RRF), so combining the two search strategies means raw SQL.

## Decision
- New `HybridSearchService`: runs the existing vector search (via `VectorStoreGateway`,
  ADR 0009) and a full-text search (raw SQL via `JdbcTemplate` against `content_tsv`)
  in parallel, then fuses the two ranked lists with RRF
  (`score(d) = Σ 1/(k+rank_i(d))`, `k=60`, the standard constant from the literature).
  A document absent from one list simply doesn't get that list's contribution — it
  isn't penalized beyond that.
- **[achado]** The plan called for `plainto_tsquery`, which ANDs every token in the
  query together. A real question like "Onde fica o Globodyne?" would then require the
  matched document to contain "onde"/"fica"/"o" too, not just "Globodyne" — defeating
  the full-text leg for exactly the rare-term case it exists to help with. Fixed by
  building an OR'd tsquery instead (`to_tsquery('simple', 'onde | fica | o | globodyne')`):
  any single significant word matching is enough for a candidate to surface, and
  `ts_rank` still rewards documents matching more terms over ones matching only one.
  The OR string is built by stripping the question down to letters/digits/whitespace
  before joining with `|` (`HybridSearchService.buildOrTsQuery`) — this isn't just
  cosmetic sanitization, it's what makes building a tsquery string from raw user input
  safe: nothing survives that could alter tsquery syntax (parentheses, `&`/`|`/`!`,
  weight labels), so the result can never be anything other than a flat OR of plain
  words.
- **`HybridSearchService` becomes the unconditional default retrieval path** for both
  `answer()` and `diagram()` — replacing the old direct `vectorStoreGateway.search()`
  call in `RagQueryService`. This is a pure retrieval-quality improvement with
  negligible cost (one extra indexed Postgres query, no extra LLM call), so unlike
  groundedness (ADR 0008) or rerank (below) there's no latency tradeoff that would
  justify making it opt-in.
- New `LlmRerankService`: one **batched** LLM call scoring every candidate 0–10 via
  structured output (`ChatClient...call().entity(RerankResponse.class)`), never one
  call per candidate. **Opt-in per request** (`ChatRequest.rerank`, mirroring
  `grounded`): this is a full extra Ollama round trip stacked on top of hybrid search +
  generation, and the project's own latency principle (documented back in the Fase 1
  planning) is that anything pushing a request meaningfully past its already-high
  baseline latency should be opt-in, not a silent default every caller pays for.
  Scoped to `answer()`/`/api/v1/chat` only, not `diagram()` — same reasoning ADR 0008
  used for groundedness.
- `HybridSearchService` always retrieves `rag.rerank-candidate-pool-size` (15, new
  property) from each leg internally before RRF fusion, regardless of the final
  `topK` requested — a wider pool measurably improves fusion quality (more chance for
  a document ranked, say, 8th in one leg and 4th in the other to still surface once
  both contribute), and a `LIMIT 15` vs `LIMIT 5` on an indexed Postgres query has no
  meaningful latency cost either way (unlike an LLM call, this isn't where the time
  goes). `topK` only controls how many results come out *after* fusion — 5 normally,
  or the same 15 handed to `LlmRerankService` when `rerank=true`, which then narrows
  to 5 itself after scoring.

## Consequences
- `RagQueryService` no longer depends on `VectorStoreGateway`/`SearchRequest`/
  `FilterExpressionBuilder` directly — `HybridSearchService` owns all of that now,
  including the tenant filter (ADR 0007) for its vector leg and a plain `tenant_id = ?`
  for its SQL leg (the generated column ADR 0011 added, not the metadata jsonb).
- Citations now carry an RRF score (and, when reranked, an LLM 0–10 score rescaled to
  0–1) instead of the raw cosine similarity — the two search legs' scores aren't on a
  comparable scale, so showing the fused score is more honest about what actually
  determined ranking than showing one leg's number and implying it was the whole
  story.
- **Verified with a dedicated integration test, not just unit tests of the RRF math**:
  `ChatQueryIT.hybridSearchFindsRareTermDocumentThatVectorSearchAloneWouldMiss` seeds a
  document whose mocked embedding is the exact opposite vector (cosine similarity -1)
  of the query's — vector search alone, with the configured 0.5 threshold, would never
  return it — and confirms it still comes back via the full-text leg. This is the
  concrete version of the plan's "pronto quando" criterion (a rare term with low
  embedding similarity gets retrieved), not just an assertion that the RRF formula
  computes correctly in isolation.
- `rag-service` never runs Flyway (it only reads `ingestion-service`'s schema), so its
  integration tests — a standalone Testcontainers Postgres with no `ingestion-service`
  involved — needed the same `ALTER TABLE ... GENERATED ALWAYS` columns Flyway's V2
  migration adds, applied directly in `ChatQueryIT`'s `@BeforeEach` (kept in sync with
  the real migration by hand, since there's no shared source between the two — worth
  watching if `content_tsv`'s definition changes again).
- **Real bug found testing `rerank=true` against the actual local `llama3.1`, not
  just mocked tests**: the first live request threw a `NullPointerException` —
  `RerankResponse.scores()` came back `null`. Structured output (`.entity(...)`)
  didn't throw; it successfully parsed *something*, just not JSON containing a
  `scores` array — a smaller local model doesn't reliably follow a requested output
  schema the way a frontier hosted model might. `LlmRerankService.rerank()` now
  treats a null/empty `scores` list the same way `diagram()` (ADR 0005) already
  treats a malformed Mermaid response: log a warning and fall back — here, to the
  candidates in their pre-rerank RRF order — rather than letting a parsing gap
  surface as a raw 500. Re-tested afterward against the real model and real demo
  data: no crash, request completes (rerank succeeding or falling back are both
  now "fine", not "sometimes 500").
