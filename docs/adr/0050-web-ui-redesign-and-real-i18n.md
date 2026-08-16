# ADR 0050: web-ui visual redesign, real PT/EN i18n, and three bugs live verification caught

## Status
Accepted

## Context
With ADR 0049 closing the last portfolio-review punch list, the user asked
for a purely visual follow-up: redesign `web-ui`'s look, using a reference
screenshot of a polished dark-themed AI dashboard (sidebar nav, header
status badge, PT|EN toggle, knowledge-base stats panel) as inspiration, not
a pixel-perfect copy — "quero a interface da forma como achar melhor,
levando essa imagem como exemplo." Asked directly whether the PT|EN toggle
in the reference image should be real i18n or just visual decoration, the
user was explicit: **"PT/EN de verdade, com toggle funcional"** — a genuine
bilingual system, not a static label.

## Decision

### Sidebar-view shell, not a rewrite of the business logic
`index.html`/`app.js` keep almost every existing `id` and function
(`performAsk`, `loadAdminPanel`, `renderAnswer`, upload/login/register/
invite/conversation logic all unchanged). The redesign groups the existing
panels into four sidebar-switched views — Knowledge, Conversations,
Documents, Settings — via a plain `showView(name)` toggling `hidden` on
`<section class="view">` elements. No router, no hash/history API: judged
unnecessary for a portfolio-scale static app with four flat destinations.

The old single `#admin-panel` (team list + document-sharing list combined)
is split into `#admin-team-section` (now inside Settings, next to Invite)
and `#admin-documents-section` (now inside Documents, next to Upload) —
same `loadAdminPanel()`/`renderAdminUsers()`/`renderAdminDocuments()`
logic, just two independent containers living in the view each
conceptually belongs to, instead of one bolted-on panel.

### Stats panel: only ever real numbers
The reference image's "24 documentos / 1.248 trechos" style panel is
**not** copied with invented data. `GET /api/v1/documents` is admin-only
(ADR 0047), so there is no real document count available to a non-admin —
the Documents stat row is simply absent for non-admins rather than showing
a fabricated or approximate number. No chunk-count endpoint exists
anywhere in the platform, so that stat from the reference image is dropped
entirely rather than invented. What *is* shown is genuinely real: hybrid
search is unconditionally active (a static fact about this system), tenant
ID and selected model come straight from already-known auth/UI state, and
the system-health dot polls the real `/actuator/health` (Security Phase 6
already made it public) every 30 seconds.

### Real PT/EN i18n
A flat `STRINGS = { en: {...}, pt: {...} }` dictionary, a `t(key, vars)`
function with `{{var}}` interpolation, and `applyTranslations()` driven by
`data-i18n`/`data-i18n-placeholder`/`data-i18n-title` attributes. Every
static string in the HTML, and every dynamic string app.js used to build
inline (upload progress, admin role toggles, citation labels, the fallback
card), now goes through `t(...)`. Chosen language persists in
`localStorage` under `ragPlatformLang` (a new key, independent of the
existing `ragPlatformAuth` key), defaulting to PT.

**Documented, accepted limitation**: backend-generated error messages
(`ErrorResponse.message`) stay in English, exactly as the backend already
produces them — translating arbitrary backend text would require i18n in
the backend itself, out of scope for a frontend-only redesign. The toggle
translates everything the frontend controls; it does not rewrite what the
API sends back.

### Theme
Single dark theme (deep navy background, blue→purple gradient on primary
actions and the active nav item), replacing the old light/dark split via
`prefers-color-scheme`. All icons are inline SVG — no icon font, no CDN —
consistent with the CSP already in place (`font-src 'self'`, `img-src
'self' data:'`) in both `web-ui/nginx.conf` (local) and `web-ui/_headers`
(Netlify demo).

## Three real bugs live verification caught

The plan called for manual, real-browser verification rather than
automated tests (this is framework-free static HTML/CSS/JS, as it always
has been). That verification did its job — it caught three real bugs
before they reached the user, one of them severe:

