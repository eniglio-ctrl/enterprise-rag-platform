# Roadmap — execution order across every pending initiative

> This file doesn't replace
> [`docs/SECURITY-HARDENING-ROADMAP.md`](SECURITY-HARDENING-ROADMAP.md) or
> [`docs/MULTI-LLM-ORCHESTRATOR-ROADMAP.md`](MULTI-LLM-ORCHESTRATOR-ROADMAP.md)
> — those two still own all the implementation detail, "done when" criteria,
> and per-phase context for their own concerns. This file is the single thing
> to open when the question is **"what do we actually do next, in what
> order?"** across *both* of them plus the couple of standalone items that
> never made it into either. Update the checkboxes as items land — this is a
> living document, same convention as the other two.
>
> **Deliberately no deep-links to specific `##` headings in the other two
> files** — heading-anchor slugs for titles with emoji/backticks/em-dashes
> are fragile across renderers (confirmed while writing this: an earlier
> draft of this exact file had a heading accidentally split across two lines
> in `MULTI-LLM-ORCHESTRATOR-ROADMAP.md`, which is exactly the kind of
> mistake deep-linking makes costly to get wrong silently). Each item below
> just names its phase number — open the file and search for "Phase N."

## Why this exists

Two living roadmaps now exist (security hardening; the broader AI-engineering
skill roadmap), plus a Kubernetes gap the README has tracked on its own since
before either roadmap existed. Twenty-six items are tracked across all three
places (up from twenty-five — a real accent/diacritic-insensitivity gap in
hybrid search's full-text leg, found while exercising the Phase 2c fallback
flow, added as its own Tier 1 item rather than folded silently into an
existing one). They don't have to happen in roadmap-file order or phase-number
order — several have no real dependency on anything and can start today;
others share infrastructure in ways worth sequencing deliberately (e.g. the
same rate-limit filter both a security phase and an AI-roadmap phase need);
others are blocked on a decision only the user can make (a paid API key, an
AWS account, a second language). This file sorts all of them into that shape.

## Portfolio-ready stopping point

**Tiers 1 and 2 alone already make this a complete, deliverable portfolio
project.** Nothing in either tier costs real, ongoing money — everything is
either free/self-hosted or a one-time dev-time investment. Tier 3 is
explicitly optional: real cloud LLM providers, LangFuse, AWS, and a second
language are each a genuine, *recurring* cost (a paid API bill, an AWS bill,
an ongoing second-codebase maintenance burden) — not a checkbox to clear for
its own sake. Do a Tier 3 item later, if and only if it's genuinely wanted
for its own reason (a real job requirement calling for AWS specifically, a
real desire to learn LangGraph hands-on) — not because "the roadmap says
there are 21 items and only 15 are done." A smaller, fully-defensible project
beats a longer one with rushed, shallow corners — see each roadmap file's own
"corrected premises" sections for why that discipline matters here
specifically, not just in the abstract.

## Tier 1 — Ready to start now, no external blocker

Recommended order (earlier items make later ones easier, not the other way
around):

1. ✅ **Kubernetes: add `auth-service`'s Deployment+Service** — closed. A gap
   the README tracked since ADR 0014 (the manifests predated `auth-service`
   entirely); see ADR 0014's "Update" section for what changed and how it
   was verified against a real `kind` cluster (all 7 pods `Running`/`Ready`,
   zero restarts; a real register → upload → ask round trip through the
   in-cluster `auth-service`).
2. ✅ **Security Phase 7 — Supply-chain security** — closed. Dependabot +
   CodeQL live on the repo (see [ADR 0026](adr/0026-supply-chain-security-phase7.md));
   secret scanning was already on by GitHub default. 15 real dependency PRs
   merged, 14 real major-version breaks closed and suppressed.
