# ADR 0034: RAG quality deep-dive — chunking strategies and evaluation metrics

## Status
Accepted

## Context
`docs/MULTI-LLM-ORCHESTRATOR-ROADMAP.md` Phase 8, Tier 1 item #6 of `docs/ROADMAP.md`.
Two related, pre-existing gaps in the RAG pipeline's own quality measurement:

- **Chunking**: `ingestion-service` only ever used `TokenTextSplitter` — a fixed
  token-count cut with zero awareness of paragraph, sentence, or heading
  boundaries. A chunk boundary can land mid-sentence or split a heading from its
  own body text.
- **Evaluation**: `RagQualityBenchmark` (Fase 7c) measured exactly one number —
  cosine similarity between a generated answer and an expected answer. It said
  nothing about whether an answer was actually *supported* by what was retrieved
  (faithfulness), or whether what was retrieved was actually *useful* for the
  question (context relevance) — two different failure modes a single similarity
  score can't distinguish.

## Decision

### Two new structure-aware splitters, compared against the production baseline
- `RecursiveCharacterTextSplitter` (paragraph → sentence → hard character cutoff,
  only descending to a finer separator for a piece still too large after the
  coarser one) and `MarkdownAwareTextSplitter` (splits at `#`-`######` heading
  boundaries first, falls back to the recursive splitter for any section still
  too large) both extend Spring AI's `TextSplitter` — Spring AI 1.0.0 ships no
  structure-aware splitter of its own, only `TokenTextSplitter`, confirmed by
  inspecting the actual resolved jars, not assumed from documentation.
- **Both live in `platform-common`, not `ingestion-service`** — the module that
  actually chunks real uploads — because `rag-service`'s new
  `ChunkingStrategyBenchmark` needs them too, and `rag-service` has no
  dependency on `ingestion-service` (sibling services, not layered). Added
  `spring-ai-commons` as a new `platform-common` dependency (version from the
  existing root `spring-ai-bom`, no new version to manage).
- **Markdown heading detection was verified against real Tika output, not
  assumed**: uploaded this project's own `docs/architecture.md` through the
  real local `ingestion-service` and inspected the stored chunk content
  directly in Postgres — `#`/`##` syntax survives Tika's plain-text extraction
  verbatim. Only after confirming that was `MarkdownAwareTextSplitter` written
  to key off it.
- **Not wired into the real ingestion pipeline in this phase.** `ingestion-service`
  still uses `TokenTextSplitter` via its existing `TextSplitterConfig` —
  swapping the production splitter changes the shape of every chunk stored for
  every future document, which is a separate decision this phase's own
  investigation doesn't make unilaterally. See Consequences for what the
  measured results suggest about that follow-up decision.

### Faithfulness and context relevance added to the existing benchmark, not a new parallel one
- `RagQueryService` gained two new **public** methods, `checkGroundedness(String,
  String)` and `checkContextRelevance(String, String)` — widened from the
  existing private `checkGroundedness` (ADR 0008) and a new sibling method,
  specifically so `RagQualityBenchmark` can reuse the exact same faithfulness
  check outside the live `/api/v1/ask` request cycle rather than building a
  second, parallel implementation.
- **Faithfulness**: `RagQualityBenchmark`'s existing call simply flips `grounded`
  from `false` to `true` — `response.groundedness()` comes back already computed
  against the exact context the answer was actually generated from, no
  reconstruction needed.
- **Context relevance**: independent of the final answer on purpose — a bad
  retrieval can still produce a faithful-looking answer if the model already
  "knew" the fact from training, so this catches a different failure mode.
  Calls the already-public `RagQueryService.retrieve(question, tenantId)`
  separately to get full, untruncated chunk text (`Citation.snippet()` is
  truncated to 200 chars, not enough for a fair judgment), then judges each
  retrieved chunk against the question via a new temperature-0 LLM-as-judge
  prompt (`RELEVANTE`/`IRRELEVANTE`) — same verdict-parsing pattern as ADR
  0008's groundedness check (the negative token, `IRRELEVANTE`, is checked
  before the positive one, since it contains it as a substring — the same
  pitfall `parseGroundedness` already handles for `NAO_SUPORTADA`/`SUPORTADA`).
