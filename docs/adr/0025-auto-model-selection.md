# ADR 0025: "Automático" as a sentinel entry in `rag.available-models`

## Status
Accepted

## Context
The user asked for an "AUTO" option in the model dropdown — the system manages
which LLM answers automatically, while the manual list of models stays available
for anyone who wants to pick a specific one. That request arrived alongside a much
larger vision (a multi-provider orchestrator with a Planner agent, a Reflection
agent, MCP tools, Redis, LangFuse/OpenTelemetry — tracked separately in
[`docs/MULTI-LLM-ORCHESTRATOR-ROADMAP.md`](../MULTI-LLM-ORCHESTRATOR-ROADMAP.md)).
This ADR covers only the small, immediately buildable piece.

Before implementing, the actual model-selection mechanism (ADR 0017) was checked
against the real code, not assumed:

- `RagQueryService.resolveModel(String requestedModel)` is the *only* model-choice
  decision point in the entire codebase. It is purely static: a blank/null request
  or an unrecognized id both fall back to `rag.available-models`'s first entry.
  There is no dynamic, question-dependent, or load-dependent selection logic
  anywhere today.
- `chat-service` has no model-selection concept at all — it's hardcoded to one
  Ollama model and never calls `rag-service`'s generation endpoints (only
  `/api/v1/retrieve`). This ADR only touches `rag-service`.
- `GET /api/v1/models` (`ModelsController`) and `web-ui`'s `loadModels()` already
  render whatever `rag.available-models` contains generically — neither hardcodes
  provider names or model ids anywhere.

## Decision
`"auto"` is added as a real, first-position entry in `rag.available-models`
(both `application.yml` and `application-demo.yml`), with `provider: auto` — a
sentinel, not a genuinely callable provider:

```yaml
available-models:
  - id: auto
    label: "Automático (recomendado)"
    provider: auto
  - id: ${CHAT_MODEL:llama3.1}
    label: "Llama 3.1 (Ollama, padrão)"
    provider: ollama
  # ...
```

`RagQueryService.resolveModel(...)` resolves this sentinel to a genuinely
callable model in one place, once per request: after picking an entry (by
explicit id, or falling back to the first entry on blank/unknown input — both
paths unchanged from ADR 0017), a final step substitutes any `provider: auto`
result for `firstConcreteModel(available)` — the first entry in the list whose
provider isn't `"auto"`. Because `resolveModel` already runs exactly once per
request and its result threads through `clientFor`, `callLlm`, `modelOptions`,
and the `model` field returned to the caller, none of those four call sites
needed to change at all — they already only ever see a real, concrete
`AvailableModel`.

**No question-dependent or dynamic logic today, by design** — the user
confirmed "auto" should mean "use the already-configured default" for now, since
there currently isn't a real pool of distinct providers worth choosing between
intelligently (local Ollama models + one LM Studio slot locally, a single Groq
entry in the demo). `docs/MULTI-LLM-ORCHESTRATOR-ROADMAP.md` is where that would
evolve once Phase 2 (real cloud providers) exists.

**No `web-ui` changes were needed.** The dropdown is populated entirely from
`GET /api/v1/models`'s response, and the first entry is already marked
`isDefault` and pre-selected by existing client code — adding "auto" first in
the YAML config makes it appear first and pre-selected automatically.

## Consequences
- `RagQueryServiceTest`'s test fixture (`newService()`) now configures two
  entries (`auto`, `llama3.1`) instead of one, matching production shape —
  every existing test that never requests a model now exercises the real
  `auto → llama3.1` substitution path, not a hypothetical one. Two new tests
  assert the response's `model` field is the concrete id (`"llama3.1"`), never
  the literal string `"auto"`, for both an explicit `"auto"` request and no
  request at all.
- Verified against the real running stack: the dropdown shows "Automático
  (recomendado)" first and pre-selected, both locally and (after redeploy) on
  the public demo; a question asked with no model chosen returns the concrete
  model id, never `"auto"`, in the response.
- If `rag.available-models` were ever misconfigured down to only `auto` entries
  with no concrete one, `resolveModel` fails fast with an `IllegalStateException`
  rather than silently returning an uncallable sentinel.
