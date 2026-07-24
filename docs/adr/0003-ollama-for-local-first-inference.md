# ADR 0003: Ollama for local-first embeddings and chat

## Status
Accepted

## Context
The project needs an embedding model and a chat model. Using a hosted API
(OpenAI, Bedrock, Vertex) means anyone cloning the repo needs an account and
an API key just to run `docker compose up`, and every ingestion/query call
has a cost and a network dependency.

## Decision
Run models locally through Ollama (`nomic-embed-text` for embeddings,
`llama3.1` for chat), wired through Spring AI's Ollama starter. Both services
set `spring.ai.ollama.init.pull-model-strategy: when_missing`, so the required
models are pulled automatically on first startup — no manual `ollama pull`
step.

## Consequences
- `docker compose up --build` is genuinely one command, with no signup or
  API key required.
- Answer quality and latency are lower than a frontier hosted model; not the
  goal of this project.
- Spring AI's `ChatModel` / `EmbeddingModel` interfaces mean swapping to
  OpenAI, Bedrock or Vertex is a dependency + configuration change, not a
  rewrite of `RagQueryService` or `DocumentIngestionService`.
- First startup is slow while models download (several GB); documented in the
  root README.
