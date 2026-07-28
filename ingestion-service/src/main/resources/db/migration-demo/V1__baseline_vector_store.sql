-- ADR 0020: identical to db/migration/V1, except vector(384) instead of vector(768) —
-- the free public demo's EmbeddingModel is a local ONNX model
-- (sentence-transformers/all-MiniLM-L6-v2), not Ollama's nomic-embed-text, and the
-- two produce different-sized vectors. A separate Flyway location
-- (spring.flyway.locations, "demo" profile only) keeps this from ever touching or
-- being touched by the regular 768-dimension schema everyone else uses.

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS vector_store (
    id uuid DEFAULT uuid_generate_v4() PRIMARY KEY,
    content text,
    metadata json,
    embedding vector(384)
);

CREATE INDEX IF NOT EXISTS spring_ai_vector_index
    ON vector_store USING hnsw (embedding vector_cosine_ops);
