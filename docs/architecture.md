# Architecture

## Overview

`enterprise-rag-platform` is a local-first RAG platform composed of four Spring Boot
services, a static web UI, PostgreSQL/pgvector, and local AI runtimes. The browser
talks directly to the service that owns each capability; `chat-service` is currently
available through its own API and is not yet wired into `web-ui`.

```mermaid
flowchart LR
    U["Browser / API client"]

    subgraph Platform["enterprise-rag-platform"]
        WEB["web-ui :3000"]
        AUTH["auth-service :8084"]
        ING["ingestion-service :8081"]
        RAG["rag-service :8082"]
        CHAT["chat-service :8083"]
        PG[("PostgreSQL 16\npgvector + application schemas")]
        OLL["Ollama\nembeddings, default chat and vision"]
        LMS["LM Studio (optional)\nOpenAI-compatible chat server"]
        WHISPER["Whisper ASR\noptional audio transcription"]
    end

    U --> WEB
    U -- "register / login" --> AUTH
    WEB -- "Bearer JWT + upload" --> ING
    WEB -- "Bearer JWT + ask" --> RAG
    U -- "Bearer JWT + conversations" --> CHAT
    ING -. "validates JWT through JWKS" .-> AUTH
    RAG -. "validates JWT through JWKS" .-> AUTH
    CHAT -. "validates JWT through JWKS" .-> AUTH
    ING -- "chunks + embeddings" --> PG
    ING -- "embedding / image description" --> OLL
    ING -- "audio transcription" --> WHISPER
    RAG -- "hybrid retrieval" --> PG
    RAG -- "embeddings, default chat, vision" --> OLL
    RAG -. "optional selected-model chat" .-> LMS
    CHAT -- "conversation memory" --> PG
    CHAT -- "retrieve relevant chunks" --> RAG
    CHAT -- "conversation-aware generation" --> OLL
```

`auth-service` issues RS256 JWTs containing `tenantId` and `userId`; the other three
Spring services act as resource servers and validate those tokens using its JWKS
endpoint. Retrieval and conversations are tenant-scoped. See
[ADR 0016](adr/0016-auth-service-jwt-oauth2.md) and
[ADR 0007](adr/0007-tenancy-data-contract.md).

Ollama is the default local runtime and is responsible for embeddings and vision. LM
Studio is optional: when its model is selected for a request, `rag-service` calls its
locally running OpenAI-compatible server for chat generation, while embeddings remain
on Ollama. See [ADR 0017](adr/0017-selectable-chat-model-ollama-lmstudio.md) and
[ADR 0025](adr/0025-auto-model-selection.md).

`platform-common` contains shared CORS, OpenAPI, error-handling, resilience and
resource-server security code. PostgreSQL deliberately remains shared while the system
is at portfolio/MVP scale; this is a documented trade-off, not an accidental boundary.
See [ADR 0010](adr/0010-platform-common-module.md) and
[ADR 0002](adr/0002-shared-database-between-services.md).

## Ingestion flow

```mermaid
sequenceDiagram
    participant U as Client
    participant I as ingestion-service
    participant V as Upload validator
    participant R as Document reader
    participant O as Ollama / Whisper
    participant P as PostgreSQL + pgvector

    U->>I: POST /api/v1/documents (Bearer JWT, file)
    I->>V: verify extension, declared type and magic bytes
    V-->>I: accepted file or 415/422
    I->>R: extract text (PDF, DOCX, MD or TXT)
    R-->>I: documents/pages
    alt image
        I->>O: create vision description
        O-->>I: textual description
    else audio
        I->>O: transcribe through Whisper ASR
        O-->>I: transcript
    end
    I->>I: split text into chunks and attach tenant metadata
    I->>O: embed chunks
    O-->>I: vectors
    I->>P: persist chunks and vectors
    I-->>U: 201 document id, source, pages and chunks
```

The original binary image is not indexed: its vision-generated description is. Audio is
transcribed before it enters the same chunk/embed/store pipeline. Validation happens
before parsers or models receive the upload. See [ADR 0018](adr/0018-image-ingestion-via-vision-model.md),
[ADR 0019](adr/0019-audio-ingestion-via-local-whisper.md), and
[ADR 0022](adr/0022-upload-validation-hardening.md).

## Query flow

`POST /api/v1/ask` is the web UI's unified entry point. It first asks the resolved chat
model for a temperature-0, one-word intent classification: `DIAGRAMA` or `RESPOSTA`.
This replaced the original keyword router after an image question was incorrectly sent
to diagram generation. Callers that already know the operation can use `/api/v1/chat`
or `/api/v1/diagrams` directly.

```mermaid
sequenceDiagram
    participant U as Client
    participant R as rag-service
    participant O as Chat / embedding model
    participant P as PostgreSQL + pgvector

    U->>R: POST /api/v1/ask (Bearer JWT, question, optional image)
    opt image attached
        R->>O: describe image once
        O-->>R: image description (ephemeral)
    end
    R->>O: classify intent: DIAGRAMA or RESPOSTA
    O-->>R: verdict
    R->>O: embed question
    O-->>R: question vector
    R->>P: vector + full-text search, fused with RRF
    P-->>R: tenant-scoped candidate chunks
    opt rerank=true
        R->>O: rank wider candidate set
        O-->>R: reordered chunks
    end
    alt DIAGRAMA
        R->>O: generate Mermaid from retrieved context
        O-->>R: Mermaid definition
        R-->>U: diagram + retrieval citations
    else RESPOSTA
        R->>O: generate grounded answer
        O-->>R: text answer
        opt grounded=true
            R->>O: assess answer against context
            O-->>R: SUPORTADA or NAO_SUPORTADA
        end
        R-->>U: answer + citations + optional groundedness
    end
```

Citations are built from the chunks actually retrieved, rather than parsed from model
output. Hybrid retrieval uses pgvector similarity and PostgreSQL full-text search fused
with reciprocal-rank fusion (RRF); optional LLM reranking and groundedness checks add
extra model calls. See [ADR 0004](adr/0004-citations-from-retrieval-not-llm.md),
[ADR 0012](adr/0012-hybrid-search-rrf-llm-rerank.md),
[ADR 0008](adr/0008-groundedness-check.md), and
[ADR 0024](adr/0024-llm-based-ask-routing.md).

## Multi-turn conversation flow

`chat-service` owns conversation lifecycle and memory. It does not duplicate RAG
retrieval: for every message it forwards the caller's bearer token to `rag-service`'s
retrieval-only endpoint, then generates a response using the retrieved context plus the
conversation history stored in PostgreSQL.

```mermaid
sequenceDiagram
    participant U as Client
    participant C as chat-service
    participant R as rag-service
    participant P as PostgreSQL
    participant O as Ollama

    U->>C: POST /conversations/{id}/messages (Bearer JWT)
    C->>R: POST /retrieve (same Bearer JWT, message)
    R-->>C: relevant tenant-scoped chunks + citations
    C->>P: load conversation history
    P-->>C: prior messages
    C->>O: generate with history + retrieved context
    O-->>C: answer
    C->>P: store user and assistant messages
    C-->>U: answer + citations
```

See [ADR 0013](adr/0013-chat-service-conversation-memory.md).
