# Architecture

## Overview

```mermaid
flowchart LR
    U["Client (curl / Postman / UI)"]

    subgraph Platform["enterprise-rag-platform"]
        ING["ingestion-service :8081"]
        RAG["rag-service :8082"]
        PG[("PostgreSQL + pgvector")]
        OLL["Ollama\nnomic-embed-text / llama3.1"]
    end

    U -- "POST /api/v1/documents" --> ING
    U -- "POST /api/v1/ask" --> RAG
    ING -- "embed chunks, INSERT" --> PG
    ING -- "embedding request" --> OLL
    RAG -- "similarity search" --> PG
    RAG -- "embedding + chat request" --> OLL
```

Both services are independent Spring Boot applications that share one Postgres/pgvector
instance: `ingestion-service` writes to the `vector_store` table, `rag-service` only reads
from it. See [ADR 0002](adr/0002-shared-database-between-services.md) for why this
coupling is an accepted MVP tradeoff rather than the target end state.

## Ingestion flow

```mermaid
sequenceDiagram
    participant U as Client
    participant I as ingestion-service
    participant R as DocumentReaderFactory
    participant S as TokenTextSplitter
    participant O as Ollama (embeddings)
    participant P as pgvector

    U->>I: POST /api/v1/documents (PDF/DOCX/MD/TXT)
    I->>R: read(file)
    R-->>I: List<Document> (one per page/section)
    I->>S: split into ~800-token chunks
    S-->>I: List<Document> chunks + metadata
    I->>O: embed(chunk text)
    O-->>I: float[] vector
    I->>P: INSERT INTO vector_store (content, metadata, embedding)
    I-->>U: 201 { documentId, source, pageCount, chunkCount }
```

## Query flow

`POST /api/v1/ask` is a single entry point: `rag-service` checks the question for
diagram-intent keywords ("diagram", "draw", "flow", "architecture", ...) and routes to
either a text answer or a Mermaid diagram — see
[ADR 0006](adr/0006-unified-ask-endpoint-with-keyword-routing.md). The underlying
single-purpose endpoints (`/api/v1/chat`, `/api/v1/diagrams`) still exist for callers
that already know which one they want.

```mermaid
sequenceDiagram
    participant U as Client
    participant C as rag-service
    participant O as Ollama
    participant P as pgvector

    U->>C: POST /api/v1/ask { question }
    C->>O: embed(question)
    O-->>C: float[] vector
    C->>P: similaritySearch(vector, topK, threshold)
    P-->>C: List<Document> (top matches + score)
    alt question asks for a diagram
        C->>O: generate Mermaid definition (system + context, question)
        O-->>C: flowchart definition
        C-->>U: 200 { type: "diagram", mermaid, citations[] }
    else
        C->>O: chat(system + context, question)
        O-->>C: generated answer
        C-->>U: 200 { type: "answer", answer, citations[] }
    end
```

Citations in the response are built directly from the documents the vector search
returned (source file, chunk index, similarity score) rather than parsed out of the
LLM's free-text answer — see [ADR 0004](adr/0004-citations-from-retrieval-not-llm.md).
