# ADR 0001: Use PostgreSQL + pgvector as the vector store

## Status
Accepted

## Context
The platform needs to store chunk embeddings and run similarity search. Options
considered: a dedicated vector database (Pinecone, Weaviate, Milvus, Qdrant) or
pgvector on top of the Postgres instance the platform already needs for
transactional/metadata storage.

## Decision
Use PostgreSQL with the `pgvector` extension (`pgvector/pgvector:pg16` image),
accessed through Spring AI's `PgVectorStore`, with an HNSW index and cosine
distance.

## Consequences
- One less moving part to operate, back up and secure — a single database
  instead of a database plus a separate vector store.
- Mature tooling (psql, migrations, backups, `pg_dump`) applies directly to
  vector data.
- At very large scale (hundreds of millions of vectors, extreme QPS) a
  purpose-built vector database would likely outperform pgvector; not a
  concern at the scale this project targets.
- Swapping stores later is a Spring AI `VectorStore` implementation swap, not
  a rewrite of ingestion or retrieval logic.
