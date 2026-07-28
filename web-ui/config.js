// Static config for the free public demo (ADR 0020), used by static-hosting
// providers (e.g. Netlify) that just serve files as-is, with no server-side
// templating step. The Docker-based deployment (docker-compose, or a container
// platform) does NOT use this file — its own entrypoint generates config.js from
// config.js.template at container start instead, always overwriting whatever is
// here. This file exists only so a plain static host has something to serve.
window.RAG_PLATFORM_CONFIG = {
  demoMode: true,
  ragBaseUrl: "https://ag-service-demo.onrender.com"
};