- **New `ChunkingStrategyBenchmark`** (separate class, not folded into
  `RagQualityBenchmark`) does the strategy comparison — a genuinely different
  shape of test (seeds one real long document per variant, deletes and re-seeds
  between variants) that didn't fit naturally into the existing per-QA-pair
  benchmark loop. `BenchmarkSupport` was extracted to hold the
  cosine-similarity/truncate helpers both classes now share.

## Consequences

### Real, measured numbers — not asserted, run against local Ollama (llama3.1 + nomic-embed-text)

**Chunking strategies** (`ChunkingStrategyBenchmark`, `docs/architecture.md`, 4
questions each targeting a fact in one specific heading section, 150-token /
~600-char chunk size — deliberately small to force multiple chunks out of a
~180-line document):

| Strategy | Chunks produced | Average answer similarity |
|---|---|---|
| Baseline (`TokenTextSplitter`) | 13 | 0.737 |
| Recursive | 15 | 0.839 |
| Markdown-aware | 16 | 0.841 |

Both structure-aware strategies beat the current production baseline by a real,
meaningful margin (+0.10, ~14% relative) on this document. Markdown-aware and
recursive are effectively tied with each other (0.841 vs. 0.839 — within the
kind of run-to-run noise a CPU-bound local model already shows elsewhere in this
project's benchmarks, e.g. `RagQualityBenchmark`'s own cross-lingual-answer
caveat). **This result is specific to a heavily-headed, well-structured
Markdown document at a small chunk size** — it says nothing about PDFs, DOCX, or
plain text, and nothing about whether the same gap holds at the real production
chunk size (800 tokens, where this exact document might fit in 1-2 chunks
regardless of strategy). Recommended follow-up, not done here: re-run this same
comparison at the production chunk size, and against at least one non-Markdown
document, before deciding whether to change `ingestion-service`'s default
splitter.

**Faithfulness and context relevance** (`RagQualityBenchmark`, existing 10
QA-pair corpus):

- **10/10 answers faithful** (`Groundedness.SUPPORTED`) — expected, since each
  QA pair's context is a single short, atomic snippet; the model has little
  room to add unsupported claims when the context is that narrow.
- **Average context-relevance rate: 0.20** (topK=5, one relevant chunk in 5)
  — genuinely informative once explained, not a bug: every QA pair's context
  string shares the same `tenantId="benchmark"`, so retrieval for any one
  question sees the *entire* 10-document pool, of which only one document is
  actually the right one — 1 relevant out of 5 retrieved is exactly what a
  10-document shared corpus with no other relevance signal would produce. This
  reveals something the old single-similarity score never could: this
  benchmark's answers score well (0.641 average, matching Fase 7c's original
  finding almost exactly) *despite* 80% of retrieved context being noise per
  question — real evidence that generation is fairly robust to irrelevant
  context here, not evidence that retrieval itself is finely tuned. Also not
  addressed here: nothing before this phase measured this signal at all, so
  there's no prior baseline to compare it against — this number is the new
  baseline for any future retrieval-quality work.
- Average answer similarity: 0.641 (minimum bar 0.60) — consistent with the
  original Fase 7c measurement, confirming the two new metrics were added
  without regressing the original one.

### Verified for real
- `./mvnw clean verify` green across all 5 modules — 9 new unit tests for the
  two splitters (`platform-common`), 3 new unit tests for the two new
  `RagQueryService` methods (mocked `ChatModel`, same pattern as the existing
  groundedness tests). Neither benchmark class runs in `verify` (both gated by
  `-Dbenchmark=true`, same as the original `RagQualityBenchmark`) — both were
  run for real against local Ollama to produce the numbers above, not just
  compiled.
- No production code path changed — `ingestion-service`'s real chunking is
  untouched; every change here is either a new, currently-unused splitter
  implementation, two new `RagQueryService` methods only exercised by tests,
  or test-only benchmark code.