3. ✅ **Multi-LLM Phase 14 — SonarQube + `docs/architecture.md` refresh** —
   closed. `docs/architecture.md` was already current; JaCoCo + a real
   SonarCloud CI analysis are live (see [ADR 0027](adr/0027-sonarcloud-jacoco-code-quality.md)).
   The first real run found 5 real bugs and 4 real vulnerabilities — each
   triaged on its own merits (2 fixed, 1 log-injection fix, 6 marked false
   positive/won't-fix with real justification, not blanket-dismissed) —
   and the quality gate went from `ERROR` to `OK` by closing an actual
   `new_coverage` gap across 3 short iterations, never by disabling a check.
4. ✅ **Security Phase 2 — Rate limiting** — closed. Bucket4j-based
   `RateLimitFilter` shared across all 4 services (see
   [ADR 0028](adr/0028-rate-limiting.md)), verified against the running
   stack (11 login attempts, 11th `429`; forged `X-Forwarded-For` confirmed
   to have zero effect). Its filter/metric are ready for Security Phases
   5/6 to reuse, and its in-memory, non-distributed design is one of the
   few genuinely concrete justifications for Tier 2's Redis item — decide
   that only now that this phase actually exists to reference.
5. ✅ **Security Phase 3 — Secrets, CORS, HTTP headers** — closed. No more
   real-looking default DB/Grafana credentials in `docker-compose.yml`
   (requires `.env`, fails clearly without it), CORS headers narrowed to
   what `web-ui` actually sends, and a real CSP on `web-ui`'s nginx —
   verified against the actual diagram feature (Mermaid's inline styles) in
   a live browser, not just `curl -I` (see
   [ADR 0029](adr/0029-secrets-cors-http-headers.md)).
6. ✅ **Multi-LLM Phase 8 — RAG quality deep-dive** — closed. Two new
   structure-aware splitters (`RecursiveCharacterTextSplitter`,
   `MarkdownAwareTextSplitter`) both beat the production `TokenTextSplitter`
   baseline by ~0.10 average answer similarity (~14% relative) on a real
   document, measured, not asserted; the benchmark now also reports
   faithfulness (10/10, reusing ADR 0008's groundedness check) and context
   relevance (0.20 average, explained by the benchmark's shared-tenant
   corpus, not a bug) per question, not just one cosine-similarity number
   (see [ADR 0034](adr/0034-rag-quality-chunking-and-evaluation-metrics.md)).
   Not wired into the real ingestion pipeline yet — a separate follow-up
   decision, not made by this investigation alone.
7. ✅ **Multi-LLM Phase 9 — Native tool/function calling** — closed. One
   `@Tool` (`DocumentLookupTool.lookupDocumentBySource`, `rag-service`) lets
   the model fetch a whole document by exact filename when a question names
   it directly (e.g. "resuma o documento X.md"). `tenantId` comes from
   Spring AI's `ToolContext`, never a model-supplied parameter — the one
   security-critical decision in this phase, since exposing it to the model
   would let a crafted prompt read another tenant's data. Verified for real
   against the running local stack: uploaded a real document, asked a real
   question, and confirmed via the actual server log (not inferred from the
   answer) that `llama3.1` invoked the tool with the correct filename and
   the caller's real tenant id (see
   [ADR 0035](adr/0035-native-tool-calling.md)). Known, named limitation:
   tool-fetched content doesn't get its own citation entry yet.
8. ✅ **Multi-LLM Phase 2a — Fallback provider wiring: OpenAI + Gemini** —
   done, with one real caveat. `GeminiClient` (plain REST client — Spring AI
   1.0.0 has no plain-API-key Gemini integration, only Vertex AI) and a
   second, manually-built `OpenAiChatModel` bean (Spring AI's OpenAI
   autoconfiguration only supports one provider slot, already claimed by LM
   Studio) are both wired behind `LlmGateway.callGeminiFallback`/
   `callOpenAiFallback`, each with its own independent Resilience4j breaker.
   Real API verification: **Gemini fully works end-to-end**
   (`gemini-flash-latest` returns real generated text); **OpenAI's key
   authenticates but the account has zero credits** (`HTTP 429
   insufficient_quota`) — a real, external blocker only the user's own
   OpenAI-console billing action can resolve, not a code problem. Circuit
   breaker isolation verified for real too: repeated OpenAI failures tripped
   only its own breaker while Gemini's stayed closed and kept answering.
   Neither provider is in `rag.available-models` — not a dropdown option, a
   confirmed, non-grounded fallback for when the local path fails or finds
   nothing (see [ADR 0036](adr/0036-fallback-provider-wiring-openai-gemini.md)
   and Phase 2's full writeup for why this is a deliberate, visible exception
   to ADR 0004, not a silent one). Phases 2b-2e (trigger detection,
   confirmation gate, `web-ui` dialog, Anthropic) are still not started.
9. ✅ **Multi-LLM Phase 2b — Fallback trigger detection** — done.
   `FallbackTriggerEvaluator` triggers on either an open local circuit
   breaker (`ollama`/`lmstudio`) or empty retrieval — structural detection,
   never keyword matching in the answer text, same principle ADR 0024
   already established for routing. Found and corrected a real premise
   error while implementing: the plan assumed the citation's retrieval
   score was cosine-similarity scale; it's actually the post-RRF-fusion
   score, a much smaller, incomparable scale, so the empty-retrieval check
   (already backed by the existing cosine `similarity-threshold` applied
   before fusion) is the correct signal instead of a new, guessed threshold
   (see [ADR 0037](adr/0037-fallback-trigger-detection.md)). Verified via 5
   unit tests against a real `CircuitBreakerRegistry`, not mocked state.
   Not wired into any real response yet — Phase 2c's job.
10. ✅ **Multi-LLM Phase 2c — Confirmation gate + non-grounded response
    contract** — done. `ChatRequest.useFallback`/`fallbackProvider` and
    `ChatResponse.fallbackAvailable`/`source` implement the two-step flow
    (offer → explicit confirm → call). Incidentally fixed a real,
    pre-existing bug: an open local circuit breaker used to reach the
    doomed call and surface as an unhandled 500 — the Phase 2b trigger now
    runs before generation instead. Verified live against the running
    stack: a real question with no local context got `fallbackAvailable:
    true`, the confirmed follow-up got a real `gemini-flash-latest` answer
    marked `source: "public-llm"`. Known, undecided-on-purpose gap: a
    confirmed fallback call that itself fails (OpenAI's real zero-credits
    state) still surfaces as a generic 500 — a proper error contract for
    that is deferred to Phase 2d (see
    [ADR 0038](adr/0038-fallback-confirmation-gate-response-contract.md)).
11. ⬜ **Multi-LLM Phase 2d — `web-ui`: confirmation dialog + provenance
    badge** (after #10) — the one-screen dialog naming both the cost and the
    "not from your documents" warning together, plus a visibly distinct
    badge on the answer itself.
12. ⬜ **Multi-LLM Phase 2e — Fallback provider wiring: Anthropic** (same
    shape as #8, once #8-#11 exist) — deliberately deferred: the user will
    generate `ANTHROPIC_API_KEY` specifically when this item starts, not
    before, unlike OpenAI/Gemini's keys which were obtained ahead of time.
13. ⬜ **Hybrid search: accent/diacritic-insensitive full-text matching** —
    a real gap found while using the fallback flow: `HybridSearchService`'s
    full-text leg indexes `content_tsv` via `to_tsvector('simple', ...)`
    (ADR 0011/0012), and Postgres's `'simple'` text search configuration
    does **not** strip accents/diacritics — "informação" and "informacao"
    tokenize differently, so a question typed without accents (common —
    quick typing, some keyboards) can silently miss full-text-indexed
    content that has them, or vice versa. The vector/embedding leg is
    largely unaffected (semantic similarity, not exact tokens), so this is
    specifically a full-text-leg gap, not a whole-retrieval one. Fix: enable
    Postgres's built-in `unaccent` extension and a custom text search
    configuration copying `simple` but mapping through `unaccent` first
    (`CREATE TEXT SEARCH CONFIGURATION ... (COPY = simple); ALTER ... ALTER
    MAPPING ... WITH unaccent, simple;`), then point both the generated
    `content_tsv` column (needs a new Flyway migration — generated column
    expressions can't be altered in place, only dropped and re-added) and
    `HybridSearchService`'s `to_tsquery(...)` calls at it instead of
    `'simple'`. "Especial characters" beyond accents (e.g. hyphenated
    compounds like "e-commerce") are a related but distinct tokenization
    question `buildOrTsQuery`'s existing alphanumeric stripping doesn't
    fully resolve either — worth a real test case, not assumed fixed by the
    same change. **Done when**: a real test indexes a chunk containing an
    accented word, a question using the unaccented spelling (and vice
    versa) still retrieves it via the full-text leg specifically (not
    coincidentally via the vector leg alone) — verified with a targeted
    `HybridSearchServiceTest`/integration test, plus a real `curl` round
    trip against the running local stack, not just asserted from reading
    the SQL.

## Tier 2 — needs a design/infra decision first, no money

Not blocked on a paid resource, but shouldn't start until a concrete decision
is made (see each phase's own "not started" note in its home file for
exactly what that decision is):

14. ✅ **Security Phase 5 — Audit logging** — closed. A shared correlation ID
    across every service (a servlet filter registered ahead of Spring
    Security entirely), structured audit events for login/registration/
    upload/access-denied, two new metrics, and a Grafana "Segurança" row.
    Found and fixed a real pre-existing bug in the process: `auth-service`'s
    own `/actuator/prometheus` had been silently unreachable by Prometheus
    since Security Phase 4 (see
    [ADR 0032](adr/0032-security-audit-logging-and-monitoring.md)).
15. ✅ **Security Phase 6 — Public demo hardening** — closed, the last phase
    in the whole security hardening rollout. Found real public exposure on
    the live demo first (`curl` showed `/actuator/prometheus`,
    `/actuator/metrics`, `/v3/api-docs`, and Swagger UI all reachable) and
    locked all of it down to `/actuator/health` only; tightened the demo's
    rate limit to 10/min per IP; gave the demo its own Netlify-native CSP
    (`web-ui/_headers`); researched (not guessed) Render's real
    `X-Forwarded-For` behavior and documented why `trusted-proxy-hops`
    deliberately stays `0` (see [ADR 0033](adr/0033-public-demo-hardening.md)).
16. ⬜ **Multi-LLM Phase 5 — Redis** — decide whether Tier 1 #4's
    distributed rate-limiting need actually justifies it, or skip until a
    clearer justification exists.
17. ✅ **Security Phase 4 — Tenants/invitations + persistent JWT key** —
    closed. Free-text `tenantId` registration replaced by a real
    invitation model (single-use, 7-day expiry, exact-email match, all
    enforced atomically); `JwtKeyProvider` now loads a persisted RSA key
    (mounted secret file or Base64 env var) instead of regenerating one on
    every restart. Verified against the real docker-compose stack, including
    an actual `auth-service` container restart with a pre-restart token
    still validating afterward (see
    [ADR 0031](adr/0031-tenant-invitations-and-persistent-jwt-key.md)). Was
    sequenced here by size, not a real technical blocker, exactly as this
    entry originally said — closing it didn't need anything else to land
    first.
18. ⬜ **Multi-LLM Phase 10 — Reframe agents around capability** (after
    Tier 1 #7)
19. ⬜ **Multi-LLM Phase 6 — Tools via MCP** (after Tier 1 #7; still needs
    its own scope cut to 1-2 concrete tools)
20. ⬜ **Multi-LLM Phase 11 — Event-driven architecture (Kafka/RabbitMQ)** —
    needs a concrete driving use case (the phase's own text suggests async
    document ingestion) and a provisioning decision (Kafka vs. RabbitMQ),
    not a paid key.
21. ⬜ **New — Go-based API Gateway / BFF** (not yet written up as its own
    phase in either file — see "Where Go actually fits" below for the full
    reasoning). Addresses the still-unaddressed "API Gateway" microservices
    pattern from the AI-engineer checklist, and is a genuine, low-risk way to
    get real Go usage into this portfolio with a concrete performance/memory
    rationale, not a speculative "let's use Go somewhere." Scope to decide
    before starting: does it just do routing + JWT pass-through validation,
    or also take over Security Phase 2's rate limiting at the edge instead
    of per-service filters?

## Tier 3 (optional) — real, ongoing cost or a big commitment

**Not required for a good, deliverable portfolio** — see "Portfolio-ready
stopping point" above. Nothing here starts without the user explicitly
signing off on the specific cost/commitment named, *and* wanting that
specific item for its own sake, not just to advance the list — see each
phase's own text for exactly what the cost/commitment is:

22. ⬜ **Multi-LLM Phase 3 — `PlannerAgent`** (after Tier 1 #8-#12 — note
    this assumes genuinely selectable multiple providers, which the Phase 2
    fallback design deliberately does *not* provide; may need its own
    provider wiring)
23. ⬜ **Multi-LLM Phase 4 — `ReflectionAgent`** (after #22 — note this
    multiplies paid API calls per question)
24. ⬜ **Multi-LLM Phase 7 — Observability (LangFuse + OpenTelemetry)** (a
    LangFuse account/hosting decision)
25. ⬜ **Multi-LLM Phase 12 — AWS deployment target** (an AWS account +
    explicit acceptance of real, non-free-tier cost for some of what's in
    scope, e.g. Bedrock/OpenSearch)
26. ⬜ **Multi-LLM Phase 13 — Python + LangGraph AI layer** (confirm this
    portfolio project should become polyglot before any code — see "Where
    Python actually fits" below for why this one is *not* primarily a
    performance decision, unlike the Go item above)

## Where Go, Java, and Python actually fit (performance/memory reasoning)

Asked directly: which language for which part, for real performance/memory
reasons, not just "the checklist mentions it." Answered honestly per
language, since two of the three aren't performance plays at all:

- **Java stays the core.** All 4 existing services (`auth-service`,
  `ingestion-service`, `rag-service`, `chat-service`) keep using it — mature
  Spring AI ecosystem, already-built resilience/observability patterns
  (ADR 0009/0015), no reason to rewrite working business logic for a
  performance problem that isn't actually there at that layer. The JVM's
  real, already-*experienced* cost in this project is memory footprint on
  constrained infra — ADR 0020 documents a real OOM kill on Render's 512MB
  free tier from JVM baseline + a local embedding model together. That's the
  actual signal for where a lighter-weight language earns its place: **the
  edge**, not the domain services.
- **Go's genuine fit here: a lightweight API Gateway/BFF at the edge**
  (Tier 2 #21, new). This isn't spreading Go around speculatively — it fills
  a real, still-unaddressed gap (the checklist's "API Gateway" microservices
  pattern, currently implemented nowhere in this project) with a language
  that's *actually* the right tool for it: a Go binary's baseline memory
  footprint is roughly a tenth of a JVM's (single-digit-to-low-double-digit
  MB vs. 150MB+ before a JVM does anything), cold start is near-instant (no
  JIT warm-up), and goroutines make high-concurrency connection handling
  cheap — exactly the profile you want for something sitting in front of
  every request, and exactly the constraint this project already hit for
  real on a free-tier deployment. A rate-limiting/routing layer here would
  also be a legitimate alternative to Security Phase 2's per-service Java
  filters, worth deciding between when that phase starts.
- **Python's fit here is ecosystem, not performance — say so plainly.**
  Phase 13 exists because LangGraph/LangChain/LlamaIndex/CrewAI/DSPy are
  Python-first tools with no real Java equivalent, not because Python is
  faster or lighter than Java for request handling (it generally isn't — a
  Python service doing real work has its own real memory footprint,
  especially once any ML/NLP library is involved). Choosing Python for the
  agent-orchestration layer is "the tools I need only exist here," which is
  a perfectly good reason on its own — just not a performance/memory one,
  and Phase 13's text in `docs/MULTI-LLM-ORCHESTRATOR-ROADMAP.md` has been
  updated to say this explicitly rather than implying otherwise.

## How to use this file

- Work top to bottom within Tier 1 first — nothing there is waiting on a
  decision, so it's pure execution time.
- Move to Tier 2 items whenever their specific decision has actually been
  made (not preemptively) — check the linked phase's own "not started" text
  for exactly what that decision is.
- **Treat finishing Tier 2 as the actual finish line for this project's
  portfolio purpose** (confirmed 2026-07-29) — not Tier 3. Tier 3 items stay
  untouched indefinitely unless the user explicitly greenlights *both* the
  named cost/commitment *and* a real reason to want that specific item, not
  "the list isn't done yet." Don't infer this file's mere existence as
  standing approval to spend money or add a new language later.
- After finishing any item: check its box here, update its own status in
  whichever of the two detailed roadmap files owns it (or write it up as a
  new phase there first, for the Go item above, which doesn't have a home
  section yet), write its ADR if the decision was non-trivial, and follow
  the verification pattern both of those files already define (build green,
  container healthy, a real manual test, commit + push).
