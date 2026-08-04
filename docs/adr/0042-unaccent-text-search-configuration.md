# ADR 0042: `unaccent_simple` text search configuration for the hybrid search full-text leg

## Status
Accepted

## Context
`docs/ROADMAP.md` item #16. `HybridSearchService`'s full-text leg (ADR 0011/0012)
indexes `content_tsv` via `to_tsvector('simple', ...)` and queries it via
`to_tsquery('simple', ...)`. Postgres's built-in `'simple'` configuration
lowercases and tokenizes but does not strip accents/diacritics — a real gap
found while using the fallback flow: "informação" and "informacao" tokenize to
different lexemes, so a question typed without accents (common — quick
typing, some keyboards, non-Brazilian keyboard layouts) can silently miss
full-text-indexed content that has them, or vice versa. The vector/embedding
leg is largely unaffected (semantic similarity over embeddings, not exact
token matching), so this is specifically a full-text-leg gap.

## Decision

### A custom `unaccent_simple` configuration, not a fixed language config
`'portuguese'`/`'english'` were both rejected for the same reason `'simple'`
was originally chosen (ADR 0011/0012): content can be in either language, and
a fixed stemming configuration would silently degrade matches in whichever
language it wasn't tuned for. `unaccent_simple` copies `'simple'` (`CREATE
TEXT SEARCH CONFIGURATION unaccent_simple (COPY = simple)`) and adds one
mapping change: `ALTER MAPPING FOR hword, hword_part, word WITH unaccent,
simple` — every token now passes through the `unaccent` dictionary first,
folding diacritics, before falling through to `simple`'s existing tokenizing
behavior. Both `to_tsvector` (index time) and `to_tsquery` (query time) are
pointed at the same named configuration, so a query and the content it's
meant to match always fold identically regardless of which side happened to
have the accent.

### A new Flyway migration, not an `ALTER` of the existing column
`content_tsv` is `GENERATED ALWAYS AS (...) STORED` (V2 migration) — a
generated column's expression can't be altered in place, only dropped and
re-added. V3 does exactly that: `CREATE EXTENSION IF NOT EXISTS unaccent`,
create the configuration, `DROP COLUMN content_tsv` (which also drops the GIN
index that depended on it), then `ADD COLUMN content_tsv ... GENERATED ALWAYS
AS (to_tsvector('unaccent_simple', coalesce(content, ''))) STORED` and
recreate the index. Existing rows are recomputed automatically as part of the
`ADD COLUMN` (same mechanism V2 already relied on) — no separate backfill
step needed. Added identically under both `db/migration` and
`db/migration-demo` (Flyway resolves migrations per-location; the demo
profile's `spring.flyway.locations` points only at the latter — same
established pattern as V2's own two copies).

### `to_tsvector(regconfig, text)` is IMMUTABLE regardless of what the named config does
A commonly-hit Postgres gotcha is that the plain `unaccent(text)` SQL function
is `STABLE`, not `IMMUTABLE`, so it can't be called directly inside a
generated column expression or a functional index. That gotcha does not apply
here: `unaccent` is wired in as a *text search dictionary* inside the
`unaccent_simple` configuration, consumed through `to_tsvector(regconfig,
text)`/`to_tsquery(regconfig, text)`, and Postgres declares the two-argument
forms of those functions `IMMUTABLE` regardless of what the named
configuration's dictionaries actually do internally — the same reason the
original V2 migration's `to_tsvector('simple', ...)` was already accepted as
a generated column expression. No wrapper function was needed.

### Hyphenated compounds: checked, not silently assumed fixed
This item's own scoping text flagged "special characters" beyond accents
(e.g. hyphenated compounds like "e-commerce") as a related but distinct
tokenization question. `buildOrTsQuery` strips anything that isn't a letter
or digit before building the OR query, and `to_tsvector` does the same
tokenizing at index time — both sides already split "e-commerce" into
"e"/"commerce" identically, so this needed no code change. A dedicated test
(`hybridSearchMatchesAHyphenatedCompoundInBothIndexedContentAndQuestion`)
exists specifically to confirm that, rather than leaving it as an assumption.

### `HybridSearchService`'s two SQL call sites repointed
Both the `content_tsv @@ to_tsquery(...)` predicate and the `ts_rank(...)`
ranking expression in `FULL_TEXT_SEARCH_SQL` now use `'unaccent_simple'`
instead of `'simple'` — querying with a different configuration than the one
the column was generated with would silently reintroduce the exact mismatch
this change exists to close.

## Consequences

### Verified for real, both halves of this item's "done when"
Three new tests added to `ChatQueryIT`, reusing the same
deliberately-opposite-vector trick as the existing
`hybridSearchFindsRareTermDocumentThatVectorSearchAloneWouldMiss` test (index
and query embeddings pointed in opposite directions, cosine similarity -1,
so the 0.5 similarity threshold guarantees the vector leg can never surface
the match — only the full-text leg's accent folding can):
- `hybridSearchMatchesAnAccentedQuestionAgainstUnaccentedIndexedContent`
- `hybridSearchMatchesAnUnaccentedQuestionAgainstAccentedIndexedContent`
- `hybridSearchMatchesAHyphenatedCompoundInBothIndexedContentAndQuestion`

All passing against a real `pgvector/pgvector:pg16` Testcontainer (9 tests in
`ChatQueryIT`, up from 6, all green). `ingestion-service`'s own
`DocumentIngestionIT` (which runs the real Flyway migration chain against a
fresh Testcontainer on every run) confirmed V3 applies cleanly with no manual
verification needed beyond that.

Then, against the real running local docker-compose stack, not just the
automated test suite: rebuilt and restarted `ingestion-service`/`rag-service`,
confirmed via container logs that Flyway actually migrated schema "public" to
version 3. Uploaded a real document containing "informação" via
`ingestion-service`. Queried Postgres directly — `content_tsv @@
to_tsquery('unaccent_simple', 'informacao')` and the accented spelling both
returned `true` for that row. Then asked `rag-service`'s `/api/v1/ask` the
fully unaccented question "Qual o prazo de retenção da informação?" typed
without any accents — got back the correct answer with a citation to the
uploaded document, RRF score `0.03278... ≈ 2/61`, the exact score
`HybridSearchServiceTest`'s `computesStandardReciprocalRankFusionScore` unit
test asserts for "present in both the vector and full-text lists at rank 1" —
concrete numeric evidence that the full-text leg genuinely contributed to
this real answer, not just the vector leg carrying it alone.

### `db/migration-demo`'s copy not verified against the demo's actual Neon instance
Kept byte-for-byte structurally identical to `db/migration`'s V3 (same
established convention as V2), but this session did not redeploy or check
`CREATE EXTENSION unaccent` against the public demo's actual Neon Postgres
instance — out of scope for this item's "done when," which only asked for
local verification. Flagged explicitly in the migration file's own comment:
if a future demo deploy's Flyway migration fails, this is the first thing to
check.
