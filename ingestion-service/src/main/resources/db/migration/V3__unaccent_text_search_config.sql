-- docs/ROADMAP.md item #16: 'simple' (V2's original text search config) does not
-- strip accents/diacritics, so "informação" and "informacao" tokenize differently
-- and a question typed without accents can silently miss full-text-indexed content
-- that has them (or vice versa). The vector/embedding leg is largely unaffected
-- (semantic similarity, not exact tokens) - this is specifically a full-text-leg gap.
--
-- unaccent_simple copies 'simple' (same tokenizer, no stemming - content can be in
-- either Portuguese or English, ADR 0011/0012) but maps every token through the
-- unaccent dictionary first, so both the indexed content and incoming queries fold
-- to the same accent-insensitive form before comparison.
--
-- content_tsv is GENERATED ALWAYS ... STORED (V2): a generated column's expression
-- can't be altered in place, only dropped and re-added, hence DROP COLUMN + ADD
-- COLUMN rather than ALTER. Dropping first also drops the GIN index that depends on
-- it, so the index is recreated afterward rather than needing its own DROP INDEX.

CREATE EXTENSION IF NOT EXISTS unaccent;

CREATE TEXT SEARCH CONFIGURATION unaccent_simple (COPY = simple);
ALTER TEXT SEARCH CONFIGURATION unaccent_simple
    ALTER MAPPING FOR hword, hword_part, word WITH unaccent, simple;

ALTER TABLE vector_store DROP COLUMN content_tsv;
ALTER TABLE vector_store
    ADD COLUMN content_tsv tsvector
        GENERATED ALWAYS AS (to_tsvector('unaccent_simple', coalesce(content, ''))) STORED;

CREATE INDEX vector_store_content_tsv_idx ON vector_store USING GIN (content_tsv);
