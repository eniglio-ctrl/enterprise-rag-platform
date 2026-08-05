# Public demo deployment — configuration reference

Practical, reproducible reference for the live public demo (ADR 0020). For the
*why* behind each decision, see
[docs/adr/0020-public-demo-deployment.md](adr/0020-public-demo-deployment.md) — this
document is the *how*: exact configuration, how to test it, how to rebuild it from
scratch, and what's intentionally out of scope.

## Live URLs

| Component | URL | Host |
|---|---|---|
| Web UI | https://web-ui-rag.netlify.app | Netlify (free) |
| API (`rag-service`) | https://ag-service-demo.onrender.com | Render (free) |
| Database | (private Neon connection string) | Neon (free) |

No login. A fixed, read-only corpus is all that's searchable — see
[Scope and limitations](#scope-and-limitations) below. As of 2026-08-01 this is
36 documents: the project's own real documentation (`README.md`,
`docs/architecture.md`, all 29 ADRs under `docs/adr/`, and the internal
development log) plus 4 short, original technical write-ups on Java/software
architecture, Spring Boot, Spring AI, and Apache Kafka — written from scratch
for this corpus, not copied from any official documentation (Oracle's,
Spring's, and Apache's docs are copyrighted; reproducing them at scale into a
publicly-queryable database would not be a fair-use quote). Re-seeded
2026-08-01 after the original 25-document, ADR-0022-era corpus went stale.

## Architecture: what's different from local

