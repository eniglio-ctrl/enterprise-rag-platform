# ADR 0029: Secrets, CORS, and HTTP security headers

## Status
Accepted

## Context
`docs/SECURITY-HARDENING-ROADMAP.md` Phase 3 / `docs/ROADMAP.md` Tier 1 #5.
Three separate, small gaps, grouped into one phase because each is cheap and
none depends on the others: `docker-compose.yml` fell back to real-looking
default credentials (`ragplatform`/`ragplatform` DB creds, a literal `admin`
Grafana password) when no `.env` was provided; `CorsConfig` allowed any
request header (`allowedHeaders("*")`) when the app only ever sends two;
`web-ui` shipped stock `nginx:alpine` with zero security headers and no CSP
at all.

## Decision
- **`docker-compose.yml`'s `${DB_USER:-ragplatform}`-style fallbacks became
  `${DB_USER:?Copy .env.example to .env first - ...}`** (Compose's
  required-with-custom-error syntax) for `DB_NAME`/`DB_USER`/`DB_PASSWORD`
  everywhere they appear (postgres, and all 4 application services), plus
  `GF_SECURITY_ADMIN_PASSWORD` → `${GRAFANA_ADMIN_PASSWORD:?...}`. Grafana's
  anonymous Viewer access stays on (ADR 0015's own local/dev convenience
  decision, untouched by this phase) — only the admin password, which
  anonymous access can't reach anyway, stopped being a real-looking
  hardcoded default.
- **`.env.example` keeps the exact same values as before** (still
  `ragplatform`/`ragplatform`, `admin`) — this phase isn't about making the
  local defaults more secure, it's about making them an explicit, informed
  choice (copying a file) instead of an invisible one baked into the compose
  file itself. `README.md`'s "Running it" section now leads with
  `cp .env.example .env`, before `docker compose up`, since the file already
  existed but nothing previously told a new reader to copy it.
- **`CorsConfig.allowedHeaders("*")` → `.allowedHeaders("Authorization",
  "Content-Type")`** — confirmed via `grep` first, not assumed, that
  `web-ui/app.js` never sends anything else (origin and methods were already
  this narrow before this phase).
- **New `web-ui/nginx.conf`** replacing stock `nginx:alpine`'s default,
  adding `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
  `Referrer-Policy: strict-origin-when-cross-origin`, and a real
  `Content-Security-Policy`. The CSP's `connect-src` lists the local
  docker-compose backend ports (`auth`/`ingestion`/`rag`-service) the
  browser talks to directly — the public demo (Netlify) is a separate
  static deployment, explicitly out of scope here, left for Security Phase 6.
- **CSP tested against the actual diagram feature in a real browser before
  finalizing, not assumed safe from Mermaid's docs** — `mermaid@11` loads
  from `cdn.jsdelivr.net` (`script-src`), and its rendered SVG output embeds
  both an inline `<style>` block and inline `style="..."` attributes on
  every node, confirmed by inspecting the live DOM
  (`svg.querySelector('style')` and `svg.querySelector('[style]')` both
  truthy) — `style-src 'self' 'unsafe-inline'` is a real requirement of this
  exact renderer, not a defensively-loose guess.

## Consequences
- **Verified for real, not just by reading the compose file**:
  `docker compose config` without a `.env` present fails with
  `required variable GRAFANA_ADMIN_PASSWORD is missing a value: Copy
  .env.example to .env first...` — the exact clear-failure behavior "done
  when" asked for. `curl -I http://localhost:3000/` after rebuilding showed
  every new header present. All 4 affected services (they share
  `platform-common`'s `CorsConfig`) rebuilt and came up healthy.
- **Verified against the real diagram feature in a real browser, per this
  phase's own explicit risk warning**: registered a real account, uploaded a
  real Markdown document describing a payments architecture (API Gateway →
  Payment Service → PostgreSQL + Kafka → Notification Service → Email),
  asked for a diagram, and confirmed via the live DOM — not a screenshot
  alone — a real `<svg>` rendered with all 6 expected node labels, zero
  browser console errors, zero CSP violations. The CORS tightening was
  verified the same way: the browser's real CORS preflight
  (`OPTIONS /api/v1/ask`) returned `200` and the actual `POST` succeeded.
- **A real, unrelated transient failure hit during this verification**: one
  `POST /api/v1/ask` failed with `net::ERR_NETWORK_IO_SUSPENDED` — a Chrome
  network-suspension artifact of the automated browser tooling backgrounding
  the tab during a long wait, confirmed by the error string itself (not a
  CORS/CSP failure), resolved by simply retrying the request.
- **`./mvnw clean verify` green across all 5 modules** — this phase touched
  no Java logic, only `platform-common`'s `CorsConfig` (a one-line change)
  and infra config (`docker-compose.yml`, `web-ui/nginx.conf`,
  `web-ui/Dockerfile`), so no test needed updating.
- **Scope deliberately excludes the public demo (Netlify/Render)** — its
  CSP/headers, if wanted, belong to Security Phase 6 (public demo
  hardening, not yet done), which already owns that deployment's other
  concerns.
