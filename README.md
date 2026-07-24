# enterprise-rag-platform

A Retrieval-Augmented Generation platform built like a real backend system, not a
notebook: independent Spring Boot services, a shared Postgres/pgvector store, local-first
inference through Ollama, structured logging, metrics, health checks, and documented
architecture decisions.

Upload a PDF/DOCX/Markdown/text file, ask a question, get back an answer with the exact
chunks (source + score) it was grounded in.

## Architecture

```mermaid
flowchart LR
    U["Browser"]

    subgraph Platform["enterprise-rag-platform"]
        WEB["web-ui :3000"]
        ING["ingestion-service :8081"]
        RAG["rag-service :8082"]
        PG[("PostgreSQL + pgvector")]
        OLL["Ollama\nnomic-embed-text / llama3.1"]
    end

    U --> WEB
    WEB -- "POST /api/v1/documents" --> ING
    WEB -- "POST /api/v1/chat" --> RAG
    ING -- "embed chunks, INSERT" --> PG
    ING -- "embedding request" --> OLL
    RAG -- "similarity search" --> PG
    RAG -- "embedding + chat request" --> OLL
```

Full ingestion/query sequence diagrams and the reasoning behind each architectural
choice live in [docs/architecture.md](docs/architecture.md) and [docs/adr](docs/adr).

## Project structure

```
enterprise-rag-platform/
├── ingestion-service/   # upload, parse, chunk, embed, persist
├── rag-service/          # retrieve, generate, cite
├── web-ui/               # browser UI for upload + chat (static HTML/CSS/JS, nginx)
├── postgres-pgvector/    # DB init (vector extension)
├── docs/
│   ├── architecture.md
│   └── adr/              # architecture decision records
├── docker-compose.yml
└── pom.xml               # Maven multi-module parent
```

## Tech stack

| Concern            | Choice                                   |
|---------------------|-------------------------------------------|
| Language / runtime  | Java 21, Spring Boot 3.5                  |
| AI orchestration    | Spring AI 1.0 (Ollama models, pgvector store) |
| Vector store        | PostgreSQL 16 + pgvector (HNSW, cosine)   |
| LLM runtime          | Ollama (`nomic-embed-text`, `llama3.1`)   |
| API docs            | springdoc-openapi / Swagger UI            |
| Observability       | Micrometer + Prometheus, Spring Boot structured (ECS) logs, Actuator health |
| Testing             | JUnit 5, Mockito, Testcontainers (Postgres/pgvector) |
| Frontend            | Vanilla HTML/CSS/JS (no build step), served by nginx |
| Packaging            | Docker, Docker Compose                    |

## Running it

Requirements: Docker and Docker Compose. Nothing else — no API keys, no local JDK/Maven
needed, models are pulled automatically on first boot.

```bash
docker compose up --build
```

First startup takes a few minutes while Ollama pulls `nomic-embed-text` and `llama3.1`
(a few GB). Once healthy:

| Service           | URL                                             |
|-------------------|--------------------------------------------------|
| web-ui            | http://localhost:3000                            |
| ingestion-service | http://localhost:8081/swagger-ui.html            |
| rag-service       | http://localhost:8082/swagger-ui.html            |

The web UI at `localhost:3000` covers both flows — drag a file in to upload and index it,
then ask a question and see the answer with its citations. The sections below show the
same two flows via `curl`, useful for scripting or CI.

### Ingest a document

```bash
curl -X POST http://localhost:8081/api/v1/documents \
  -F "file=@/path/to/aula12.md"
```

```json
{ "documentId": "…", "source": "aula12.md", "pageCount": 1, "chunkCount": 3 }
```

### Ask a question

```bash
curl -X POST http://localhost:8082/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{"question": "Como funciona o padrão SAGA?"}'
```

```json
{
  "answer": "O padrão SAGA coordena transações distribuídas... [1]",
  "citations": [
    { "source": "aula12.md", "chunkIndex": 0, "score": 0.83, "snippet": "O padrão SAGA..." }
  ]
}
```

## Running the tests

```bash
./mvnw test          # unit tests, no external dependencies
./mvnw verify         # includes Testcontainers integration tests — needs Docker
```

Integration tests spin up a real `pgvector/pgvector:pg16` container per module and mock
only the Ollama-backed models, so the ingestion → chunk → embed → store → retrieve path
is exercised against a real database, not a fake.

## What's implemented vs. what's next

This is a deliberately shipped **vertical slice**: `ingestion-service` and `rag-service`
are fully working end to end, rather than six half-built modules. What's next:

- **chat-service** — multi-turn conversations with memory (Spring AI `ChatMemory`),
  sitting in front of `rag-service`.
- **auth-service** — JWT/OAuth2, so ingestion and chat are per-user/per-tenant.
- **Hybrid search + re-ranking** — combine pgvector similarity with Postgres full-text
  (`tsvector`) search, re-rank the merged candidates with a cross-encoder.
- **Kubernetes manifests** — Deployments, Services, ConfigMaps, HPA for each service.
- **CI** — GitHub Actions running `./mvnw verify` (including the Testcontainers suite)
  on every PR.
- **Grafana dashboards** on top of the Prometheus metrics already exposed by both
  services.
- Groundedness check for generated answers (see
  [ADR 0004](docs/adr/0004-citations-from-retrieval-not-llm.md)).

## Architecture decisions

- [ADR 0001 — PostgreSQL + pgvector as the vector store](docs/adr/0001-postgres-pgvector-as-vector-store.md)
- [ADR 0002 — Shared database between services (MVP simplification)](docs/adr/0002-shared-database-between-services.md)
- [ADR 0003 — Ollama for local-first inference](docs/adr/0003-ollama-for-local-first-inference.md)
- [ADR 0004 — Citations from retrieval, not from the LLM](docs/adr/0004-citations-from-retrieval-not-llm.md)
