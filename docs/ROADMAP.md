# Roadmap — execution order across every pending initiative

> This file doesn't replace
> [`docs/SECURITY-HARDENING-ROADMAP.md`](SECURITY-HARDENING-ROADMAP.md),
> [`docs/MULTI-LLM-ORCHESTRATOR-ROADMAP.md`](MULTI-LLM-ORCHESTRATOR-ROADMAP.md),
> [`docs/PRODUCTION-READINESS-ROADMAP.md`](PRODUCTION-READINESS-ROADMAP.md),
> [`docs/EXTERNAL-DATA-INTEGRATION-ROADMAP.md`](EXTERNAL-DATA-INTEGRATION-ROADMAP.md),
> or [`docs/PRODUCT-DIFFERENTIATION-ROADMAP.md`](PRODUCT-DIFFERENTIATION-ROADMAP.md)
> — those five still own all the implementation detail, "done when" criteria,
> and per-phase context for their own concerns. This file is the single thing
> to open when the question is **"what do we actually do next, in what
> order?"** across *all five* of them plus the couple of standalone items
> that never made it into any. Update the checkboxes as items land — this is
> a living document, same convention as the other five.
>
> **Deliberately no deep-links to specific `##` headings in the other two
> files** — heading-anchor slugs for titles with emoji/backticks/em-dashes
> are fragile across renderers (confirmed while writing this: an earlier
> draft of this exact file had a heading accidentally split across two lines
> in `MULTI-LLM-ORCHESTRATOR-ROADMAP.md`, which is exactly the kind of
> mistake deep-linking makes costly to get wrong silently). Each item below
> just names its phase number — open the file and search for "Phase N."

## Why this exists

Five living roadmaps now exist (security hardening; the broader
AI-engineering skill roadmap; production readiness — added 2026-08-03;
external data integration — added 2026-08-05; and product differentiation
— added 2026-08-06, see its own file for why it's separate from the other
four), plus a Kubernetes gap the README has tracked on its own since before
any of them existed. Forty-nine items are tracked across all six places (up
from thirty-nine — ten new items added when the user gave a direct list of
what would make this project stand out against tools like NotebookLM,
plus one more (Audio Overview) the same day once a direct follow-up
question — "would this end up equivalent to NotebookLM?" — surfaced its
own flagship feature as a concrete gap; three of the original twelve items
on that list turned out to already be built, named explicitly in
[`docs/PRODUCT-DIFFERENTIATION-ROADMAP.md`](PRODUCT-DIFFERENTIATION-ROADMAP.md)
rather than silently dropped). They don't have to happen in
roadmap-file order or phase-number
order — several have no real dependency on anything and can start today;
others share infrastructure in ways worth sequencing deliberately (e.g. the
same rate-limit filter both a security phase and an AI-roadmap phase need,
or the async ingestion queue that production-readiness, cloud-drive import,
and citation highlighting all need); others are blocked on a decision only
the user can make (a paid API key, an AWS account, a second language). This
file sorts all of them into that shape.

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

**Closed out, not "production-ready complete"**: a final review before
declaring this stopping point real raised three concrete findings — see
[ADR 0049](adr/0049-closing-the-portfolio-review-gaps.md) for the full
account (a non-transactional registration path, unbounded
question/message size, and a stale public demo deploy). Fixed and verified
for real, except the demo redeploy itself, which needs the user's own
Netlify dashboard access and isn't a code fix. Per that same ADR, this
project is described as **production-minded** / **ready for production
evolution**, deliberately not **production-ready complete** — Tier 3 below
and `docs/PRODUCTION-READINESS-ROADMAP.md`'s own remaining phases are real,
known, unstarted work, not hidden gaps.

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
11. ✅ **Multi-LLM Phase 2d — `web-ui`: confirmation dialog + provenance
    badge** — done, closing the Phase 2 fallback sequence except Anthropic.
    A confirmation card (exact copy from the plan) shows when
    `fallbackAvailable: true`, and a distinct warning badge marks any answer
    whose `source` is `public-llm`. `askForm`'s logic was extracted into one
    reusable `performAsk(...)` function shared by the initial ask and the
    confirm button, so the confirm flow needed no duplicated fetch logic.
    Verified live in the browser, both directions: a question with no local
    match showed the confirmation card, confirming got a real
    `gemini-flash-latest` answer with the badge visible; a normal question
    against a real uploaded document correctly showed **no** badge (see
    [ADR 0039](adr/0039-webui-fallback-confirmation-dialog-provenance-badge.md)).
