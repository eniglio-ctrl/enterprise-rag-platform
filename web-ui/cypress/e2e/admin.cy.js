// docs/adr/0047-tenant-admin-role.md: the first user of a brand-new tenant
// (exactly what cy.registerAndLogin() creates every time - no invitation
// token) is auto-promoted to ADMIN, so no extra setup is needed to reach
// the admin-only panels here.
describe("Admin panel: team and document sharing", () => {
  // One stable tenant per spec file - see documents.cy.js's identical comment
  // for why (auth-service's real 10/min rate limit, ADR 0028).
  const email = `cypress-admin-${Date.now()}@example.com`;
  const password = "CypressTest123!";

  beforeEach(() => {
    cy.registerAndLogin({ email, password });
    cy.visit("/");
  });

  it("shows the admin-only sections for the tenant's first user", () => {
    cy.goToView("documents");
    cy.get("#admin-documents-section").should("be.visible");

    cy.goToView("settings");
    cy.get("#admin-team-section").should("be.visible");
    cy.get("#admin-users-list li").should("have.length.at.least", 1);
  });

  it("restricts a document's sharing and sees the change reflected", () => {
    cy.goToView("documents");
    cy.get("#file-input").selectFile("cypress/fixtures/sample-document.md", { force: true });
    cy.get("#upload-button").click();
    cy.get("#upload-status", { timeout: 30000 }).should("have.class", "success");

    // The admin document list is only fetched once, at login - reloading
    // re-runs that fetch so the just-uploaded document shows up in it,
    // same as a real admin refreshing the page would see.
    cy.reload();
    cy.goToView("documents");

    // Plain, unscoped queries rather than .within()/.as() - this tenant has
    // exactly one document (just uploaded above), so there's nothing to
    // disambiguate. Found for real: both .within() and an .as() alias
    // reported the child status element as "never found" even after the
    // real PATCH had already visibly succeeded in the app (visible in the
    // command log's network panel and the on-screen "Salvo." text) - looked
    // like a stale-reference retry quirk, not an app bug, so sidestepped
    // it entirely rather than chasing it further.
    cy.get("#admin-documents-list li").should("have.length", 1);
    cy.get(".admin-visibility-select").select("RESTRICTED");
    cy.get(".admin-save-sharing").click();
    // Per-item status (.admin-doc-status), not the section-level
    // #admin-documents-status - found for real running this spec: the save
    // handler (app.js's renderAdminDocuments) only ever updates the row's
    // own status div.
    cy.get(".admin-doc-status", { timeout: 15000 }).should("have.class", "success");
    cy.get(".admin-visibility-select").should("have.value", "RESTRICTED");
  });
});
