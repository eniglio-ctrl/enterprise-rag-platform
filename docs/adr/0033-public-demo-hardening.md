# ADR 0033: Public demo hardening

## Status
Accepted

## Context
`docs/SECURITY-HARDENING-ROADMAP.md` Phase 6, the last item in the security
hardening rollout. Every prior phase hardened the platform's code paths in general;
this phase is specifically about the one deployment of that code sitting on the
open internet with no login at all — `web-ui-rag.netlify.app` (Netlify) calling
`ag-service-demo.onrender.com` (Render), per ADR 0020. Before changing anything, the
actual current exposure was checked against the live URLs, not assumed from reading
config:

```
GET /actuator/health      -> 200
GET /actuator/prometheus  -> 200  (real internal metrics, publicly readable)
GET /actuator/metrics     -> 200
GET /v3/api-docs          -> 200  (full OpenAPI schema)
GET /swagger-ui.html      -> 302 -> /swagger-ui/index.html -> 200
```

Every one of these beyond `/actuator/health` is unnecessary public surface: internal
operational metrics and a complete map of the API for anyone who asks, on a
deployment nobody is meant to administer from the outside at all.

## Decision

### Actuator and API docs: health only
- `rag-service/src/main/resources/application-demo.yml` overrides
  `management.endpoints.web.exposure.include` to `health` only (the base
  `application.yml`'s `health, info, prometheus, metrics` list is fully replaced,
  not merged — the same profile-override behavior already documented for
  `spring.autoconfigure.exclude` and the rate-limit rules list). Render's own health
  check still needs `/actuator/health`; nothing else does.
- `springdoc.api-docs.enabled`/`springdoc.swagger-ui.enabled` both set to `false` —
  plain booleans, no list-replace subtlety. Neither the machine-readable schema nor
  the interactive UI built on it serves any purpose for a demo visitor.

### Rate limit: tightened from 30/min to 10/min
- Same three `rag-query`-named rules (`/api/v1/ask`, `/api/v1/chat`,
  `/api/v1/diagrams`), same per-IP keying (ADR 0028's demo-specific override,
  unchanged) — only the capacity number changed. The public demo pays real (if
  free-tier) Groq/Mistral API cost per question, unlike the base `application.yml`'s
  local Ollama, which costs nothing beyond electricity — a lower ceiling caps
  worst-case abuse cost directly, not just request volume.

### `trusted-proxy-hops`: stays 0, decision now fully researched instead of deferred
- This was the one open question the previous phases' comments explicitly deferred
  to this phase: "confirm Render's actual edge hop count." Researched directly
  rather than assumed: Render's own community has an **open, unresolved feature
  request** ("Send the correct X_FORWARDED_FOR") reporting inconsistent
  `X-Forwarded-For` behavior on their platform, and a separate community thread
  describing missing `X-Forwarded-For` on certain routed requests. Trusting a
  specific hop count on a documented, disputed foundation would make the rate
  limiter's IP resolution *less* trustworthy than the status quo, not more.
- **Decision: leave `trusted-proxy-hops: 0`.** Worst case with this value is coarser
  IP granularity (if Render's edge IP is what `request.getRemoteAddr()` actually
  sees, multiple real visitors could share one bucket) — an availability/UX
  tradeoff, never a security bypass, since no client-controlled header is ever
  trusted. This is a corrected premise, not a skipped task: the original plan
  assumed this number was knowable and just needed confirming; the real answer is
  that it isn't safely knowable from public documentation today, and guessing on a
  trust boundary is worse than the conservative default already in place.

### CSP: a real, demo-specific policy via Netlify's own header mechanism
- New `web-ui/_headers` (Netlify's native static-header-injection file — the
  `web-ui/nginx.conf` CSP from ADR 0029 only ever applies to the docker-compose
  deployment; Netlify serves this directory straight from its own CDN and never
  runs that nginx config at all, a gap ADR 0029 explicitly left for this phase).
- **Tighter than a copy of the docker-compose CSP, not just a port**: `connect-src`
  lists only the one real backend this deployment ever calls
  (`https://ag-service-demo.onrender.com`) — the docker-compose CSP's local ports
  (8081/8082/8084) mean nothing on the public internet and would be actively
  misleading here. `auth-service`/`ingestion-service` origins are correctly absent
  entirely — the demo has no login and no permanent upload (`DemoSecurityConfig`,
  ADR 0020), so nothing in this deployment ever calls either.
- `style-src 'self' 'unsafe-inline'` kept from ADR 0029's own real, live-browser-verified
  finding: Mermaid's rendered SVG output uses inline `<style>` and `style="..."`
  attributes, confirmed necessary there, still true here (same frontend code, same
  Mermaid version).

### Read-only, reconfirmed explicitly
- ADR 0020 already established the demo has no write path at all (only
  `rag-service`/`web-ui` deployed; `ingestion-service` — the only service with a
  persistent-write endpoint — is never deployed publicly). This phase changes
  nothing about that; stated here explicitly, as the roadmap's plan asked for, so
  the claim has its own decision record rather than only living in ADR 0020's
  original context.

## Consequences
- **Verified against the live URLs, both before and after this phase's changes**:
  the exposure table in Context above was captured from the real, currently-deployed
  demo before any change; after this ADR's changes deploy (Netlify/Render both
  auto-deploy from `main`, the same mechanism every other phase's commits already
  went through), the same five requests are expected to show `/actuator/health`
  still `200` and the other four no longer publicly reachable — re-verify directly
  against the live URLs after the deploy completes, don't trust the config alone.
- **`./mvnw clean verify` unaffected** — this phase only touches
  `rag-service/application-demo.yml` (a profile that's never exercised by any
  automated test in this repo, by design — it needs real Groq/Mistral keys and a
  real Neon connection) and a new static `web-ui/_headers` file with no build-time
  processing. No Java source changed.
- **The `trusted-proxy-hops` non-decision is durable, not a placeholder**: any
  future revisit needs a direct answer from Render's own support, not another
  community-thread read — the bar this phase sets for changing a trust boundary is
  higher than "seems likely."
- **Local/docker-compose deployments are completely unaffected**: every change in
  this phase lives in `application-demo.yml` or `web-ui/_headers`, neither of which
  exists in or affects the non-demo profile or the docker-compose nginx path.