12. ✅ **Multi-LLM Phase 2e — Fallback provider wiring: Anthropic** — done,
    same shape as #8 (`spring-ai-starter-model-anthropic`, a real Spring AI
    starter unlike Gemini's, built the same manually-constructed-bean way as
    the OpenAI fallback). No `ANTHROPIC_API_KEY` was generated for this —
    the user's own instruction when starting this item was to wire it and
    handle a missing key/no credits gracefully everywhere, not to acquire a
    key first. Doing that surfaced a real, pre-existing gap: OpenAI/Gemini's
    own auth/quota failures had **no** handling at all before this — either
    would have propagated as a raw exception into a generic `500`.
    `RagQueryService.answerViaPublicLlmFallback` now checks every provider's
    API key before ever attempting a call, and catches a real rejection
    (invalid key, no credits/quota) from any of the three, returning a
    clear `source: "public-llm-unavailable"` response instead — while still
    re-throwing genuine infrastructure signals (circuit open, bulkhead
    full, no response) unchanged, so ADR 0017/0043's existing handling for
    those isn't bypassed. **Verified for real against the running stack**:
    Anthropic (genuinely no key) and OpenAI (the real zero-credits account
    from ADR 0036) both now return a graceful `200`/`"public-llm-unavailable"`
    instead of a `500`; Gemini (default) still returns a real answer,
    unaffected. 4 new `RagQueryServiceTest` cases (the happy path, the
    no-key skip, the rejected-request rescue, and confirming a circuit-
    breaker-open signal still propagates rather than being swallowed) — 71
    → 76 rag-service tests. See
    [ADR 0045](adr/0045-anthropic-fallback-and-graceful-provider-unavailability.md).
13. ✅ **Close the DOCX upload validation gap (zip-as-docx + zip bomb)** —
    done. `UploadValidationService.validateDocxStructure` walks the archive
    for real via `ZipInputStream` after the existing signature check:
    confirms `word/document.xml` is present before Tika ever sees the file,
    and bounds entry count and total uncompressed size (`ingestion.docx.max
    -entry-count`/`max-uncompressed-bytes`) **while actually decompressing**
    each entry, not by trusting the archive's own attacker-controlled size
    headers. **Verified for real against the running `ingestion-service`**:
    a genuine ZIP archive renamed to `.docx` (built with the real `zip` CLI)
    returned a clean `422` with `"DOCX file is missing word/document.xml"`;
    a hand-built, real, valid minimal DOCX still ingests normally (`201`).
    3 new tests (`UploadValidationServiceTest`, 26→29) cover the
    missing-entry, excessive-entry-count, and a genuine zip-bomb-shaped case
    (a small, highly-compressible payload decompressing well past the
    configured limit) — the pre-existing `docxBytes()` test fixture, itself
    only ever the bare signature bytes with no real ZIP structure (exactly
    the gap this closed), was replaced with a real in-memory ZIP. Real,
    unrelated infra hiccup hit and resolved along the way: Docker Desktop's
    BuildKit backend started failing every build with "DeadlineExceeded"
    mid-session; a full Docker Desktop restart (confirmed necessary — a
    build-cache prune alone did not fix it) resolved it. See
    [ADR 0022](adr/0022-upload-validation-hardening.md)'s "Update" section
    and `docs/SECURITY-HARDENING-ROADMAP.md`'s Phase 1 (now "✅ Done, no
    open gap") for the full account.
