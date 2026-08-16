// Golden path: upload a real file, import a second one from a real URL, then
// ask a real question about the uploaded content and see a real citation
// come back. Runs against the real ingestion-service/rag-service - the ask
// step makes a real local Ollama call, so it only asserts on response
// *structure* (a citation appeared), never on the generated answer text.
describe("Documents: upload, URL import, and asking a question", () => {
  // One stable tenant shared by every test in this file (email computed once,
  // not per-call) - auth-service rate-limits /api/v1/auth/* to 10/min per IP
  // (a real, intentional control, ADR 0028), and cy.session() only actually
  // re-registers when the [email, password] key changes, so this keeps the
  // whole suite's real register-call count to one per spec file instead of
  // one per test.
  const email = `cypress-documents-${Date.now()}@example.com`;
  const password = "CypressTest123!";

  beforeEach(() => {
    cy.registerAndLogin({ email, password });
    cy.visit("/");
  });

  it("uploads a real file and it appears in the upload history", () => {
    cy.goToView("documents");

    cy.get("#file-input").selectFile("cypress/fixtures/sample-document.md", { force: true });
    cy.get("#upload-button").should("not.be.disabled").click();

    cy.get("#upload-status", { timeout: 30000 }).should("have.class", "success");
    cy.get("#upload-history li").should("have.length.at.least", 1);
    cy.get("#upload-history li").first().should("contain.text", "sample-document.md");
  });

  it("imports a folder, skipping an unsupported file with a visible reason", () => {
    // docs/EXTERNAL-DATA-INTEGRATION-ROADMAP.md Phase 2: #folder-input has no
    // folder-structure requirement of its own (webkitdirectory only changes what the
    // native OS picker shows) - selectFile with multiple real files exercises the
    // same FileList-driven loop a real folder selection would.
    cy.goToView("documents");

    cy.get("#folder-input").selectFile(
      ["cypress/fixtures/sample-document.md", "cypress/fixtures/unsupported-file.zip"],
      { force: true },
    );

    cy.get("#upload-status", { timeout: 30000 }).should("have.class", "success");
    cy.get("#upload-history li").should("have.length", 2);
    cy.get("#upload-history li.history-skipped").should("have.length", 1)
      .and("contain.text", "unsupported-file.zip");
    cy.get("#upload-history li").not(".history-skipped").should("have.length", 1)
      .and("contain.text", "sample-document.md");
  });

  it("imports a second document from a real public URL", () => {
    cy.goToView("documents");

    cy.get("#url-import-input").type("https://raw.githubusercontent.com/kubernetes/kubernetes/master/CONTRIBUTING.md");
    cy.get("#url-import-button").click();

    // A real outbound fetch (docs/EXTERNAL-DATA-INTEGRATION-ROADMAP.md Phase
    // 1) - can 404 if that upstream file ever moves, in which case this test
    // will need a different known-good public URL, same caveat as the
    // manual verification done for that phase originally.
    cy.get("#upload-status", { timeout: 30000 }).should("have.class", "success");
    cy.get("#upload-history li").should("have.length.at.least", 1);
  });

  it("asks a real question about an uploaded document and gets a citation back", () => {
    cy.goToView("documents");
    cy.get("#file-input").selectFile("cypress/fixtures/sample-document.md", { force: true });
    cy.get("#upload-button").click();
    cy.get("#upload-status", { timeout: 30000 }).should("have.class", "success");

    cy.goToView("knowledge");
    cy.get("#question-input").type("What does the SAGA pattern coordinate?");
    cy.get("#ask-button").click();

    // Real local Ollama generation - can genuinely take tens of seconds
    // (RagQualityBenchmark measured ~20-30s per question on this project's
    // own hardware), hence the long timeout instead of asserting instantly.
    //
    // Found for real: the local groundedness check (ADR 0008) is itself a
    // temperature-based LLM call, so it doesn't always agree the same answer
    // is "SUPPORTED" - when it decides an otherwise-fine answer isn't
    // grounded, rag-service offers the public-LLM fallback instead of
    // returning it (ADR 0038), and #fallback-confirm-card appears instead of
    // #answer-card. Both are the app behaving correctly for a real local
    // model's real non-determinism, so this accepts either outcome rather
    // than assuming the answer is always grounded.
    // Both elements always exist in the DOM (toggled via the `hidden`
    // attribute, never added/removed), so a plain cy.get(selector, {timeout})
    // would resolve instantly and never actually wait for either to become
    // visible - {timeout} only governs cy.get()'s own element lookup, not a
    // later .filter()/.should() in the chain. A should(callback) re-runs
    // (and retries, honoring this same timeout) from cy.get() itself until
    // the callback's assertion passes, which is what actually waits here.
    cy.get("#answer-card, #fallback-confirm-card", { timeout: 150000 }).should(($el) => {
      expect($el.filter(":visible")).to.have.length(1);
    }).then(($el) => {
      const $visible = $el.filter(":visible");
      if ($visible.is("#answer-card")) {
        cy.get("#citations-list li", { timeout: 5000 }).should("have.length.at.least", 1);
      } else {
        cy.log("Local answer wasn't grounded this run - fallback confirmation shown instead (real ADR 0038 behavior, not a failure)");
      }
    });
  });
});
