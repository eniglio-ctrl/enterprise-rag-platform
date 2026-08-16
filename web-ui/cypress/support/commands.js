// Same localStorage key/shape web-ui/app.js's setAuth() itself writes
// (TOKEN_STORAGE_KEY = "ragPlatformAuth") - kept in sync manually, not
// imported, since app.js has no build step / module system to import from.
const TOKEN_STORAGE_KEY = "ragPlatformAuth";

/**
 * Registers a brand-new tenant (no invitation token, matching web-ui's own
 * "leave blank to create a new organization" register form) directly via
 * auth-service's API, then seeds the resulting token into localStorage
 * before visiting the app - faster and less brittle than filling in the
 * register form on every test that just needs to already be logged in.
 * auth.cy.js is the one spec that exercises the actual register/login forms,
 * since validating those forms is its whole point.
 *
 * cy.session caches this across tests in the same run (same email+password
 * key never re-registers), same idea as a browser keeping you logged in.
 */
Cypress.Commands.add("registerAndLogin", (overrides = {}) => {
  const email = overrides.email || `cypress-${Date.now()}-${Math.floor(Math.random() * 100000)}@example.com`;
  const password = overrides.password || "CypressTest123!";

  cy.session(
    [email, password],
    () => {
      cy.request("POST", `${Cypress.env("authBase")}/api/v1/auth/register`, {
        email,
        password,
        invitationToken: null,
      }).then((response) => {
        const auth = {
          token: response.body.token,
          expiresAt: Date.now() + response.body.expiresInSeconds * 1000,
          tenantId: response.body.tenantId,
          userId: response.body.userId,
          email,
          role: response.body.role,
        };
        cy.visit("/");
        cy.window().then((win) => {
          win.localStorage.setItem(TOKEN_STORAGE_KEY, JSON.stringify(auth));
        });
      });
    },
    { validate: () => cy.window().its("localStorage").invoke("getItem", TOKEN_STORAGE_KEY).should("exist") },
  );

  cy.wrap({ email, password }, { log: false });
});

/** Navigates via the real nav buttons (data-view attribute), not a direct URL - this app has no routes/history, just client-side view toggling. */
Cypress.Commands.add("goToView", (viewName) => {
  cy.get(`.nav-item[data-view="${viewName}"]`).click();
});
