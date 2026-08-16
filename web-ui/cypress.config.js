const { defineConfig } = require("cypress");

// Runs against the real local docker-compose stack (web-ui + all 4 Java
// services + real Ollama) - not a mocked backend. See docs/adr for the
// full reasoning: some flows here make a real LLM call, so assertions in
// the specs check response *structure*, never exact generated text.
module.exports = defineConfig({
  e2e: {
    baseUrl: "http://localhost:3000",
    supportFile: "cypress/support/e2e.js",
    defaultCommandTimeout: 15000,
    // Ask/summarize/FAQ flows call a real, CPU-bound local Ollama model -
    // found for real running the full suite back-to-back: a single spec in
    // isolation answers in ~20s, but stacked right after other specs' real
    // LLM calls with no cool-down, the same question took over 90s once.
    // Same "CPU-bound local inference has real, sometimes large variance"
    // characteristic RagQualityBenchmark's own javadoc already documents.
    requestTimeout: 60000,
    responseTimeout: 150000,
  },
  env: {
    authBase: "http://localhost:8084",
    ingestionBase: "http://localhost:8081",
    ragBase: "http://localhost:8082",
  },
});