### 1. Stale `style.css`/`app.js` served indefinitely after deploy
Neither `nginx.conf` nor `_headers` set any `Cache-Control` header, so
`index.html`, `style.css`, and `app.js` — none of them content-hashed,
since this project has no build step — could be cached by a browser (or
any intermediate cache) indefinitely past a real deploy. This is the exact
failure mode ADR 0033 already documented for the Netlify demo, and it
reproduced identically against the *local* docker-compose stack during
this session's own verification: after rebuilding the container with the
new redesign, the browser kept rendering the old page from cache. Fixed
two ways, deliberately redundant: (a) `Cache-Control: no-cache,
must-revalidate` on `index.html`/`style.css`/`app.js` in both
`nginx.conf` and `_headers`, so any client that respects HTTP caching
semantics revalidates on every load; (b) a manual `?v=2026-08-05` version
query on `style.css`/`config.js`/`app.js`'s `<link>`/`<script>` tags in
`index.html`, bumped by hand on real changes — belt-and-suspenders for
clients (or caches) that already hold a pre-existing entry from before the
`Cache-Control` header existed, which no server-side header change alone
can retroactively invalidate.

`nginx.conf` note: `add_header` inside a `location` block does not merge
with the `server`-level `add_header` list, it replaces it — the CSP/
`X-Content-Type-Options`/`X-Frame-Options`/`Referrer-Policy` headers are
repeated inside the new `location ~* \.(?:html|css|js)$ {}` block for this
reason, not duplicated by oversight.

### 2. The new health badge was blocked by CORS, not actually broken
`checkSystemHealth()` fetches `${RAG_BASE}/actuator/health` cross-origin
from the browser. `CorsConfig` (platform-common) only registers CORS for
`/api/**` via `WebMvcConfigurer.addCorsMappings()` — actuator endpoints
are served by Spring Boot's own, separate `WebMvcEndpointHandlerMapping`,
which that registry has no effect on at all. The badge showed "Sistema
offline" even though `curl` (which does not enforce CORS) showed the
service healthy. Fixed with the actuator-specific
`management.endpoints.web.cors.*` properties in rag-service's
`application.yml`, reusing the same `WEB_UI_ORIGIN` value `CorsConfig`
already reads — not a second, divergent CORS policy, the same origin
allowed through the mechanism actuator actually respects.

### 3. A temporal-dead-zone crash silently disabled Ask/Upload/Conversation on every page load
The most serious of the three. `app.js`'s initial call site —
`renderAuthState(); applyTranslations(); showView("knowledge");` — was
placed midway through the file, *before* `const modelSelect = ...` and
every other DOM-reference `const` declared further down. `showView`
synchronously calls `renderKnowledgeStats()`, which reads
`modelSelect.selectedOptions` — a `const` still in its temporal dead zone
at that point in top-level script execution throws `ReferenceError:
Cannot access 'modelSelect' before initialization`. Because this happens
synchronously and uncaught at the top level, **the entire rest of the
script never ran** — every `const` declaration and `addEventListener`
call positioned after that point (the ask form, upload form, model
select, image attachment, diagram rendering, conversation send) was
silently skipped on every single page load. Login, registration, and
invite still worked (their listeners are registered earlier in the file),
which is exactly what made this easy to miss in a quick check and exactly
why it surfaced only once the Knowledge/Documents/Conversations flows were
actually exercised by hand. Fixed by moving the three init calls to the
literal end of the file, after every other top-level declaration.

**Why this matters for how this project treats "done"**: this is the
kind of regression a build-time linter (`no-use-before-define`) or even a
basic smoke test would have caught before it ever reached a live check —
worth noting as a real, concrete argument for the tooling this project has
deliberately not added yet (ADR 0033's territory), not just an abstract
one.

## Consequences
The visual redesign, the real i18n system, and the admin-panel split are
additive changes to a project already declared portfolio-complete (ADR
0049) — they do not reopen that closure, they are exactly the kind of
"evolution" ADR 0049 anticipated. The three bugs this session's own
verification caught and fixed did not exist in the pre-redesign `web-ui`;
they were introduced by this change and are fixed within it, not carried
forward as new debt.