Only `rag-service` and `web-ui` are deployed. `ingestion-service`, `chat-service`,
and `auth-service` are **not** part of the public demo — the demo has no upload and
no login, so there's nothing for them to do there. They're only used locally
(`docker-compose`), and `ingestion-service` additionally gets run standalone, once,
to seed the demo database (see [Seeding the database](#seeding-the-database)).

| Concern | Local (`docker-compose`) | Public demo |
|---|---|---|
| Chat model | Ollama (`llama3.1`) | Groq (`llama-3.3-70b-versatile`) |
| Embedding model | Ollama (`nomic-embed-text`, 768 dim) | Mistral AI (`mistral-embed`, 1024 dim) |
| Database | Local Postgres container | Neon (serverless Postgres) |
| Auth | Real JWT via `auth-service` | None — `DemoSecurityConfig` treats every request as one fixed `demo` tenant |
| Upload | Yes, via `ingestion-service` | No — read-only, pre-seeded |
| Rate limit | 30 requests/min per tenant | 10 requests/min per IP (Security Phase 6, ADR 0033 — a public, unauthenticated URL paying real per-question API cost gets a tighter ceiling) |
| Actuator / API docs | Full (`health, info, prometheus, metrics`, Swagger UI, OpenAPI JSON) | `health` only — everything else disabled (Security Phase 6) |

All of this is controlled by Spring's `demo` profile
(`SPRING_PROFILES_ACTIVE=demo`), which is purely additive configuration —
`rag-service/src/main/resources/application-demo.yml` and
`ingestion-service/src/main/resources/application-demo.yml`. The default profile
(used everywhere else, including CI) is completely unaffected; `./mvnw clean verify`
never activates `demo`.

## `rag-service` on Render — full environment variable reference

Service type: **Web Service**, Docker runtime.
- **Root Directory**: *(blank — repo root)*
- **Dockerfile Path**: `rag-service/Dockerfile`
- **Instance Type**: Free

| Key | Value | Purpose |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `demo` | Activates `application-demo.yml` |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://<neon-host>/<db>?sslmode=require` | Neon connection (JDBC form of the connection string Neon gives you) |
| `SPRING_DATASOURCE_USERNAME` | *(from Neon)* | |
| `SPRING_DATASOURCE_PASSWORD` | *(from Neon)* | |
| `GROQ_API_KEY` | *(from console.groq.com)* | Chat provider — app fails fast at boot if missing |
| `MISTRAL_API_KEY` | *(from console.mistral.ai)* | Embedding provider — app fails fast at boot if missing |
| `WEB_UI_ORIGIN` | `https://web-ui-rag.netlify.app` | CORS allow-origin — **must** match the actual deployed frontend's origin exactly, or the browser silently blocks every API call (curl-based testing won't reveal this — CORS is a browser-only enforcement) |
| `PORT` | `8082` | Tells Render which port the container actually listens on (must match the Dockerfile's `EXPOSE`) |

Optional overrides (sensible defaults exist, only set these to change behavior):

| Key | Default | Purpose |
|---|---|---|
| `GROQ_MODEL` | `llama-3.3-70b-versatile` | Which Groq model to use |

**Do not set** `JAVA_TOOL_OPTIONS` for heap tuning — that was only ever needed for
the local-ONNX-embedding approach that was reverted (see ADR 0020's update
section). The current Mistral-based setup runs comfortably in Render's free 512MB.

### Gotchas already hit once, worth knowing before touching this again

- **Env var values must be pasted exactly** — no leading/trailing whitespace, no
  stray text (a copy-paste that accidentally included a table's "Value" header
  text once produced `Unrecognized option: Value` from the JVM). If a key that
  worked in isolation (tested via curl) fails once set on Render, re-paste it from
  scratch rather than editing in place.
- **`OllamaChatModel`'s bean is not lazy** — even though the demo's
  `available-models` list never selects Ollama, Spring AI's
  `OllamaChatAutoConfiguration` eagerly calls `GET /api/tags` against
  `OLLAMA_BASE_URL` when constructing the bean, to decide whether to pull the
  configured model. With no Ollama reachable, this throws and crashes the whole
  app at boot. Already fixed via `spring.ai.ollama.init.pull-model-strategy: never`
  in `application-demo.yml` — don't remove it.
- **A profile-specific `spring.autoconfigure.exclude` REPLACES the base list, it
  doesn't merge with it.** Every profile-specific YAML file that sets this
  property must spell out the *entire* exclude list it wants active, not just the
  one entry it means to change — already correct in both services'
  `application-demo.yml`, but a real trap for any future edit.

## `web-ui` on Netlify

Netlify, not Render — a second Render web service required a credit card for
verification (a $1 refunded hold, not a real charge, but still card data this
project's demo doesn't need to collect). `web-ui` is plain static files
(`index.html`/`style.css`/`app.js`), so a static host is a better fit anyway; no
Docker build needed.

**Setup**:
- **Base directory**: `web-ui`
- **Build command**: *(blank — nothing to build)*
- **Publish directory**: `web-ui`

**Config**: `web-ui/config.js` is a real, committed file (not the
`config.js.template` the Docker/`docker-compose` path uses) with the values
hardcoded for this specific deployment:

```js
window.RAG_PLATFORM_CONFIG = {
  demoMode: true,
  ragBaseUrl: "https://ag-service-demo.onrender.com"
};
```

If `rag-service`'s Render URL ever changes (redeployed under a new service name,
moved to a different host), update this file and the `WEB_UI_ORIGIN` env var on
`rag-service` together — they have to point at each other correctly or CORS
blocks everything.

### Gotchas already hit once

- **Netlify's team-level "Default project visibility" setting only applies to
  *new* projects.** An already-created site keeps whatever visibility it had —
  changing the team default does not retroactively affect it. Fix: go to the
  specific project's own overview page (not team settings) and toggle its
  visibility to Public there.
- If a fresh site returns `401` with a "Login Redirect" page pointing at
  `app.netlify.com/edge-access`, that's this exact issue, not a bug in the app.

## Seeding the database

The demo's documents are indexed by running `ingestion-service` **locally**,
once per document, with `SPRING_PROFILES_ACTIVE=demo` pointed at the real Neon
connection string — `ingestion-service` is never itself deployed publicly.

```bash
./mvnw -q -pl ingestion-service -am package -DskipTests

SERVER_PORT=8091 SPRING_PROFILES_ACTIVE=demo \
SPRING_DATASOURCE_URL="jdbc:postgresql://<neon-host>/<db>?sslmode=require" \
SPRING_DATASOURCE_USERNAME="<neon-user>" \
SPRING_DATASOURCE_PASSWORD="<neon-password>" \
MISTRAL_API_KEY="<your-mistral-key>" \
  java -jar ingestion-service/target/ingestion-service.jar

# in another terminal, once it's up (no auth needed — DemoSecurityConfig permits all):
curl -X POST http://localhost:8091/api/v1/documents -F "file=@/path/to/doc.txt;type=text/plain"
```

**As of 2026-08-01**, the seeded corpus is 36 files: the project's own real
documentation (`README.md`, `docs/architecture.md`, every ADR under
`docs/adr/*.md`, and the internal development log `01-O-QUE-FOI-FEITO.md`,
kept outside this repo — see its own note below) plus 4 short, original
technical write-ups under `docs/demo-seed-content/` (Java/software
architecture, Spring Boot, Spring AI, Apache Kafka — written from scratch,
not copied from any official docs; see that directory's own README for why).
Uploaded with a loop over each file (any content type works; `text/markdown`
was used for all of them, `.md` extension included):

```bash
for f in README.md docs/architecture.md docs/adr/*.md docs/demo-seed-content/*.md \
         /path/to/01-O-QUE-FOI-FEITO.md; do
  curl -s -o /dev/null -w "%{http_code} $f\n" -X POST http://localhost:8091/api/v1/documents \
    -F "file=@${f};type=text/markdown"
done
```

`01-O-QUE-FOI-FEITO.md` is the project's internal development log, kept in a
separate, non-public continuity folder outside this repository (personal
working notes, not project documentation meant for a public audience) —
substitute its real local path when re-seeding.

To re-seed from scratch (e.g. after a schema change, or to replace the corpus
again), clear the existing rows — `DELETE FROM vector_store;` is enough and
keeps the schema intact (no need to touch Flyway's history table unless the
schema itself changed):

```sql
DELETE FROM vector_store;
```

then rerun the upload loop above against the now-empty table. **Watch out for
double-submitting a file** if a seeding script errors
out partway through and gets rerun from the top — check
`SELECT metadata->>'source', count(DISTINCT metadata->>'documentId') FROM
vector_store GROUP BY 1 HAVING count(DISTINCT metadata->>'documentId') > 1;`
afterwards to catch any source that ended up with more than one `documentId`,
and delete the older `documentId`'s rows if so.

### Known incident (2026-08-05): a schema migration added after the initial seed never reached Neon, and every question-answering endpoint 500'd

`rag-service`'s `HybridSearchService.fullTextSearch` queries against a Postgres
text-search configuration named `unaccent_simple` (added by
`ingestion-service`'s `db/migration/V3__unaccent_text_search_config.sql`, part
of the accent/special-char-insensitive hybrid search work). `rag-service` is
auto-deployed to Render on every push to `main`, so the *code* expecting
`unaccent_simple` went live immediately — but per this document's own
"Seeding the database" section, `ingestion-service` is never itself deployed,
so nothing ever re-ran its Flyway migrations against Neon after the demo's
one-time initial seed, which predates V3. Every query-based endpoint
(`/api/v1/ask`, `/api/v1/retrieve`, `/api/v1/diagrams`) failed with
`PSQLException: text search configuration "unaccent_simple" does not exist`,
wrapped by `GlobalExceptionHandler` into the generic, deliberately
detail-free `500 "An unexpected error occurred"` the demo shows on purpose
(config-only endpoints like `/api/v1/models` were unaffected, which is what
pointed at the query path specifically rather than the service being down).

**Fix, run once directly in Neon's SQL editor** — no redeploy needed, this is
a data-layer fix, not a code change (identical to V3's own migration body,
safe against the demo's disposable seeded-only dataset: no document or vector
data is touched, only a derived generated column and its index are dropped
and recreated):

```sql
CREATE EXTENSION IF NOT EXISTS unaccent;

CREATE TEXT SEARCH CONFIGURATION unaccent_simple (COPY = simple);
ALTER TEXT SEARCH CONFIGURATION unaccent_simple
    ALTER MAPPING FOR hword, hword_part, word WITH unaccent, simple;

ALTER TABLE vector_store DROP COLUMN content_tsv;
ALTER TABLE vector_store
    ADD COLUMN content_tsv tsvector
        GENERATED ALWAYS AS (to_tsvector('unaccent_simple', coalesce(content, ''))) STORED;

CREATE INDEX vector_store_content_tsv_idx ON vector_store USING GIN (content_tsv);
```

**The general gap this exposes, not just this one incident**: `rag-service`
auto-deploys on push, but any `db/migration-demo` schema change only reaches
Neon if someone remembers to re-run `ingestion-service` (demo profile)
against it afterward — there's no CI/CD step that does this automatically,
unlike a normal deployment where the same service that owns the code also
owns applying its own migrations at boot. Until that's automated (or judged
not worth automating for a demo this small), **any future change to
`ingestion-service`'s `db/migration-demo` needs its SQL applied to Neon by
hand, the same way, before or right after `rag-service`'s matching code
change goes live** — otherwise this exact failure mode recurs with a
different column or index.

## How to test the demo

**Browser** (the real user-facing path): open
[web-ui-rag.netlify.app](https://web-ui-rag.netlify.app), type a question about
this project's architecture, ADRs, or development history (the seeded
documents' actual topics), click Ask.

**API directly**:

```bash
curl -s https://ag-service-demo.onrender.com/api/v1/models

curl -s -X POST https://ag-service-demo.onrender.com/api/v1/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"What are the four microservices in this platform?"}'
```

No `Authorization` header needed or accepted — every request is treated as the
same fixed demo tenant.

**Known-good test questions** (match the seeded content — verified for real
against the live demo on 2026-08-01):
- "What are the four microservices in this platform?"
- "How does hybrid search work?"
- "How is data isolated between tenants?"
- "What is ADR 0022 about?"
- "Como funciona o rate limiting deste projeto?"
- "Can this platform ingest images or audio?"
- "O que são partições no Kafka?" (answered from the original Kafka write-up,
  not the project's own docs — confirms the non-project content is searchable
  too)
- "O que é auto-configuração no Spring Boot?"
- "O que é um ChatModel no Spring AI?"

A question about anything *not* covered by the seeded documents will correctly
get "not enough information" — that's the retrieval working as intended, not a
bug (the same well-understood behavior as ADR 0007's tenant isolation).

**Cold start**: Render's free tier spins the service down after ~15 minutes of
inactivity. The first request after a gap can take 30–60 seconds while it wakes
up — expected, not a hang.

## Security hardening (Phase 6, ADR 0033)

Verified against the live URLs before hardening this deployment — every one of
these was really, publicly reachable and unauthenticated:

```
GET /actuator/health      -> 200  (kept - Render's own health check needs it)
GET /actuator/prometheus  -> 200  (now disabled)
GET /actuator/metrics     -> 200  (now disabled)
GET /v3/api-docs          -> 200  (now disabled)
GET /swagger-ui.html      -> 302 -> /swagger-ui/index.html -> 200  (now disabled)
```

Re-verify the same five requests after any future redeploy — `/actuator/health`
should still return `200`, the other four should not. **Re-verified for real**:
`rag-service` on Render redeployed automatically and now shows exactly this
(`/actuator/health` → `200`, the other four → `404` — an intermediate deploy
briefly returned `500` instead of `404` for the disabled endpoints, a real bug
fixed the same day, see ADR 0033's own account).

`web-ui/_headers` (Netlify's native header-injection file, since Netlify never
runs `web-ui/nginx.conf` — that file only applies to the docker-compose build)
gives the demo its own CSP, tighter than the docker-compose one: `connect-src`
lists only `https://ag-service-demo.onrender.com`, not the local dev ports, and
there's no `auth-service`/`ingestion-service` origin at all since this deployment
never calls either.

**Known gap, found while verifying this phase, not fixed by it**: unlike
`rag-service` on Render, **`web-ui` on Netlify does not appear to auto-deploy from
`main`**. Checked directly — the live `web-ui-rag.netlify.app` still serves the
free-text `register-tenant` field that Security Phase 4 removed from the source
days earlier, and the new `_headers` file's CSP is absent from the live response
headers. This predates Phase 6 and isn't something this session could fix (it
needs Netlify dashboard/GitHub-integration access this session doesn't have) — it
was invisible until now because the demo's `DEMO_MODE` hides the entire
login/register panel, so the stale HTML/JS never showed up in normal use. **Next
step for whoever has Netlify access**: check the site's deploy settings (is it
linked to this GitHub repo at all? is continuous deployment enabled? check the
Deploys tab for failed builds) and trigger a manual deploy to confirm `_headers`
actually ships once that's fixed.

`trusted-proxy-hops` stays `0` — researched, not assumed: Render's own community
has an open, unresolved report of inconsistent `X-Forwarded-For` behavior on their
platform, so trusting a specific hop count there would be a worse foundation for
the rate limiter's IP resolution than the conservative default already in place.
See ADR 0033 for the full reasoning.

## Scope and limitations (by design, not oversight)

- **No upload, no login** — a fixed, small document set. This demonstrates
  retrieval/generation quality, not the full product surface.
- **No image or audio ingestion, no multi-turn chat, no diagram generation
  endpoint exposed here** — all real features of the full stack (ADRs
  0005/0013/0018/0019), just not part of this lean deployment. Run the full
  `docker-compose` stack locally to exercise them.
- **No persistence beyond the seeded documents** — nothing written here is
  retained; there's no write path at all in this deployment.
- **Free-tier limits apply**: Render's cold starts, Groq's and Mistral's free-tier
  rate limits. A burst of concurrent testers could hit a rate limit; the circuit
  breaker/retry (ADR 0009/0017) surfaces this as a clear error, not a hang.

## What's left in the broader roadmap

The original 8-phase roadmap (Fase 0-7d) and the entire security hardening
rollout (`docs/SECURITY-HARDENING-ROADMAP.md`, Phases 0-7) are both fully done as
of this phase — this deployment isn't blocking anything further. Remaining work
lives in `docs/ROADMAP.md`'s Tier 2/3 (Multi-LLM fallback providers, audit-logging
follow-ups, etc.), independent of this specific deployment.
