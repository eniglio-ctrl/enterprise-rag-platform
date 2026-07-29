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
[Scope and limitations](#scope-and-limitations) below. As of 2026-07-28 this is
the project's own real documentation (25 documents: `README.md`,
`docs/architecture.md`, and all 22 ADRs under `docs/adr/`, plus the internal
development log) — not the 3 short synthetic paragraphs originally seeded at
launch.

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

**As of 2026-07-28**, the seeded corpus is the project's own real documentation,
25 files total — `README.md`, `docs/architecture.md`, every ADR under
`docs/adr/*.md`, and the internal development log
(`01-O-QUE-FOI-FEITO.md`) — replacing the 3 short synthetic paragraphs seeded at
initial launch. Uploaded with a loop over each file (any content type works;
`text/markdown` was used for all of them, `.md` extension included):

```bash
for f in README.md docs/architecture.md docs/adr/*.md /path/to/01-O-QUE-FOI-FEITO.md; do
  curl -s -o /dev/null -w "%{http_code} $f\n" -X POST http://localhost:8091/api/v1/documents \
    -F "file=@${f};type=text/markdown"
done
```

To re-seed from scratch (e.g. after a schema change, or to replace the corpus
again), drop and let Flyway recreate:

```sql
DROP TABLE IF EXISTS vector_store;
DROP TABLE IF EXISTS flyway_schema_history;
```

then rerun `ingestion-service` as above — `db/migration-demo`'s `V1`/`V2` recreate
the 1024-dimension schema (matching `mistral-embed`'s output size) from an empty
database. **Watch out for double-submitting a file** if a seeding script errors
out partway through and gets rerun from the top — check
`SELECT metadata->>'source', count(DISTINCT metadata->>'documentId') FROM
vector_store GROUP BY 1 HAVING count(DISTINCT metadata->>'documentId') > 1;`
afterwards to catch any source that ended up with more than one `documentId`,
and delete the older `documentId`'s rows if so.

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
against the live demo on 2026-07-28):
- "What are the four microservices in this platform?"
- "How does hybrid search work?"
- "How is data isolated between tenants?"
- "What is ADR 0022 about?"
- "What bugs were found and fixed during the auth-service implementation?"
- "How does the RAG quality benchmark work?"
- "Can this platform ingest images or audio?"

A question about anything *not* covered by the seeded documents will correctly
get "not enough information" — that's the retrieval working as intended, not a
bug (the same well-understood behavior as ADR 0007's tenant isolation).

**Cold start**: Render's free tier spins the service down after ~15 minutes of
inactivity. The first request after a gap can take 30–60 seconds while it wakes
up — expected, not a hang.

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

Everything else in the original 8-phase roadmap is done. Two items remain,
independent of this deployment:

- **Fase 7c — RAG quality benchmark**: a `RagQualityBenchmark` test class (opt-in,
  `-Dbenchmark=true`, not part of `verify`) comparing generated answers against a
  small set of expected Q&A pairs via cosine similarity, using the already-injected
  `EmbeddingModel` — no new dependency.
- **Fase 7d — Final README polish**: a second pass once the benchmark exists,
  citing real numbers, plus an updated demo GIF if multi-turn chat gets added to
  the public demo's scope later.
