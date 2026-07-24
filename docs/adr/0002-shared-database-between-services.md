# ADR 0002: ingestion-service and rag-service share one Postgres instance

## Status
Accepted for the MVP; revisit once auth-service and chat-service land (see roadmap in the root README).

## Context
A "proper" microservice split would have `rag-service` ask `ingestion-service`
for chunks through an API rather than reading its table directly. For the
first vertical slice, that indirection adds a network hop and a second API
contract without adding value yet — nothing else writes to the store, and both
services already depend on the same `vector_store` schema shape.

## Decision
Both services connect to the same Postgres database and the same
`vector_store` table (Spring AI's `PgVectorStore` default). To avoid both
services racing to create the schema on first boot:
- `ingestion-service` sets `spring.ai.vectorstore.pgvector.initialize-schema: true`
  and owns schema creation.
- `rag-service` sets `initialize-schema: false` and only ever reads.
- In `docker-compose.yml`, `rag-service` depends on `ingestion-service` being
  healthy, guaranteeing the schema exists before `rag-service` starts serving
  queries.

## Consequences
- Fewer moving parts for the MVP; one schema, one source of truth.
- The two services are coupled through a shared table shape instead of a
  versioned API — a schema change in one must be coordinated with the other.
- This is a known, deliberate simplification. The roadmap item to introduce
  an internal retrieval API (or an event-driven ingestion pipeline) exists
  specifically to remove this coupling once there's a second consumer of the
  ingested data (e.g. `chat-service`).
