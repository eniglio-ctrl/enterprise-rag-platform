# ADR 0015: Observability stack (Prometheus + Grafana)

## Status
Accepted

## Context
All three Java services already expose `/actuator/prometheus` (Micrometer +
`micrometer-registry-prometheus` were on the classpath since Fase 0) — HTTP request
metrics and JVM metrics were already being produced, just never consumed. The plan's
own scope (`05-PROXIMOS-PASSOS.md`, item 6) called for closing that loop: a Prometheus
+ Grafana stack in `docker-compose.yml`, dashboards checked into the repo (not
configured once by hand and lost on a fresh environment), and business metrics
instrumented directly in the services doing the actual RAG work.

## Decision
- **Prometheus + Grafana over an alternative stack** (e.g. OpenTelemetry + Jaeger for
  distributed tracing): metrics, not traces, were the actual gap — Micrometer's
  Prometheus registry was already present and doing nothing. Distributed tracing
  across `ingestion-service`/`rag-service`/`chat-service`/Ollama is a real, separate
  gap worth its own future item, not folded into this one.
- **Dashboards provisioned as versioned JSON** (`observability/grafana/dashboards/*.json`
  + `provisioning/dashboards/dashboards.yml` pointing at that folder), not clicked
  together once in the UI — a fresh `docker compose up` gets the same dashboard
  automatically, matching the plan's own "não deixar como configuração manual feita
  uma vez só" requirement.
- **Anonymous Viewer access on Grafana** (`GF_AUTH_ANONYMOUS_ENABLED: true`,
  `GF_AUTH_ANONYMOUS_ORG_ROLE: Viewer`): this is a local portfolio demo, not a
  multi-user environment — removing the login step for `localhost:3001` matters more
  here than access control does. `GF_SECURITY_ADMIN_PASSWORD` is still set (non-default)
  for the one case someone does want to edit provisioning-locked dashboards.
- **Business metrics instrumented directly where the work happens**, not bolted on
  from outside: `Counter`/`Timer` fields injected via `MeterRegistry` in
  `DocumentIngestionService` (`rag.documents.ingested`, `rag.chunks.ingested`,
  `rag.ingestion.duration`), `RagQueryService` (`rag.answers.generated`,
  `rag.diagrams.generated`, `rag.answer.generation.duration`,
  `rag.diagram.generation.duration`), and `ConversationService`
  (`chat.messages.exchanged`, `chat.message.duration`). Early-return branches (empty
  retrieval, diagram fallback) are handled by wrapping the whole method in a private
  `doX()` delegate under `Timer.record(Supplier)`, rather than threading `Timer.Sample`
  start/stop calls through every branch.
- **`percentiles-histogram` enabled explicitly** for `http.server.requests` and each
  custom Timer (`application.yml`, `management.metrics.distribution.percentiles-histogram`)
  — without this, Micrometer only exports a naive average (`_sum`/`_count`), and the
  dashboard's p95 latency panel (`histogram_quantile(0.95, ...)`) would have silently
  returned no data.

## Consequences

- **Real bug found and fixed, not hypothetical**: the ingestion counter was originally
  named `rag.chunks.created`, which Micrometer's Prometheus naming convention silently
  rewrote to `rag_chunks_total` — Prometheus/OpenMetrics reserves the `_created` suffix
  for a different purpose (a counter's creation timestamp), so Micrometer strips a
  trailing `.created` from the metric name entirely. Caught by querying the raw
  `/actuator/prometheus` output directly and noticing the dashboard's
  `rag_chunks_created_total` query returned nothing, while `rag_documents_ingested_total`
  (no reserved word) worked fine. Fixed by renaming the meter to `rag.chunks.ingested`
  — sidesteps the naming collision entirely rather than fighting Prometheus's
  convention, and reads better next to `rag.documents.ingested` anyway.
- **Verified for real**: rebuilt the three touched images, brought up the full
  9-service `docker-compose` stack (all healthy on the first try), confirmed all three
  Prometheus scrape targets reached `up`, uploaded a real document and asked a real
  question through the running stack, and confirmed via screenshot that the
  auto-provisioned Grafana dashboard rendered real, non-zero data end to end: HTTP
  request rate split by service, JVM heap usage, and the business panel showing the
  real ~45s answer-generation timer alongside the ingestion counter.
- **Metric names double as a contract with the dashboard JSON**: renaming a
  `Counter`/`Timer` in code without updating the corresponding PromQL `expr` in
  `observability/grafana/dashboards/rag-platform-overview.json` produces a silently
  empty panel, not an error — worth checking both together whenever either changes.
- **Distributed tracing remains a known, separate gap** (Ollama calls, HTTP calls
  between services) — not addressed here, left as a candidate for a future item if the
  latency-composition problem (retrieval → rerank → generation → groundedness, ADR
  0009's risk #6) needs deeper visibility than aggregate Timers provide.
