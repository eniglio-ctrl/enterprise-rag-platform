-- ADR 0020: byte-for-byte identical to db/migration/V3 — see that file for the
-- full rationale (docs/ROADMAP.md item #16, accent/diacritic-insensitive
-- full-text matching). Kept as its own copy for the same reason as V2's demo
-- copy: Flyway resolves migrations per-location, and the demo profile's
-- spring.flyway.locations points only at db/migration-demo.
--
-- Not independently verified against the demo's actual Neon instance in this
-- change — CREATE EXTENSION unaccent needs to succeed there too, which this
-- session did not deploy/check live (out of scope for this item's "done when",
-- which only asks for local verification). If a future demo deploy's Flyway
-- migration fails here, that is the first thing to check.

CREATE EXTENSION IF NOT EXISTS unaccent;

CREATE TEXT SEARCH CONFIGURATION unaccent_simple (COPY = simple);
ALTER TEXT SEARCH CONFIGURATION unaccent_simple
    ALTER MAPPING FOR hword, hword_part, word WITH unaccent, simple;

ALTER TABLE vector_store DROP COLUMN content_tsv;
ALTER TABLE vector_store
    ADD COLUMN content_tsv tsvector
        GENERATED ALWAYS AS (to_tsvector('unaccent_simple', coalesce(content, ''))) STORED;

CREATE INDEX vector_store_content_tsv_idx ON vector_store USING GIN (content_tsv);
