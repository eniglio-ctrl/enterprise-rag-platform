# ADR 0008: Opt-in groundedness check as a second LLM call

## Status
Accepted

## Context
[ADR 0004](0004-citations-from-retrieval-not-llm.md) already guarantees that the
`citations` array is accurate to what was actually retrieved, but it explicitly does
not guarantee that every sentence in the generated prose is faithfully supported by
those chunks — the model could still write a claim that isn't backed by the context it
was given. ADR 0004 flagged this as a roadmap item; this ADR implements it.

## Decision
- After generating an answer, `RagQueryService.answer()` can optionally make a
  **second** call to the chat model, asking it to classify the answer it just produced
  as `SUPORTADA` (supported) or `NAO_SUPORTADA` (not supported) given the same
  retrieved context. Temperature `0.0`, same reasoning as diagram generation
  (ADR 0005): this is a classification, not prose, so deterministic output beats
  variety.
- **Opt-in per request**, not a global setting: `ChatRequest.grounded` (nullable
  `Boolean`, default `false`/absent). A second full LLM call roughly doubles the
  latency of an already-slow local CPU inference path — making it mandatory would hurt
  every caller to benefit only the ones who need the extra confidence check.
- The verdict is exposed as `ChatResponse.groundedness` / `AskResponse.groundedness`,
  an enum (`SUPPORTED` / `NOT_SUPPORTED`), `null` when not requested. It's presented as
  extra information for the caller to judge, not used to suppress or alter the answer —
  consistent with how citations are already handled.
- Diagram generation does **not** get a groundedness check. A Mermaid diagram isn't a
  set of textual claims in the same sense a prose answer is; this ADR scopes the check
  to `answer()`/`/api/v1/chat` (and the "answer" branch of `/api/v1/ask`) only.
- Verdict parsing checks for `NAO_SUPORTADA` before `SUPORTADA` (the former contains
  the latter as a substring) and falls back to `SUPPORTED` with a logged warning if the
  model's output doesn't match either — an optimistic default, since this is an
  informational signal, not a hard gate blocking the response.

## Consequences
- **Real latency measured**, not estimated: a grounded request against the actual
  local `llama3.1` model completed in ~80s end-to-end, barely more than the ~75s
  already observed for an equivalent ungrounded question. The verification call adds
  little wall-clock time in practice because its output is a single short token
  (`SUPORTADA`/`NAO_SUPORTADA`), unlike the primary answer's full prose generation —
  the dominant cost is prompt processing + the first generation, not the second call.
  This may not hold for longer answers/contexts; re-measure if `top-k` or context size
  changes materially.
- **Critical test-infrastructure fix required first**: `ChatQueryIT`'s mocked
  `ChatModel` previously returned one canned response for *any* prompt
  (`given(chatModel.call(any(Prompt.class))).willReturn(...)`). With two different
  prompts now hitting the same mock (the answer prompt and the verification prompt), a
  single canned response would have made `grounded=true` silently "pass" without ever
  exercising the `NOT_SUPPORTED` path. Fixed by switching to `willAnswer(...)` that
  inspects `prompt.getSystemMessage().getText()` and returns a different canned verdict
  depending on which prompt was sent — this is now the default stubbing for every test
  in the class, not just the new groundedness ones.
- No new failure mode beyond what already exists for any chat call: if the
  verification call itself fails (timeout, model error), it propagates as a request
  failure rather than silently omitting `groundedness` — same handling as the primary
  answer call, no separate try/catch was added for it.
