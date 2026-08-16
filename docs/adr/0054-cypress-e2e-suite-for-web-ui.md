# ADR 0054: Cypress E2E suite for `web-ui`, run against the real stack

## Status
Accepted

## Context
The user asked directly whether Helm and Cypress belonged in this project.
Helm was rejected — the project already uses Kustomize for the same
templated-manifest need, and doesn't even use overlays yet, so migrating to
Helm would rewrite working YAML without solving anything new. Cypress had a
real, concrete justification: `web-ui` (plain HTML/CSS/JS, no framework, no
build step) had zero automated end-to-end coverage. Every flow — login,
upload, ask, admin sharing, the URL-import and summarize/FAQ features added
earlier this session — had only ever been verified manually.

## Decision

### Tests run against the real local stack, not a mocked backend
Same philosophy as `RagQualityBenchmark`/`ChatQueryIT` elsewhere in this
project: `web-ui/cypress.config.js` points `baseUrl` at the real
`docker compose`-run `web-ui` (port 3000), and every spec hits the real
`auth-service`/`ingestion-service`/`rag-service` — no `cy.intercept()`
stubbing. Consequence accepted explicitly: flows that call a real local
Ollama model (`ask`, `summarize`, `generate FAQ`) get long timeouts
(`responseTimeout: 150000`) and assert on response *structure* (a citation
appeared, a summary has non-zero length), never on exact generated text.

### `web-ui/package.json` — Node/npm's first appearance in this project's frontend
Cypress is fundamentally an npm package; `web-ui` had no `package.json`
before this. Scoped deliberately as a dev/test-only dependency tree — the
Docker image `web-ui/Dockerfile` builds still serve the same static
files via Nginx, completely unaffected by this addition.

### `cy.registerAndLogin()` seeds `localStorage` directly, one tenant per spec file
Registers a real new tenant via `auth-service`'s API (`cy.request()`, no
UI interaction) and writes the resulting token into `localStorage` under
the exact key/shape `web-ui/app.js`'s own `setAuth()` uses — faster and
less brittle than filling in the login form for every test that just needs
to already be authenticated. `auth.cy.js` is the one spec that fills in the
real forms, since validating them is its whole point.

Each spec file computes ONE stable `email`/`password` pair and reuses it
across all its `it()` blocks via `cy.session()`'s caching, rather than
registering a fresh tenant per test. Found for real running the full suite
back-to-back: `auth-service`'s real rate limit on `/api/v1/auth/*` (10/min
per IP, ADR 0028 — an intentional security control, not a bug) started
returning 429s once enough tests each registered their own tenant in quick
succession. The fix is economizing real requests in the test suite, not
loosening a legitimate rate limit.

### Two real, pre-existing bugs found only by actually clicking through the app
Both invisible to every prior test in this project (none of which drive a
real browser through these exact flows) and to manual testing so far (the
sharing/promote-role buttons hadn't been exercised via a real browser
click in this session; the status-class bug has no visible symptom for an
ordinary user):

1. **`CorsConfig.allowedMethods` was missing `PATCH`.** `ingestion-service`'s
   document-sharing endpoint and `auth-service`'s role-promotion endpoint
   are both `@PatchMapping`, but the shared CORS config
   (`platform-common/.../web/CorsConfig.java`) only allowed `GET`/`POST`.
   A browser's CORS preflight silently blocked every real PATCH request
   before it left the browser — no server-side log at all, just a generic
   `fetch` failure client-side. Fixed by adding `"PATCH"` to
   `allowedMethods`; this single shared-module fix closes the same latent
   gap in both `ingestion-service` and `auth-service` at once.
2. **`setStatus()` overwrote the whole `className`.** `web-ui/app.js`'s
   `setStatus(el, message, kind)` did `el.className = "status " + kind`,
   which silently destroyed any additional class an element had beyond
   `"status"` (`admin-doc-status`, `insight-status`) the moment its first
   status message was shown. Invisible to a user — no CSS keys off those
   extra classes, and every click handler already holds a direct JS
   reference to its own status element rather than re-selecting it — but a
   real bug all the same: any future code (a test, new CSS, a debugging
   script) trying to find that element by its specific class afterward
   would silently fail. Fixed with `classList.add`/`remove`, touching only
   the `"success"`/`"error"` modifier and leaving every other class alone.

Both fixes were verified by rebuilding the affected Docker images and
re-running the exact Cypress test that had caught each one, confirming it
now passes for the right reason - not by reasoning about the fix in the
abstract.

### Scope: four specs on the highest-value flows, not exhaustive coverage
`auth.cy.js` (register/login/logout, wrong-password rejection),
`documents.cy.js` (upload, URL import, ask-a-question), `document-
insights.cy.js` (summarize, generate FAQ, the real SSRF-guard error path),
`admin.cy.js` (admin-only sections, document sharing). Not every button in
the app has a test — the goal was the critical path, matching how every
other phase in this project scoped itself to one real capability at a time
rather than a speculative do-everything suite.

### Not run in the hosted GitHub Actions CI, for now
Same infra decision already made for `RagQualityBenchmark`
(`-Dbenchmark=true`, opt-in, never in CI): a GitHub-hosted runner has no
GPU-less-but-still-real local Ollama with the needed models pre-pulled, and
provisioning one is a real infra/cost decision this ADR doesn't make. The
suite runs locally against `docker compose up -d`, documented in the
README, with a follow-up noted in the roadmap for a possible self-hosted
runner later.

## Consequences

### Verified for real: 10/10 passing, full suite, twice
`npx cypress run` (all four specs) passes cleanly end-to-end after both
real bugs above were fixed, confirmed with a second full clean run
immediately after (not a one-off flaky pass). The full backend Java suite
(`./mvnw test`) also stays green after the shared `platform-common` CORS
change - direct evidence the fix didn't affect anything else relying on
that config.

### A real, temperature-based non-determinism the ask-flow test had to account for
The local groundedness check (ADR 0008) is itself an LLM call, and it
doesn't always reach the same verdict for what's semantically the same
answer. When it decides an answer isn't grounded, `rag-service` offers the
public-LLM fallback instead of returning it directly (ADR 0038), and
`web-ui` shows `#fallback-confirm-card` instead of `#answer-card`. The
`documents.cy.js` ask-a-question test was rewritten (after hitting this for
real, not anticipated up front) to accept either outcome as correct rather
than assuming the local answer is always grounded - a real characteristic
of testing against a genuine local model, not a flaky test to paper over.
