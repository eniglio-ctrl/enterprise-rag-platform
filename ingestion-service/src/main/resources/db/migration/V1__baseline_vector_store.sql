-- Baseline schema matching exactly what Spring AI's PgVectorStore auto-creates today
-- (captured via `pg_dump --schema-only -t vector_store` against a running instance,
-- not written from memory — see ADR 0011). IF NOT EXISTS everywhere so this migration
-- is safe to run both against a brand-new database and against an existing one that
-- was previously initialized by PgVectorStore itself (spring.ai.vectorstore.pgvector.
-- initialize-schema was true before this ADR; Flyway now owns schema creation instead).

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS vector_store (
    id uuid DEFAULT uuid_generate_v4() PRIMARY KEY,
    content text,
    metadata json,
    embedding vector(768)
);

CREATE INDEX IF NOT EXISTS spring_ai_vector_index
    ON vector_store USING hnsw (embedding vector_cosine_ops);
