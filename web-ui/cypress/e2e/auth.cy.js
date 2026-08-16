// The one spec that fills in the real login/register forms by hand - every
// other spec uses cy.registerAndLogin() instead, since validating these two
// forms is specifically this spec's job.
describe("Authentication", () => {
  const email = `cypress-auth-${Date.now()}@example.com`;
  const password = "CypressTest123!";

  it("registers a brand-new tenant, logs out, and logs back in with the same credentials", () => {
    cy.visit("/");

    cy.get("#auth-panel").should("be.visible");
    cy.get("#app-shell").should("not.be.visible");

    // Deliberately leaves the invitation token blank - the form's own
    // placeholder says this creates a brand-new organization.
    cy.get("#register-email").type(email);
    cy.get("#register-password").type(password);
    cy.get("#register-form button[type='submit']").click();

    // A successful register logs the user in immediately (same response
    // that creates the account also returns a usable token).
    cy.get("#app-shell").should("be.visible");
    cy.get("#auth-panel").should("not.be.visible");
    cy.get("#user-menu-email").should("contain.text", email);

    cy.get("#user-menu-button").click();
    cy.get("#logout-button").click();
    cy.get("#auth-panel").should("be.visible");
    cy.get("#app-shell").should("not.be.visible");

    cy.get("#login-email").type(email);
    cy.get("#login-password").type(password);
    cy.get("#login-form button[type='submit']").click();

    cy.get("#app-shell").should("be.visible");
    cy.get("#user-menu-email").should("contain.text", email);
  });

  it("shows an error status instead of logging in with the wrong password", () => {
    cy.visit("/");

    cy.get("#login-email").type(email);
    cy.get("#login-password").type("definitely-the-wrong-password");
    cy.get("#login-form button[type='submit']").click();

    cy.get("#login-status").should("be.visible").and("have.class", "error");
    cy.get("#app-shell").should("not.be.visible");
  });
});