14. ✅ **Make the test suite portable across JDK vendors (Mockito as a
    Surefire Java agent)** — done. Root `pom.xml`'s `pluginManagement` gained
    a `maven-dependency-plugin:properties` execution (bound to `initialize`)
    that resolves `${org.mockito:mockito-core:jar}`, and Surefire's `argLine`
    now reads `@{argLine} -javaagent:${org.mockito:mockito-core:jar}` — the
    `@{...}` delayed-evaluation syntax (not `${...}`) is what lets this
    combine correctly with `jacoco-maven-plugin`'s own dynamic `argLine`
    injection instead of racing it. All 5 modules gained a bare
    `<plugin>` reference for `maven-dependency-plugin`, mirroring the exact
    pattern already used for `jacoco-maven-plugin` (neither is
    default-lifecycle-bound). **Verified for real**: `./mvnw clean verify`
    across all 5 modules — 179 tests, 0 failures, **zero** occurrences of
    "self-attaching"/"Dynamic loading of agents" anywhere in the build log
    (previously present on every single test run this entire session); all
    5 modules' `jacoco.xml` reports still generated correctly, confirming
    the two javaagents (JaCoCo's + Mockito's) coexist without conflict.
    **Both halves of the "done when" criterion confirmed, not just one**:
    GitHub Actions' `CI` workflow run for this exact commit completed
    `success` in 4m1s, confirming Temurin (CI's pinned vendor) behaves
    identically to Oracle's JDK (the local development machine) — see
    [ADR 0040](adr/0040-mockito-javaagent-jdk-portability.md).
15. ✅ **Wire `chat-service` into `web-ui` — a real multi-turn conversation
    UI** — closed a real, self-admitted gap: `README.md` said outright
    "`chat-service` isn't wired into `web-ui` yet ... it's reachable today
    via its own API." That left a fully-built, tested capability
    (conversation memory on top of retrieval, ADR 0013) with zero visible
    demonstration in the one flow anyone reviewing this project actually
    clicks through — a real portfolio-narrative gap, not just a technical
    one. Added a minimal multi-turn chat panel to `web-ui` (start/continue a
    conversation, send a message, see the running history with a
    per-message "Sources: ..." citation line), calling `chat-service`'s own
    existing endpoints directly — no new backend design needed, kept hidden
    under `DEMO_MODE` (same as upload/invite) since `chat-service` isn't
    part of the public demo deployment (ADR 0020). Also padded
    `chat-service`'s own thin test coverage: 2 new `ConversationIT` cases
    (GET on an unknown conversation now 404s, same as POST; a blank message
    now 400s, proving the existing `@NotBlank` is actually enforced, not
    just typed) — module test count 8→10. **Verified for real, not just
    assumed**: registered a fresh user, uploaded a real Markdown document
    about the SAGA pattern, started a conversation in the browser, asked
    "Quais são os dois modelos principais de SAGA?" (got back
    Choreography/Orchestration with a "Sources: saga-notes.md" citation),
    then asked the deliberately context-only follow-up "E o que mais?" —
    the answer correctly discussed **compensação** (a topic from the same
    document, never mentioned in either message so far), proving the
    question was resolved using the prior turn's context and the running
    conversation history, not answered in isolation. See
    [ADR 0041](adr/0041-conversation-ui-in-web-ui.md).
16. ✅ **Hybrid search: accent/diacritic-insensitive full-text matching** —
    closed a real gap found while using the fallback flow: `HybridSearchService`'s
    full-text leg indexed `content_tsv` via `to_tsvector('simple', ...)`
    (ADR 0011/0012), and Postgres's `'simple'` text search configuration does
    **not** strip accents/diacritics — "informação" and "informacao"
    tokenized differently, so a question typed without accents (common —
    quick typing, some keyboards) could silently miss full-text-indexed
    content that has them, or vice versa. The vector/embedding leg was
    largely unaffected (semantic similarity, not exact tokens) — this was
    specifically a full-text-leg gap, not a whole-retrieval one. Fix: a new
    `unaccent_simple` text search configuration (`CREATE EXTENSION unaccent;
    CREATE TEXT SEARCH CONFIGURATION unaccent_simple (COPY = simple); ALTER
    ... ALTER MAPPING FOR hword, hword_part, word WITH unaccent, simple;`),
    a new Flyway migration (V3, both `db/migration` and `db/migration-demo`)
    dropping and re-adding the generated `content_tsv` column against it
    (generated column expressions can't be altered in place), and
    `HybridSearchService`'s two `to_tsquery(...)` call sites repointed at
    `'unaccent_simple'` instead of `'simple'`. Hyphenated compounds (e.g.
    "e-commerce") were checked too, per this item's own callout that it's a
    related but distinct tokenization question — `buildOrTsQuery`'s existing
    alphanumeric stripping already splits them the same way on both the
    index and query side, confirmed by a dedicated test rather than assumed.
    **Done when, verified for real**: three new `ChatQueryIT` tests, reusing
    the same deliberately-opposite-vector trick as the existing Globodyne
    test so a match can only come from the full-text leg, not the vector leg
    — unaccented-indexed/accented-question, accented-indexed/unaccented-question,
    and the hyphenated-compound case — all passing against a real
    Postgres/pgvector Testcontainer. `ingestion-service`'s own integration
    test confirmed the V3 migration applies cleanly. Then, against the real
    running local stack: uploaded a document containing "informação",
    queried Postgres directly (`content_tsv @@ to_tsquery('unaccent_simple',
    ...)` matched both the accented and unaccented spelling), and asked
    `/api/v1/ask` the fully unaccented question "Qual o prazo de retenção da
    informação?" spelled without accents — got back the right answer with a
    citation, RRF score `0.0328 ≈ 2/61`, matching the exact "found in both
    lists at rank 1" score the unit test asserts, confirming the full-text
    leg (not just the vector leg) genuinely contributed. See
    [ADR 0042](adr/0042-unaccent-text-search-configuration.md).
17. ✅ **Operational resilience hardening** (timeouts, concurrency limits,
    readiness-vs-liveness split) — see
    [docs/PRODUCTION-READINESS-ROADMAP.md](PRODUCTION-READINESS-ROADMAP.md)
    Phase 4 for the full account. Closed all three confirmed gaps: added
    `@Bulkhead` (Resilience4j, `SEMAPHORE`, fail-fast — `max-wait-duration:
    0`) to every local-model gateway across all three services, including a
    **new**, previously-unflagged gap found while scoping this —
    `chat-service`'s own direct Ollama call had no
    `@CircuitBreaker`/`@Retry`/`@Bulkhead` at all before this, unlike its two
    siblings; split Kubernetes readiness/liveness probes onto Spring Boot's
    own `/actuator/health/readiness`/`/liveness` groups (`db` in readiness
    only, not liveness); closed 3 real timeout gaps found by the audit
    (`GeminiClient`, the OpenAI-fallback `ChatClient`, and
    `ingestion-service`'s vision-model client all had **zero** timeout
    configured before this). **Verified for real, not just in automated
    tests**: fired 8 real concurrent requests against the actual running
    stack's real Ollama — the bulkhead's 4-permit limit rejected the excess
    4 in ~155ms with a distinct message, and (an unplanned but genuine
    finding) the 4 that got through then failed for real, tripping the
    circuit breaker too, which recovered on its own 30s later — real
    evidence the two mechanisms compose correctly under a real failure, not
    just a scripted one. Also `docker compose pause postgres`'d the real
    local Postgres: `/actuator/health/liveness` stayed `200 UP` the whole
    time, `/actuator/health/readiness` correctly went `503 DOWN` after
    HikariCP's own connection-timeout elapsed, and recovered immediately on
    unpause. See [ADR 0043](adr/0043-operational-resilience-hardening.md).
18. ✅ **Backups and disaster recovery** — see
    [docs/PRODUCTION-READINESS-ROADMAP.md](PRODUCTION-READINESS-ROADMAP.md)
    Phase 9. Added `scripts/backup-postgres.sh` (`pg_dumpall`, not `pg_dump`
    — the only tool that recreates roles/databases/extensions from nothing,
    the real disaster-recovery scenario) and `scripts/restore-postgres.sh`.
    **Verified with a real drill, not just scripts that exist**: rather than
    `docker compose down -v` against the actual local dev environment (real
    risk to this session's own data), took a real backup of the real
    running Postgres, restored it alone into a brand-new, isolated
    throwaway container (deliberately bootstrapped with *different*
    credentials than production, so only the restore could make the real
    role/database exist in it), ran real `auth-service`/`rag-service`
    containers against that restored, isolated database, logged in for real
    as a pre-existing user, and asked the same real question as before the
    drill — got back the identical grounded answer (same RRF score) citing
    a document that only existed via the restore. Confirmed the real stack
    was completely untouched throughout. See
    [ADR 0044](adr/0044-backups-and-disaster-recovery.md).

## Tier 2 — needs a design/infra decision first, no money

Not blocked on a paid resource, but shouldn't start until a concrete decision
is made (see each phase's own "not started" note in its home file for
exactly what that decision is):

19. ✅ **Security Phase 5 — Audit logging** — closed. A shared correlation ID
    across every service (a servlet filter registered ahead of Spring
    Security entirely), structured audit events for login/registration/
    upload/access-denied, two new metrics, and a Grafana "Segurança" row.
    Found and fixed a real pre-existing bug in the process: `auth-service`'s
    own `/actuator/prometheus` had been silently unreachable by Prometheus
    since Security Phase 4 (see
    [ADR 0032](adr/0032-security-audit-logging-and-monitoring.md)).
20. ✅ **Security Phase 6 — Public demo hardening** — closed, the last phase
    in the whole security hardening rollout. Found real public exposure on
    the live demo first (`curl` showed `/actuator/prometheus`,
    `/actuator/metrics`, `/v3/api-docs`, and Swagger UI all reachable) and
    locked all of it down to `/actuator/health` only; tightened the demo's
    rate limit to 10/min per IP; gave the demo its own Netlify-native CSP
    (`web-ui/_headers`); researched (not guessed) Render's real
    `X-Forwarded-For` behavior and documented why `trusted-proxy-hops`
    deliberately stays `0` (see [ADR 0033](adr/0033-public-demo-hardening.md)).
21. ✅ **Secrets and configuration management for production** — done.
    Decision: HashiCorp Vault, dev mode, run locally via docker-compose —
    free, no cloud account. `auth-service`'s JWT signing key now sources
    from Vault's KV backend (same `auth.signing-key.value` property name
    `JWT_SIGNING_KEY` used to populate directly). `JwtKeyProvider` reads
    `Environment` directly and re-resolves on an `EnvironmentChangeEvent`
    (fired by `POST /actuator/refresh`) — the third design tried, after
    two that looked correct on paper and were disproved by actually
    running them: `@RefreshScope` never actually re-triggered the
    constructor in this config-data-import setup, and re-fetching
    `AuthProperties` via `ObjectProvider` raced `ConfigurationPropertiesRebinder`
    listening to the same event on a different bean. **Verified for
    real**, not just reasoned about: a new `VaultKeyRotationIT`
    (Testcontainers Vault, no mocks) rotates a real generated key through
    a real Vault and a real refresh, and confirms a token issued
    afterward verifies against a public key matching the new key's exact
    thumbprint in the same running process — plus a manual pass against the actual
    docker-compose stack, confirmed via `docker inspect`'s `StartedAt`
    that `auth-service` was never restarted. One real bug found only by
    running the test suite: Spring Cloud Vault builds its token
    authentication eagerly and throws on a blank token before
    `optional:`/fail-fast can help — fixed with a non-blank placeholder
    default. See [ADR 0048](adr/0048-vault-for-the-jwt-signing-key.md).
22. ⬜ **Multi-LLM Phase 5 — Redis** — decide whether Tier 1 #4's
    distributed rate-limiting need actually justifies it, or skip until a
    clearer justification exists. Only once there's more than one replica
    or real measured load — see
    [docs/PRODUCTION-READINESS-ROADMAP.md](PRODUCTION-READINESS-ROADMAP.md)
    Phase 7 for why that condition matters, not just a nice-to-have caveat.
    Also now has a concrete "what would this add" answer — see #37 below.
23. ✅ **Security Phase 4 — Tenants/invitations + persistent JWT key** —
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
24. ✅ **Resource-level authorization (RBAC vs. ABAC)** — done. Decision:
    ABAC (a lightweight owner + visibility + explicit-share-list model),
    not RBAC — the roadmap's own "done when" is about per-document sharing
    ("can this specific user see this specific document"), a shape RBAC
    only answers by degrading into a role per document-user pair. Reuses
    the `"userId"` metadata key already stamped at upload as the owner; a
    new `PATCH /api/v1/documents/{documentId}/sharing` endpoint
    (ingestion-service, owner-only) is the one write path; enforcement is a
    Java-level check (`DocumentVisibility.isVisibleTo`, `platform-common`,
    shared between both services) applied uniformly to all three retrieval
    paths — the hybrid search vector leg, its full-text leg, and
    `DocumentLookupTool`'s exact-match lookup (Multi-LLM Phase 9) — before
    RRF fusion runs, not filtered out after. **Verified for real against
    the running stack, exactly matching this item's own "done when"**:
    registered two real users into the *same* tenant via the real
    invitation flow (ADR 0031), uploaded a document as one, restricted it,
    confirmed the other user's questions never retrieved it while the
    owner still saw it, then shared it explicitly and confirmed the other
    user could now see it too. Two real bugs found and fixed by writing
    real tests, not assumed away: a missing `@PathVariable` name (this
    build has no `-parameters` flag) and a Postgres `uuid = character
    varying` type mismatch in the sharing repository's `UPDATE`.
    ingestion-service 47→51 tests, rag-service 76→78 tests. See
    [ADR 0046](adr/0046-resource-level-authorization-abac.md).
25. ⬜ **Multi-LLM Phase 10 — Reframe agents around capability** (after
    Tier 1 #7)
26. ⬜ **Multi-LLM Phase 6 — Tools via MCP** (after Tier 1 #7; still needs
    its own scope cut to 1-2 concrete tools — now has one, see #32/#33
    below and [docs/EXTERNAL-DATA-INTEGRATION-ROADMAP.md](EXTERNAL-DATA-INTEGRATION-ROADMAP.md)
    Phase 7)
27. ⬜ **Multi-LLM Phase 11 — Event-driven architecture (Kafka/RabbitMQ)** —
    needs a concrete driving use case (the phase's own text suggests async
    document ingestion) and a provisioning decision (Kafka vs. RabbitMQ),
    not a paid key. Bundled with separate file storage (S3/MinIO) and
    status tracking (`PENDING`/`PROCESSING`/`READY`/`FAILED`) once it
    starts — see
    [docs/PRODUCTION-READINESS-ROADMAP.md](PRODUCTION-READINESS-ROADMAP.md)
    Phase 3 for why an async queue without durable storage just moves the
    "where did the bytes go" problem rather than solving it.
28. ⬜ **New — Go-based API Gateway / BFF** (not yet written up as its own
    phase in either file — see "Where Go actually fits" below for the full
    reasoning). Addresses the still-unaddressed "API Gateway" microservices
    pattern from the AI-engineer checklist, and is a genuine, low-risk way to
    get real Go usage into this portfolio with a concrete performance/memory
    rationale, not a speculative "let's use Go somewhere." Scope to decide
    before starting: does it just do routing + JWT pass-through validation,
    or also take over Security Phase 2's rate limiting at the edge instead
    of per-service filters?
29. ✅ **New — Tenant admin role + permission-management screen** — done.
    Decision: a per-tenant `ADMIN` role, not a platform-wide super-admin —
    preserves the tenant isolation every other part of the codebase
    already enforces. Bootstrap is automatic: whoever creates a tenant
    (registers with no invitation) becomes its first ADMIN; a Flyway
    migration (`V3__user_role.sql`) backfills the same rule retroactively
    for tenants that already existed, promoting the earliest-created user
    of each. An ADMIN can, in this first version: change the sharing of
    *any* document in their tenant (the one bypass to ADR 0046's
    owner-only check — the actual reason this screen exists), list the
    tenant's members, and promote/demote them (blocked from changing their
    own role outright, so a tenant can never end up with zero ADMINs). A
    new `GET /api/v1/documents` (admin-only) fills the listing gap ADR
    0046 explicitly left unbuilt. `web-ui` gained an admin panel, visible
    only when the logged-in user's role is `ADMIN`, covering both actions.
    See [ADR 0047](adr/0047-tenant-admin-role.md).
30. ⬜ **New — External Data Integration Phase 1: import a document from a
    URL** — smallest, most contained piece; reuses the existing
    `ValidatedUpload` pipeline almost entirely. The one real decision to
    make before starting is the SSRF guardrail (reject private/internal
    IP ranges before connecting), not a design fork. See
    [docs/EXTERNAL-DATA-INTEGRATION-ROADMAP.md](EXTERNAL-DATA-INTEGRATION-ROADMAP.md)
    Phase 1.
31. ⬜ **New — External Data Integration Phase 2: import a whole local
    folder** — `web-ui` only, no backend change (`<input type="file"
    webkitdirectory>` looping the existing per-file upload call). See
    [docs/EXTERNAL-DATA-INTEGRATION-ROADMAP.md](EXTERNAL-DATA-INTEGRATION-ROADMAP.md)
    Phase 2.
32. ⬜ **New — External Data Integration Phase 3: batch import connector
    for external databases** — a settings screen for a user's own
    Postgres/MySQL connection parameters, encrypted-at-rest credential
    storage in the app's own Postgres (decided over Vault, for now, to
    start faster), and a one-time import of a table/query into pgvector
    through the existing chunking/embedding pipeline. See
    [docs/EXTERNAL-DATA-INTEGRATION-ROADMAP.md](EXTERNAL-DATA-INTEGRATION-ROADMAP.md)
    Phase 3.
33. ⬜ **New — External Data Integration Phase 4: live external-database
    query tool** (after #32, reuses its stored connections) — an
    `@Tool`-annotated capability modeled on `DocumentLookupTool`, letting
    the LLM query a connected external database at answer-time,
    read-only-enforced at the query-statement level, not just prompted to
    behave. See
    [docs/EXTERNAL-DATA-INTEGRATION-ROADMAP.md](EXTERNAL-DATA-INTEGRATION-ROADMAP.md)
    Phase 4.
34. ⬜ **New — Product Differentiation Phase 1: citation highlighting /
    source viewer** — open the original document and see the cited
    passage highlighted in place, instead of today's metadata + text
    snippet. Soft dependency on
    [docs/PRODUCTION-READINESS-ROADMAP.md](PRODUCTION-READINESS-ROADMAP.md)
    Phase 3 (needs original file bytes persisted, not just derived
    chunks). See
    [docs/PRODUCT-DIFFERENTIATION-ROADMAP.md](PRODUCT-DIFFERENTIATION-ROADMAP.md)
    Phase 1.
35. ⬜ **New — Product Differentiation Phase 2: document comparison** — a
    structured, multi-document comparison (agreements/contradictions/
    unique points), not a single grounded answer. See
    [docs/PRODUCT-DIFFERENTIATION-ROADMAP.md](PRODUCT-DIFFERENTIATION-ROADMAP.md)
    Phase 2.
36. ⬜ **New — Product Differentiation Phase 3: specialized domain agents
    (Legal/HR/Finance/IT)** — extends Multi-LLM Phase 3 (`PlannerAgent`,
    #42 below) with the concrete shape it was missing, rather than a
    competing agent design. See
    [docs/PRODUCT-DIFFERENTIATION-ROADMAP.md](PRODUCT-DIFFERENTIATION-ROADMAP.md)
    Phase 3.
37. ⬜ **New — Product Differentiation Phase 4: cross-session
    personalization memory** — answers Multi-LLM Phase 5's own "what
    would Redis actually add" question (#22 above): a preference/fact
    that survives across separate conversations for the same user, which
    today's per-conversation memory doesn't provide. See
    [docs/PRODUCT-DIFFERENTIATION-ROADMAP.md](PRODUCT-DIFFERENTIATION-ROADMAP.md)
    Phase 4.
38. ⬜ **New — Product Differentiation Phase 5: usage and cost
    dashboards** — real $-cost tracking for public-LLM fallback calls on
    top of the existing Prometheus/Grafana usage metrics, not a new
    observability stack. Soft dependency on
    [docs/PRODUCTION-READINESS-ROADMAP.md](PRODUCTION-READINESS-ROADMAP.md)
    Phase 3. See
    [docs/PRODUCT-DIFFERENTIATION-ROADMAP.md](PRODUCT-DIFFERENTIATION-ROADMAP.md)
    Phase 5.
39. ⬜ **New — Product Differentiation Phase 6: document versioning** — a
    new document supersedes an older one as a version, instead of today's
    always-unrelated-new-`documentId` behavior on re-upload. See
    [docs/PRODUCT-DIFFERENTIATION-ROADMAP.md](PRODUCT-DIFFERENTIATION-ROADMAP.md)
    Phase 6.
40. ⬜ **New — Product Differentiation Phase 7: OCR for scanned PDFs** —
    today's `PagePdfDocumentReader` only extracts an existing text layer;
    a scanned (image-only) PDF silently produces near-empty text. Routes
    a text-less page's rendered image through the vision-model pipeline
    already built for standalone image uploads (ADR 0018) rather than a
    new OCR engine. See
    [docs/PRODUCT-DIFFERENTIATION-ROADMAP.md](PRODUCT-DIFFERENTIATION-ROADMAP.md)
    Phase 7.
41. ⬜ **New — Product Differentiation Phase 8: automatic summaries and
    FAQs** — a per-document "Summarize"/"Generate FAQ" action reusing the
    existing chat model wiring, with its own prompt templates and
    response shape. See
    [docs/PRODUCT-DIFFERENTIATION-ROADMAP.md](PRODUCT-DIFFERENTIATION-ROADMAP.md)
    Phase 8.

## Tier 3 (optional) — real, ongoing cost or a big commitment

**Not required for a good, deliverable portfolio** — see "Portfolio-ready
stopping point" above. Nothing here starts without the user explicitly
signing off on the specific cost/commitment named, *and* wanting that
specific item for its own sake, not just to advance the list — see each
phase's own text for exactly what the cost/commitment is:

42. ⬜ **Multi-LLM Phase 3 — `PlannerAgent`** (after Tier 1 #8-#12 — note
    this assumes genuinely selectable multiple providers, which the Phase 2
    fallback design deliberately does *not* provide; may need its own
    provider wiring)
43. ⬜ **Multi-LLM Phase 4 — `ReflectionAgent`** (after #42 — note this
    multiplies paid API calls per question)
44. ⬜ **Multi-LLM Phase 7 — Observability (LangFuse + OpenTelemetry)** (a
    LangFuse account/hosting decision; the OpenTelemetry half is also
    tracked from the production-operations angle in
    [docs/PRODUCTION-READINESS-ROADMAP.md](PRODUCTION-READINESS-ROADMAP.md)
    Phase 6, not duplicated content, just a second reason to want it)
45. ⬜ **Multi-LLM Phase 12 — AWS deployment target** (an AWS account +
    explicit acceptance of real, non-free-tier cost for some of what's in
    scope, e.g. Bedrock/OpenSearch)
46. ⬜ **Multi-LLM Phase 13 — Python + LangGraph AI layer** (confirm this
    portfolio project should become polyglot before any code — see "Where
    Python actually fits" below for why this one is *not* primarily a
    performance decision, unlike the Go item above)
47. ⬜ **New — External Data Integration Phase 6: cloud drive import
    (Google Drive)** (after
    [docs/PRODUCTION-READINESS-ROADMAP.md](PRODUCTION-READINESS-ROADMAP.md)
    Phase 3's async queue/object storage — a Google OAuth app registration
    and review process is the real, ongoing-ish commitment that puts this
    in Tier 3 rather than Tier 2). See
    [docs/EXTERNAL-DATA-INTEGRATION-ROADMAP.md](EXTERNAL-DATA-INTEGRATION-ROADMAP.md)
    Phase 6.
48. ⬜ **New — Product Differentiation Phase 9: federated search
    (SharePoint, Confluence, Google Drive, GitHub)** — querying an
    external system *live* at answer-time, never copying its content in
    (unlike #47's Drive *import*) — four different OAuth/API integrations,
    each its own real external dependency, is why this sits in Tier 3
    rather than Tier 2; start with one provider, not all four at once. See
    [docs/PRODUCT-DIFFERENTIATION-ROADMAP.md](PRODUCT-DIFFERENTIATION-ROADMAP.md)
    Phase 9.
49. ⬜ **New — Product Differentiation Phase 10: Audio Overview** (a
    generated two-voice podcast-style discussion of a document — added
    2026-08-06 as a direct follow-up once the user asked whether this
    project would end up equivalent to NotebookLM; it wouldn't, and this
    missing flagship feature was a concrete reason why). **Hard**
    dependency on
    [docs/PRODUCTION-READINESS-ROADMAP.md](PRODUCTION-READINESS-ROADMAP.md)
    Phase 3 (generated audio needs durable storage and must generate
    asynchronously) — the one phase in this whole file where that
    dependency isn't optional. Introduces text-to-speech, a capability
    this codebase has never had in either direction (today's
    `AudioTranscriptionService`, ADR 0019, only goes speech-to-text);
    recommended to start with a local, self-hostable TTS engine (Piper or
    Coqui TTS), matching this project's Ollama/Whisper local-first
    precedent, over a paid cloud TTS API. See
    [docs/PRODUCT-DIFFERENTIATION-ROADMAP.md](PRODUCT-DIFFERENTIATION-ROADMAP.md)
    Phase 10.

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
  (Tier 2 #28, new). This isn't spreading Go around speculatively — it fills
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
  whichever of the five detailed roadmap files owns it (or write it up as a
  new phase there first, for the Go item above, which doesn't have a home
  section yet), write its ADR if the decision was non-trivial, and follow
  the verification pattern all five of those files already define (build
  green, container healthy, a real manual test, commit + push).
