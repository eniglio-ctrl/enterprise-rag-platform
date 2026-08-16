# ADR 0053: Repeatable eval history for `RagQualityBenchmark`

## Status
Accepted

## Context
`docs/MULTI-LLM-ORCHESTRATOR-ROADMAP.md` Phase 16, added after the user asked
for an evaluation of an "AI Factory Stack" framing (LLM / RAG / Vector DB /
Agent / MCP / Guardrails / Evals) pasted from an outside conversation.
Gap-checked box by box against this project: LLM, RAG, Vector DB are done;
Agent (`PlannerAgent`) and MCP are already Phase 3/6 above, just not started
yet; Guardrails (ABAC, tenant isolation, rate limiting) already exist, just
never named as one concept. Evals was the one real, still-open gap —
`RagQualityBenchmark` (ADR 0034) already measures real answer quality
against a fixed question set, but ADR 0034 itself flagged the exact thing
that was never closed: *"recommended follow-up, not done here: re-run this
same benchmark."* Every run printed to stdout and left no trace; there was
never anything to compare a later run against.

## Decision

### Extend the existing benchmark, not build new infrastructure
`RagQualityBenchmark` gained per-question latency measurement (wrapping the
existing `ragQueryService.answer(...)` call with `Instant.now()`, summed
into the loop's existing accumulator pattern) and, at the end of the run,
one appended row — `date,commit,questions,avgSimilarity,faithful,
totalQuestions,avgContextRelevance,avgLatencyMs` — to
`rag-service/src/test/resources/benchmark/history.csv`, the real git-tracked
source file (not `target/test-classes`), via a new
`BenchmarkSupport.appendHistoryRow`. The git commit is resolved from
`GIT_COMMIT` (common CI env var) or a real `git rev-parse --short HEAD`
call, never throwing — a benchmark run should never fail just because the
commit couldn't be resolved. No new service, no dashboard, no scheduled CI
job (would need an Ollama-capable runner — a real infra/cost decision this
phase deliberately doesn't make): the "trend over time" story is just `git
diff` on one CSV file after a manual run.

### Two real bugs found only by actually running this for real
1. **`unaccent_simple` drift.** Both `RagQualityBenchmark` and
   `ChunkingStrategyBenchmark`'s own ad-hoc DDL setup (rag-service never runs
   Flyway itself, ADR 0011) still created `content_tsv` using plain
   `'simple'`, from before ADR 0042 introduced `'unaccent_simple'`.
   `HybridSearchService`'s full-text SQL has queried with `'unaccent_simple'`
   ever since — every run of either benchmark since that ADR shipped would
   have failed immediately with "text search configuration unaccent_simple
   does not exist." Nobody had re-run either benchmark since, so nobody
   noticed. Fixed by copying `ChatQueryIT`'s already-correct setup
   (`CREATE EXTENSION unaccent`, `CREATE TEXT SEARCH CONFIGURATION
   unaccent_simple ...`) into both benchmark classes.
2. **Locale-dependent decimal separator corrupting the CSV.** The first real
   history row wrote fields like `0,5838` instead of `0.5838` — `%.4f`
   without an explicit `Locale` uses the JVM's default locale, and this
   machine's default renders a decimal point as a comma, which is also this
   format's field separator. Fixed by switching to
   `String.format(Locale.ROOT, ...)`. A machine-readable file must never
   depend on which locale happens to run the JVM producing it — the exact
   same class of bug this project has already hit once before with
   log-injection-adjacent locale assumptions elsewhere, just in a new spot.

Both bugs were only found because this ADR's own "done when" required an
actual successful real run, not just a compiling code change — consistent
with this project's established practice of verifying against real
infrastructure rather than trusting that code which merely compiles behaves
correctly.

## Consequences

### The very first real run surfaced a real, unrelated finding
With both bugs fixed, two consecutive real runs against local Ollama both
came in under the benchmark's own minimum-similarity bar (0.60): 0.5838 and
0.5876. This is a genuine quality-drift finding, not a bug in this phase's
own code — something in this project's RAG pipeline (or the local model's
current behavior, or environmental drift since ADR 0034 was written) is
producing answers a bit less similar to the expected answers than the bar
this project set for itself. **Deliberately not investigated or fixed
here** — diagnosing *why* similarity dropped is a separate, real piece of
work (compare against ADR 0034's original measured numbers, check for
qa-pairs/config drift, check if a different local model version changed
behavior) that this phase's scope (repeatable tracking, not a quality fix)
didn't call for. Flagged explicitly as the next real follow-up, in the same
spirit ADR 0034 flagged the gap this phase closes — this repeatable history
file is exactly what makes that next investigation possible: `git diff
rag-service/src/test/resources/benchmark/history.csv` after a fix attempt
will show, in concrete numbers, whether it actually helped.

### Verified for real, twice
Two full real runs against local Ollama (`llama3.1`/`nomic-embed-text`,
already pulled), each ~4-7 minutes (10 questions × real generation +
groundedness + context-relevance calls per question). First run proved the
`unaccent_simple` fix (reached the assertion instead of erroring on the SQL);
its history row came out corrupted by the locale bug. Second run, after
fixing that too, produced a correctly comma-separated row. The normal
`./mvnw test -pl rag-service` suite (without `-Dbenchmark=true`) stays green
throughout — this benchmark remains `@EnabledIfSystemProperty`, opt-in, never
gating normal CI, exactly as before this phase.
