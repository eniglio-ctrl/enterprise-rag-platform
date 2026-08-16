// docs/PRODUCT-DIFFERENTIATION-ROADMAP.md Phase 8 (summarize/FAQ) plus the
// real SSRF-guard error path from the URL-import feature (External Data
// Integration Phase 1) - both make real calls to the backend, so success
// assertions check structure (a summary/FAQ list appeared), never exact
// generated text.
describe("Document insights: summarize, FAQ, and the URL-import error path", () => {
  // One stable tenant per spec file - see documents.cy.js's identical comment
  // for why (auth-service's real 10/min rate limit, ADR 0028).
  const email = `cypress-insights-${Date.now()}@example.com`;
  const password = "CypressTest123!";

  beforeEach(() => {
    cy.registerAndLogin({ email, password });
    cy.visit("/");
    cy.goToView("documents");
  });

  it("summarizes a real uploaded document", () => {
    cy.get("#file-input").selectFile("cypress/fixtures/sample-document.md", { force: true });
    cy.get("#upload-button").click();
    cy.get("#upload-status", { timeout: 30000 }).should("have.class", "success");

    cy.get("#upload-history li").first().within(() => {
      cy.get(".summarize-button").click();
    });

    // Real local Ollama generation over the whole document - same generous
    // budget as documents.cy.js's ask-a-question step.
    cy.get("#insight-card", { timeout: 150000 }).should("be.visible");
    cy.get("#insight-summary").should("be.visible").invoke("text").should("have.length.greaterThan", 0);
  });

  it("generates a real FAQ list for an uploaded document", () => {
    cy.get("#file-input").selectFile("cypress/fixtures/sample-document.md", { force: true });
    cy.get("#upload-button").click();
    cy.get("#upload-status", { timeout: 30000 }).should("have.class", "success");

    cy.get("#upload-history li").first().within(() => {
      cy.get(".generate-faq-button").click();
    });

    cy.get("#insight-card", { timeout: 150000 }).should("be.visible");
    cy.get("#insight-faq-list").should("be.visible");
    cy.get("#insight-faq-list li").should("have.length.at.least", 1);
  });

  it("blocks a URL pointing at a private/internal address and shows a real error", () => {
    // Same address used to manually verify the SSRF guard earlier in this
    // project's history (docs/adr/0051-url-based-document-import.md) - the
    // cloud-metadata endpoint, a real link-local address, not a placeholder.
    cy.get("#url-import-input").type("http://169.254.169.254/latest/meta-data");
    cy.get("#url-import-button").click();

    cy.get("#upload-status", { timeout: 15000 })
      .should("have.class", "error")
      .and("contain.text", "private");
  });
});
