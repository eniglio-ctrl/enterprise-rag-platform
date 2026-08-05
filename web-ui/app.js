// ---------------------------------------------------------------------------
// i18n: a real PT/EN toggle, not just a visual reskin (user's explicit choice).
// Every string the frontend itself controls is translated; a message that
// comes straight from the backend (ErrorResponse.message - validation errors,
// exceptions) is passed through as-is, in whatever language the backend
// already generates it in (English) - translating arbitrary backend text
// would need an i18n system on the backend too, out of scope here.
// ---------------------------------------------------------------------------
const LANG_STORAGE_KEY = "ragPlatformLang";

const STRINGS = {
  en: {
    "header.brand": "Enterprise RAG",
    "header.online": "System online",
    "header.offline": "System offline",
    "header.logout": "Log out",
    "header.tenant": "Tenant",
    "header.roleAdmin": "Admin",
    "header.roleMember": "Member",

    "nav.knowledge": "Knowledge",
    "nav.conversations": "Conversations",
    "nav.documents": "Documents",
    "nav.settings": "Settings",

    "demo.banner": "Public demo — a fixed set of documents is already indexed below; there's no login and no permanent document upload here (see the README for how to run the full stack locally with your own files). You can still attach an image to a single question.",

    "auth.loginHeading": "Log in",
    "auth.loginHint": "Everything you upload and every question you ask is scoped to your tenant. Registering with no invitation token creates a brand-new tenant just for you; a teammate joins yours with an invitation you send them from Settings after logging in.",
    "auth.emailPlaceholder": "Email",
    "auth.passwordPlaceholder": "Password",
    "auth.loginButton": "Log in",
    "auth.loginLoading": "Logging in...",
    "auth.loginFailed": "Login failed.",
    "auth.registerHeading": "Register",
    "auth.registerPasswordPlaceholder": "Password (min 8 characters)",
    "auth.invitationPlaceholder": "Invitation token (leave blank to create a new organization)",
    "auth.registerButton": "Register",
    "auth.registerLoading": "Creating account...",
    "auth.registerFailed": "Registration failed.",
    "auth.sessionExpired": "Your session expired. Please log in again.",

    "knowledge.heading": "Ask your knowledge base",
    "knowledge.subheading": "Reliable answers with verifiable sources.",
    "knowledge.hint": "Ask a question about what you uploaded, or ask for a diagram/drawing/flow of an architecture or process described in it — the same box handles both, based on what you ask. You can also attach an image (PNG/JPEG/GIF/WebP) to a single question — click the paperclip or just paste a screenshot (Cmd/Ctrl+V) straight into the question box.",
    "knowledge.questionPlaceholder": "e.g. What are the two SAGA models? or Draw the disaster recovery architecture described",
    "knowledge.attachTitle": "Attach an image to this question",
    "knowledge.modelLabel": "Model",
    "knowledge.askButton": "Ask",
    "knowledge.retrieving": "Retrieving context and generating a response...",
    "knowledge.describingImage": "Describing the attached image and generating a response...",
    "knowledge.askingPublic": "Asking a public AI model (not grounded in your documents)...",
    "knowledge.noDiagramFound": "No architecture, process or flow was found in the ingested content to diagram.",
    "knowledge.diagramRenderError": "The generated diagram could not be rendered.",
    "knowledge.somethingWrong": "Something went wrong.",
    "knowledge.imagePasted": "Image pasted from clipboard: {{name}}",
    "knowledge.fallbackPrompt": "No answer was found in your documents. Search a public AI model (OpenAI/Gemini) instead? The answer won't be grounded in your documents, and this uses a paid/rate-limited API.",
    "knowledge.fallbackYes": "Search public AI",
    "knowledge.fallbackNo": "Cancel",
    "knowledge.answerHeading": "Answer",
    "knowledge.provenanceBadge": "Public AI answer, not verified against your documents",
    "knowledge.citationsHeading": "Sources consulted",
    "knowledge.noCitations": "No sources were retrieved for this question.",
    "knowledge.citationScore": "score {{score}}",
    "knowledge.citationChunk": "chunk {{index}}",
    "knowledge.copyAnswer": "Copy answer",
    "knowledge.copied": "Copied to clipboard.",

    "stats.heading": "Knowledge base",
    "stats.hybridSearch": "Hybrid search",
    "stats.active": "Active",
    "stats.tenant": "Tenant",
    "stats.model": "Model in use",
    "stats.documents": "Documents",

    "documents.heading": "Documents",
    "documents.uploadHint": "PDF, DOCX, Markdown, plain text, an image (described by a vision model), or audio (transcribed by a local Whisper server). Either way, the derived text is indexed automatically after upload.",
    "documents.dropzoneLabel": "Drag a file here or click to choose",
    "documents.uploadButton": "Upload & index",
    "documents.uploading": "Uploading and indexing \"{{name}}\"...",
    "documents.indexed": "Indexed \"{{source}}\" into {{chunks}} chunk(s).",
    "documents.uploadFailed": "Upload failed.",
    "documents.historyMeta": "{{pages}} page(s) · {{chunks}} chunk(s)",
    "documents.adminHeading": "Manage sharing",
    "documents.adminHint": "Admin-only (ADR 0047): restrict any document in the tenant to specific teammates instead of everyone.",
    "documents.ownerLabel": "owner",
    "documents.visibilityTenant": "Visible to the whole tenant",
    "documents.visibilityRestricted": "Restricted",
    "documents.saveButton": "Save",
    "documents.saving": "Saving...",
    "documents.saved": "Saved.",
    "documents.saveSharingFailed": "Could not update sharing.",
    "documents.loadAdminFailed": "Could not load the admin panel.",

    "settings.heading": "Settings",
    "settings.inviteHeading": "Invite a teammate",
    "settings.inviteHint": "Creates a single-use invitation for your own tenant, valid for a limited time. Share the token with your teammate — they paste it into the invitation token field when registering.",
    "settings.inviteEmailPlaceholder": "Teammate's email",
    "settings.inviteButton": "Create invitation",
    "settings.creatingInvitation": "Creating invitation...",
    "settings.invitationCreated": "Invitation token (valid until {{expiresAt}}): {{token}}",
    "settings.inviteFailed": "Could not create the invitation.",
    "settings.teamHeading": "Team",
    "settings.teamHint": "Admin-only (ADR 0047): promote or demote another member of your tenant.",
    "settings.makeAdmin": "Make admin",
    "settings.makeMember": "Make member",
    "settings.updatingRole": "Updating role...",
    "settings.roleUpdateFailed": "Could not update the role.",

    "conversations.heading": "Conversations",
    "conversations.hint": "Multi-turn chat with memory — unlike Knowledge, each message here remembers the whole conversation, so a follow-up resolves using the prior message's context, not just the question in isolation.",
    "conversations.startButton": "Start a new conversation",
    "conversations.starting": "Starting a new conversation...",
    "conversations.startFailed": "Could not start a conversation (status {{status}}).",
    "conversations.inputPlaceholder": "Send a message in this conversation...",
    "conversations.sendButton": "Send",
    "conversations.thinking": "Thinking...",
    "conversations.sourcesLabel": "Sources: {{sources}}",
    "conversations.conversationLabel": "Conversation {{id}}",
  },
  pt: {
    "header.brand": "Enterprise RAG",
    "header.online": "Sistema online",
    "header.offline": "Sistema offline",
    "header.logout": "Sair",
    "header.tenant": "Tenant",
    "header.roleAdmin": "Admin",
    "header.roleMember": "Membro",

    "nav.knowledge": "Conhecimento",
    "nav.conversations": "Conversas",
    "nav.documents": "Documentos",
    "nav.settings": "Configurações",

    "demo.banner": "Demonstração pública — um conjunto fixo de documentos já está indexado abaixo; não há login nem upload permanente de documentos aqui (veja o README para rodar o stack completo localmente com seus próprios arquivos). Você ainda pode anexar uma imagem a uma única pergunta.",

    "auth.loginHeading": "Entrar",
    "auth.loginHint": "Tudo que você envia e cada pergunta que faz fica restrito ao seu tenant. Registrar-se sem um token de convite cria uma organização nova só sua; um colega entra na sua através de um convite enviado em Configurações depois de fazer login.",
    "auth.emailPlaceholder": "Email",
    "auth.passwordPlaceholder": "Senha",
    "auth.loginButton": "Entrar",
    "auth.loginLoading": "Entrando...",
    "auth.loginFailed": "Falha ao entrar.",
    "auth.registerHeading": "Registrar",
    "auth.registerPasswordPlaceholder": "Senha (mínimo 8 caracteres)",
    "auth.invitationPlaceholder": "Token de convite (deixe em branco para criar uma nova organização)",
    "auth.registerButton": "Registrar",
    "auth.registerLoading": "Criando conta...",
    "auth.registerFailed": "Falha no registro.",
    "auth.sessionExpired": "Sua sessão expirou. Faça login novamente.",

    "knowledge.heading": "Pergunte à sua base de conhecimento",
    "knowledge.subheading": "Respostas confiáveis, com fontes verificáveis.",
    "knowledge.hint": "Faça uma pergunta sobre o que você enviou, ou peça um diagrama/fluxo de uma arquitetura ou processo descrito nele — a mesma caixa cuida dos dois casos, dependendo do que você pedir. Você também pode anexar uma imagem (PNG/JPEG/GIF/WebP) a uma única pergunta — clique no clipe ou cole um print (Cmd/Ctrl+V) direto na caixa de pergunta.",
    "knowledge.questionPlaceholder": "ex.: Quais são os dois modelos de SAGA? ou Desenhe a arquitetura de disaster recovery descrita",
    "knowledge.attachTitle": "Anexar uma imagem a esta pergunta",
    "knowledge.modelLabel": "Modelo",
    "knowledge.askButton": "Perguntar",
    "knowledge.retrieving": "Recuperando contexto e gerando uma resposta...",
    "knowledge.describingImage": "Descrevendo a imagem anexada e gerando uma resposta...",
    "knowledge.askingPublic": "Perguntando a um modelo de IA público (não baseado nos seus documentos)...",
    "knowledge.noDiagramFound": "Nenhuma arquitetura, processo ou fluxo foi encontrado no conteúdo indexado para desenhar.",
    "knowledge.diagramRenderError": "O diagrama gerado não pôde ser renderizado.",
    "knowledge.somethingWrong": "Algo deu errado.",
    "knowledge.imagePasted": "Imagem colada da área de transferência: {{name}}",
    "knowledge.fallbackPrompt": "Não encontrei uma resposta nos seus documentos. Buscar em um modelo de IA pública (OpenAI/Gemini)? A resposta não será baseada nos seus documentos, e isso usa uma API paga/com limite de uso.",
    "knowledge.fallbackYes": "Buscar em IA pública",
    "knowledge.fallbackNo": "Cancelar",
    "knowledge.answerHeading": "Resposta",
    "knowledge.provenanceBadge": "Resposta de IA pública, não verificada com seus documentos",
    "knowledge.citationsHeading": "Fontes consultadas",
    "knowledge.noCitations": "Nenhuma fonte foi recuperada para esta pergunta.",
    "knowledge.citationScore": "pontuação {{score}}",
    "knowledge.citationChunk": "trecho {{index}}",
    "knowledge.copyAnswer": "Copiar resposta",
    "knowledge.copied": "Copiado para a área de transferência.",

    "stats.heading": "Base de conhecimento",
    "stats.hybridSearch": "Busca híbrida",
    "stats.active": "Ativa",
    "stats.tenant": "Tenant",
    "stats.model": "Modelo em uso",
    "stats.documents": "Documentos",

    "documents.heading": "Documentos",
    "documents.uploadHint": "PDF, DOCX, Markdown, texto simples, uma imagem (descrita por um modelo de visão) ou áudio (transcrito por um servidor Whisper local). De toda forma, o texto derivado é indexado automaticamente após o envio.",
    "documents.dropzoneLabel": "Arraste um arquivo aqui ou clique para escolher",
    "documents.uploadButton": "Enviar e indexar",
    "documents.uploading": "Enviando e indexando \"{{name}}\"...",
    "documents.indexed": "\"{{source}}\" indexado em {{chunks}} trecho(s).",
    "documents.uploadFailed": "Falha no envio.",
    "documents.historyMeta": "{{pages}} página(s) · {{chunks}} trecho(s)",
    "documents.adminHeading": "Gerenciar permissões",
    "documents.adminHint": "Só para admin (ADR 0047): restrinja qualquer documento do tenant a colegas específicos em vez de todo mundo.",
    "documents.ownerLabel": "dono",
    "documents.visibilityTenant": "Visível para todo o tenant",
    "documents.visibilityRestricted": "Restrito",
    "documents.saveButton": "Salvar",
    "documents.saving": "Salvando...",
    "documents.saved": "Salvo.",
    "documents.saveSharingFailed": "Não foi possível atualizar o compartilhamento.",
    "documents.loadAdminFailed": "Não foi possível carregar o painel de administração.",

    "settings.heading": "Configurações",
    "settings.inviteHeading": "Convidar um colega",
    "settings.inviteHint": "Cria um convite de uso único para o seu tenant, válido por tempo limitado. Compartilhe o token com seu colega — ele cola no campo de token de convite ao se registrar.",
    "settings.inviteEmailPlaceholder": "Email do colega",
    "settings.inviteButton": "Criar convite",
    "settings.creatingInvitation": "Criando convite...",
    "settings.invitationCreated": "Token de convite (válido até {{expiresAt}}): {{token}}",
    "settings.inviteFailed": "Não foi possível criar o convite.",
    "settings.teamHeading": "Equipe",
    "settings.teamHint": "Só para admin (ADR 0047): promova ou rebaixe outro membro do seu tenant.",
    "settings.makeAdmin": "Tornar admin",
    "settings.makeMember": "Tornar membro",
    "settings.updatingRole": "Atualizando papel...",
    "settings.roleUpdateFailed": "Não foi possível atualizar o papel.",

    "conversations.heading": "Conversas",
    "conversations.hint": "Chat multi-turno com memória — diferente do Conhecimento, cada mensagem aqui lembra da conversa inteira, então uma pergunta de acompanhamento é resolvida usando o contexto da mensagem anterior, não só a pergunta isolada.",
    "conversations.startButton": "Iniciar uma nova conversa",
    "conversations.starting": "Iniciando uma nova conversa...",
    "conversations.startFailed": "Não foi possível iniciar uma conversa (status {{status}}).",
    "conversations.inputPlaceholder": "Envie uma mensagem nesta conversa...",
    "conversations.sendButton": "Enviar",
    "conversations.thinking": "Pensando...",
    "conversations.sourcesLabel": "Fontes: {{sources}}",
    "conversations.conversationLabel": "Conversa {{id}}",
  },
};

let currentLang = localStorage.getItem(LANG_STORAGE_KEY) === "en" ? "en" : "pt";

function t(key, vars) {
  const template = (STRINGS[currentLang] && STRINGS[currentLang][key]) || STRINGS.en[key] || key;
  if (!vars) {
    return template;
  }
  return Object.keys(vars).reduce((str, name) => str.replaceAll(`{{${name}}}`, vars[name]), template);
}

function applyTranslations() {
  document.querySelectorAll("[data-i18n]").forEach((el) => {
    el.textContent = t(el.dataset.i18n);
  });
  document.querySelectorAll("[data-i18n-placeholder]").forEach((el) => {
    el.placeholder = t(el.dataset.i18nPlaceholder);
  });
  document.querySelectorAll("[data-i18n-title]").forEach((el) => {
    el.title = t(el.dataset.i18nTitle);
  });
  document.documentElement.lang = currentLang;
  document.querySelectorAll(".lang-option").forEach((button) => {
    button.classList.toggle("active", button.dataset.lang === currentLang);
  });
  updateAuthSummary();
}

function setLanguage(lang) {
  currentLang = lang === "en" ? "en" : "pt";
  localStorage.setItem(LANG_STORAGE_KEY, currentLang);
  applyTranslations();
}

// ---------------------------------------------------------------------------

const INGESTION_BASE = window.RAG_PLATFORM_CONFIG?.ingestionBaseUrl ?? "http://localhost:8081";
const RAG_BASE = window.RAG_PLATFORM_CONFIG?.ragBaseUrl ?? "http://localhost:8082";
const AUTH_BASE = window.RAG_PLATFORM_CONFIG?.authBaseUrl ?? "http://localhost:8084";
// chat-service isn't part of the public demo deployment (ADR 0020) - only reached
// when DEMO_MODE is false, same condition the conversation nav item itself is hidden
// under (renderAuthState() below).
const CHAT_BASE = window.RAG_PLATFORM_CONFIG?.chatBaseUrl ?? "http://localhost:8083";
// ADR 0020: the free public demo has no auth-service and no upload — rag-service's
// own "demo" Spring profile treats every request as one fixed tenant regardless of
// headers sent, so there's nothing for a login form or bearer token to accomplish
// there. Everywhere else (local, docker-compose), this stays false and behavior is
// unchanged from before this flag existed.
const DEMO_MODE = window.RAG_PLATFORM_CONFIG?.demoMode ?? false;

// localStorage, not an HttpOnly cookie (ADR 0016): auth-service would need to set a
// cross-origin cookie for a static file server on a different port, complicating CORS
// for no real security gain in a local/demo deployment. Documented tradeoff, not an
// oversight — a real production deployment behind one origin could revisit this.
const TOKEN_STORAGE_KEY = "ragPlatformAuth";

const authPanel = document.getElementById("auth-panel");
const appShell = document.getElementById("app-shell");
const userMenuButton = document.getElementById("user-menu-button");
const userMenuDropdown = document.getElementById("user-menu-dropdown");
const userMenuEmail = document.getElementById("user-menu-email");
const userMenuTenant = document.getElementById("user-menu-tenant");
const userMenuRole = document.getElementById("user-menu-role");
const logoutButton = document.getElementById("logout-button");
const demoBanner = document.getElementById("demo-banner");

const loginForm = document.getElementById("login-form");
const loginStatus = document.getElementById("login-status");
const registerForm = document.getElementById("register-form");
const registerStatus = document.getElementById("register-status");
const inviteForm = document.getElementById("invite-form");
const inviteStatus = document.getElementById("invite-status");
const adminUsersList = document.getElementById("admin-users-list");
const adminUsersStatus = document.getElementById("admin-users-status");
const adminDocumentsList = document.getElementById("admin-documents-list");
const adminDocumentsStatus = document.getElementById("admin-documents-status");
const adminTeamSection = document.getElementById("admin-team-section");
const adminDocumentsSection = document.getElementById("admin-documents-section");

let lastKnownDocumentCount = null;

function getAuth() {
  try {
    const raw = localStorage.getItem(TOKEN_STORAGE_KEY);
    if (!raw) return null;
    const auth = JSON.parse(raw);
    if (!auth.token || Date.now() >= auth.expiresAt) {
      localStorage.removeItem(TOKEN_STORAGE_KEY);
      return null;
    }
    return auth;
  } catch {
    return null;
  }
}

function setAuth({ token, expiresInSeconds, tenantId, userId, email, role }) {
  localStorage.setItem(
    TOKEN_STORAGE_KEY,
    JSON.stringify({ token, expiresAt: Date.now() + expiresInSeconds * 1000, tenantId, userId, email, role })
  );
  renderAuthState();
}

function clearAuth(message) {
  localStorage.removeItem(TOKEN_STORAGE_KEY);
  renderAuthState();
  if (message) {
    setStatus(loginStatus, message, "error");
  }
}

function authHeader() {
  const auth = getAuth();
  return auth ? { Authorization: `Bearer ${auth.token}` } : {};
}

function updateAuthSummary() {
  const auth = getAuth();
  if (!auth) {
    return;
  }
  userMenuEmail.textContent = auth.email ?? auth.userId;
  userMenuTenant.textContent = `${t("header.tenant")}: ${auth.tenantId}`;
  userMenuRole.textContent = auth.role === "ADMIN" ? t("header.roleAdmin") : t("header.roleMember");
  userMenuRole.hidden = false;
}

function renderAuthState() {
  if (DEMO_MODE) {
    authPanel.hidden = true;
    appShell.hidden = false;
    document.getElementById("user-menu").hidden = true;
    setNavVisible("conversations", false);
    setNavVisible("documents", false);
    setNavVisible("settings", false);
    demoBanner.hidden = false;
    showView("knowledge");
    loadModels();
    checkSystemHealth();
    return;
  }

  const auth = getAuth();
  const authenticated = Boolean(auth);
  authPanel.hidden = authenticated;
  appShell.hidden = !authenticated;
  document.getElementById("user-menu").hidden = !authenticated;
  setNavVisible("conversations", authenticated);
  setNavVisible("documents", authenticated);
  setNavVisible("settings", authenticated);
  if (authenticated) {
    updateAuthSummary();
    loadModels();
    checkSystemHealth();
  }

  // ADR 0047: the two admin-only sections only exist for a tenant's ADMIN - `role`
  // rides along on AuthResponse into localStorage for free (setAuth spreads the
  // whole login/register response body), so no separate lookup is needed here.
  const isAdmin = authenticated && auth.role === "ADMIN";
  adminTeamSection.hidden = !isAdmin;
  adminDocumentsSection.hidden = !isAdmin;
  if (isAdmin) {
    loadAdminPanel();
  }
}

function setNavVisible(name, visible) {
  const button = document.querySelector(`.nav-item[data-view="${name}"]`);
  if (button) {
    button.hidden = !visible;
  }
}

// A real health check, not decoration - hits the same /actuator/health every other
// service in this project already exposes publicly (Security Phase 6).
async function checkSystemHealth() {
  const dot = document.getElementById("system-health-dot");
  const label = document.getElementById("system-health-label");
  try {
    const response = await fetch(`${RAG_BASE}/actuator/health`);
    const body = await response.json();
    const online = response.ok && body.status === "UP";
    dot.classList.toggle("online", online);
    dot.classList.toggle("offline", !online);
    label.textContent = online ? t("header.online") : t("header.offline");
  } catch {
    dot.classList.remove("online");
    dot.classList.add("offline");
    label.textContent = t("header.offline");
  }
}

setInterval(() => {
  if (!appShell.hidden) {
    checkSystemHealth();
  }
}, 30000);

// Populates the model dropdown from rag.available-models (ADR 0017) — the frontend
// never hardcodes model ids/labels, it just renders whatever the backend is
// configured to offer (Ollama models already pulled, plus LM Studio if its local
// server is running).
async function loadModels() {
  try {
    const response = await fetch(`${RAG_BASE}/api/v1/models`, { headers: authHeader() });
    if (!response.ok) {
      return;
    }
    const { models } = await response.json();
    modelSelect.innerHTML = "";
    models.forEach(({ id, label, isDefault }) => {
      const option = document.createElement("option");
      option.value = id;
      option.textContent = label;
      option.selected = isDefault;
      modelSelect.appendChild(option);
    });
    renderKnowledgeStats();
  } catch {
    // Model picker is a convenience, not a critical path — if it fails to load,
    // /api/v1/ask still works with the server's own default model.
  }
}

function renderKnowledgeStats() {
  const auth = getAuth();
  const statsTenant = document.getElementById("stats-tenant");
  const statsModel = document.getElementById("stats-model");
  const statsDocumentsRow = document.getElementById("stats-documents-row");
  const statsDocumentsCount = document.getElementById("stats-documents-count");
  if (!statsTenant) {
    return;
  }
  statsTenant.textContent = auth?.tenantId ?? "—";
  statsModel.textContent = modelSelect.selectedOptions[0]?.textContent ?? "—";
  // Only ever a real number: GET /api/v1/documents is admin-only (ADR 0047), so a
  // non-admin never sees a fabricated or approximated document count here.
  if (auth?.role === "ADMIN" && lastKnownDocumentCount !== null) {
    statsDocumentsRow.hidden = false;
    statsDocumentsCount.textContent = String(lastKnownDocumentCount);
  } else {
    statsDocumentsRow.hidden = true;
  }
}

// ADR 0047: admin-only sections - fetches the tenant's members and every document in
// the tenant (not just the caller's own), so an admin can promote/demote teammates and
// override any document's sharing settings. Both requests 403 for a non-admin, but
// this is only ever called after renderAuthState() already checked auth.role.
async function loadAdminPanel() {
  try {
    const [usersResponse, documentsResponse] = await Promise.all([
      fetch(`${AUTH_BASE}/api/v1/auth/users`, { headers: authHeader() }),
      fetch(`${INGESTION_BASE}/api/v1/documents`, { headers: authHeader() }),
    ]);
    if (!usersResponse.ok || !documentsResponse.ok) {
      throw new Error(t("documents.loadAdminFailed"));
    }
    const users = await usersResponse.json();
    const documents = await documentsResponse.json();
    lastKnownDocumentCount = documents.length;
    renderKnowledgeStats();
    renderAdminUsers(users);
    // The owner's email and the "share with" checkboxes are resolved client-side by
    // joining on `users` here - ingestion-service (owner of documents) never calls
    // auth-service (owner of users), keeping the services decoupled the way they
    // already are everywhere else in this codebase.
    renderAdminDocuments(documents, users);
  } catch (error) {
    setStatus(adminUsersStatus, error.message ?? t("documents.loadAdminFailed"), "error");
  }
}

function renderAdminUsers(users) {
  const auth = getAuth();
  adminUsersList.innerHTML = "";
  users.forEach((user) => {
    const item = document.createElement("li");
    const isSelf = user.id === auth?.userId;
    const nextRole = user.role === "ADMIN" ? "MEMBER" : "ADMIN";
    item.innerHTML = `
      <span class="source">${escapeHtml(user.email)}</span>
      <span class="meta">${escapeHtml(user.role)}</span>
      ${isSelf ? "" : `<button type="button" data-user-id="${escapeHtml(user.id)}" data-next-role="${nextRole}">${
        nextRole === "ADMIN" ? t("settings.makeAdmin") : t("settings.makeMember")
      }</button>`}
    `;
    adminUsersList.appendChild(item);
  });

  adminUsersList.querySelectorAll("button[data-user-id]").forEach((button) => {
    button.addEventListener("click", async () => {
      setStatus(adminUsersStatus, t("settings.updatingRole"));
      try {
        const response = await fetch(
          `${AUTH_BASE}/api/v1/auth/users/${button.dataset.userId}/role`,
          {
            method: "PATCH",
            headers: { "Content-Type": "application/json", ...authHeader() },
            body: JSON.stringify({ role: button.dataset.nextRole }),
          },
        );
        const body = await response.json();
        if (!response.ok) {
          throw new Error(body.message ?? t("settings.roleUpdateFailed"));
        }
        setStatus(adminUsersStatus, "", "");
        loadAdminPanel();
      } catch (error) {
        setStatus(adminUsersStatus, error.message ?? t("settings.roleUpdateFailed"), "error");
      }
    });
  });
}

function renderAdminDocuments(documents, users) {
  const emailById = new Map(users.map((user) => [user.id, user.email]));
  adminDocumentsList.innerHTML = "";
  documents.forEach((doc) => {
    const item = document.createElement("li");
    const ownerEmail = emailById.get(doc.ownerId) ?? doc.ownerId;
    const checkboxes = users
      .filter((user) => user.id !== doc.ownerId)
      .map((user) => `
        <label>
          <input type="checkbox" value="${escapeHtml(user.id)}" ${doc.sharedWith.includes(user.id) ? "checked" : ""} />
          ${escapeHtml(user.email)}
        </label>
      `)
      .join("");
    item.innerHTML = `
      <div>
        <span class="source">${escapeHtml(doc.source)}</span>
        <span class="meta">${t("documents.ownerLabel")}: ${escapeHtml(ownerEmail)}</span>
      </div>
      <div class="admin-document-controls">
        <select class="admin-visibility-select">
          <option value="TENANT" ${doc.visibility === "TENANT" ? "selected" : ""}>${t("documents.visibilityTenant")}</option>
          <option value="RESTRICTED" ${doc.visibility === "RESTRICTED" ? "selected" : ""}>${t("documents.visibilityRestricted")}</option>
        </select>
        <button type="button" class="admin-save-sharing">${t("documents.saveButton")}</button>
      </div>
      <div class="admin-shared-with" ${doc.visibility === "RESTRICTED" ? "" : "hidden"}>${checkboxes}</div>
      <div class="status admin-doc-status" hidden></div>
    `;

    const select = item.querySelector(".admin-visibility-select");
    const sharedWithDiv = item.querySelector(".admin-shared-with");
    const docStatus = item.querySelector(".admin-doc-status");
    select.addEventListener("change", () => {
      sharedWithDiv.hidden = select.value !== "RESTRICTED";
    });

    item.querySelector(".admin-save-sharing").addEventListener("click", async () => {
      const visibility = select.value;
      const sharedWith = Array.from(sharedWithDiv.querySelectorAll("input:checked")).map((input) => input.value);
      setStatus(docStatus, t("documents.saving"));
      try {
        const response = await fetch(`${INGESTION_BASE}/api/v1/documents/${doc.documentId}/sharing`, {
          method: "PATCH",
          headers: { "Content-Type": "application/json", ...authHeader() },
          body: JSON.stringify({ visibility, sharedWith }),
        });
        const body = await response.json();
        if (!response.ok) {
          throw new Error(body.message ?? t("documents.saveSharingFailed"));
        }
        setStatus(docStatus, t("documents.saved"), "success");
      } catch (error) {
        setStatus(docStatus, error.message ?? t("documents.saveSharingFailed"), "error");
      }
    });

    adminDocumentsList.appendChild(item);
  });
}

loginForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const email = document.getElementById("login-email").value.trim();
  const password = document.getElementById("login-password").value;

  setStatus(loginStatus, t("auth.loginLoading"));
  try {
    const response = await fetch(`${AUTH_BASE}/api/v1/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email, password }),
    });
    const body = await response.json();
    if (!response.ok) {
      throw new Error(body.message ?? t("auth.loginFailed"));
    }
    setAuth({ ...body, email });
    loginForm.reset();
    setStatus(loginStatus, "", "");
  } catch (error) {
    setStatus(loginStatus, error.message ?? t("auth.loginFailed"), "error");
  }
});

registerForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const email = document.getElementById("register-email").value.trim();
  const password = document.getElementById("register-password").value;
  const invitationToken = document.getElementById("register-invitation").value.trim() || null;

  setStatus(registerStatus, t("auth.registerLoading"));
  try {
    const response = await fetch(`${AUTH_BASE}/api/v1/auth/register`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email, password, invitationToken }),
    });
    const body = await response.json();
    if (!response.ok) {
      throw new Error(body.message ?? t("auth.registerFailed"));
    }
    setAuth({ ...body, email });
    registerForm.reset();
    setStatus(registerStatus, "", "");
  } catch (error) {
    setStatus(registerStatus, error.message ?? t("auth.registerFailed"), "error");
  }
});

inviteForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const email = document.getElementById("invite-email").value.trim();

  setStatus(inviteStatus, t("settings.creatingInvitation"));
  try {
    const response = await fetch(`${AUTH_BASE}/api/v1/auth/invitations`, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...authHeader() },
      body: JSON.stringify({ email }),
    });
    const body = await response.json();
    if (!response.ok) {
      throw new Error(body.message ?? t("settings.inviteFailed"));
    }
    inviteForm.reset();
    setStatus(inviteStatus, t("settings.invitationCreated", {
      expiresAt: new Date(body.expiresAt).toLocaleString(),
      token: body.token,
    }), "");
  } catch (error) {
    setStatus(inviteStatus, error.message ?? t("settings.inviteFailed"), "error");
  }
});

logoutButton.addEventListener("click", () => {
  userMenuDropdown.hidden = true;
  clearAuth();
});

userMenuButton.addEventListener("click", () => {
  userMenuDropdown.hidden = !userMenuDropdown.hidden;
});

document.addEventListener("click", (event) => {
  if (!userMenuDropdown.hidden && !document.getElementById("user-menu").contains(event.target)) {
    userMenuDropdown.hidden = true;
  }
});

document.querySelectorAll(".lang-option").forEach((button) => {
  button.addEventListener("click", () => setLanguage(button.dataset.lang));
});

// ---------------------------------------------------------------------------
// Sidebar navigation - shows one view at a time, no router/hash needed for a
// portfolio-scale app. Demo mode hides every nav item except Knowledge
// (renderAuthState above), matching what those views used to be hidden for
// individually before this redesign.
// ---------------------------------------------------------------------------
const VIEW_NAMES = ["knowledge", "conversations", "documents", "settings"];

function showView(name) {
  VIEW_NAMES.forEach((view) => {
    document.getElementById(`view-${view}`).hidden = view !== name;
    document.querySelector(`.nav-item[data-view="${view}"]`)?.classList.toggle("active", view === name);
  });
  if (name === "knowledge") {
    renderKnowledgeStats();
  }
}

document.querySelectorAll(".nav-item").forEach((button) => {
  button.addEventListener("click", () => showView(button.dataset.view));
});

const dropzone = document.getElementById("dropzone");
const dropzoneLabel = document.getElementById("dropzone-label");
const fileInput = document.getElementById("file-input");
const uploadForm = document.getElementById("upload-form");
const uploadButton = document.getElementById("upload-button");
const uploadStatus = document.getElementById("upload-status");
const uploadHistory = document.getElementById("upload-history");

const askForm = document.getElementById("ask-form");
const askButton = document.getElementById("ask-button");
const askStatus = document.getElementById("ask-status");
const questionInput = document.getElementById("question-input");
const modelSelect = document.getElementById("model-select");
const answerCard = document.getElementById("answer-card");
const answerText = document.getElementById("answer-text");
const answerProvenanceBadge = document.getElementById("answer-provenance-badge");
const citationsList = document.getElementById("citations-list");
const copyAnswerButton = document.getElementById("copy-answer-button");

// Multi-LLM Phase 2c/2d (ADR 0038)
const fallbackConfirmCard = document.getElementById("fallback-confirm-card");
const fallbackConfirmYes = document.getElementById("fallback-confirm-yes");
const fallbackConfirmNo = document.getElementById("fallback-confirm-no");
// Set only while fallback-confirm-card is showing - lets the "yes" button re-ask
// the exact same question with useFallback: true without the user retyping it.
// Never carries an attached image: rag-service never offers the fallback at all
// when one is present (ADR 0038), so there is nothing to resend here either way.
let pendingFallbackQuestion = null;

const imageInput = document.getElementById("image-input");
const imageAttachmentPreview = document.getElementById("image-attachment-preview");
const imageAttachmentName = document.getElementById("image-attachment-name");
const imageAttachmentClear = document.getElementById("image-attachment-clear");

const diagramCard = document.getElementById("diagram-card");
const diagramOutput = document.getElementById("diagram-output");
const diagramCitations = document.getElementById("diagram-citations");
let diagramCounter = 0;

// ADR 0013 conversation memory, wired into web-ui here for the first time.
const startConversationButton = document.getElementById("start-conversation-button");
const conversationIdLabel = document.getElementById("conversation-id-label");
const conversationThread = document.getElementById("conversation-thread");
const conversationMessages = document.getElementById("conversation-messages");
const conversationForm = document.getElementById("conversation-form");
const conversationInput = document.getElementById("conversation-input");
const conversationSendButton = document.getElementById("conversation-send-button");
const conversationStatus = document.getElementById("conversation-status");
let currentConversationId = null;

mermaid.initialize({ startOnLoad: false, theme: "dark" });

document.getElementById("config-summary").textContent = DEMO_MODE
  ? `rag-service: ${RAG_BASE} · public demo (ADR 0020)`
  : `ingestion-service: ${INGESTION_BASE} · rag-service: ${RAG_BASE} · auth-service: ${AUTH_BASE} · chat-service: ${CHAT_BASE}`;

function setStatus(el, message, kind) {
  el.textContent = message;
  el.className = `status ${kind ?? ""}`.trim();
  el.hidden = !message;
}

function setFile(file) {
  fileInput.files = createFileList(file);
  dropzoneLabel.textContent = file ? file.name : t("documents.dropzoneLabel");
  uploadButton.disabled = !file;
}

function createFileList(file) {
  const dataTransfer = new DataTransfer();
  if (file) {
    dataTransfer.items.add(file);
  }
  return dataTransfer.files;
}

dropzone.addEventListener("dragover", (event) => {
  event.preventDefault();
  dropzone.classList.add("dragover");
});

dropzone.addEventListener("dragleave", () => dropzone.classList.remove("dragover"));

dropzone.addEventListener("drop", (event) => {
  event.preventDefault();
  dropzone.classList.remove("dragover");
  const file = event.dataTransfer.files[0];
  if (file) {
    setFile(file);
  }
});

fileInput.addEventListener("change", () => setFile(fileInput.files[0]));

function setAttachedImage(file) {
  imageInput.files = createFileList(file);
  imageAttachmentName.textContent = file ? file.name : "";
  imageAttachmentPreview.hidden = !file;
}

imageInput.addEventListener("change", () => setAttachedImage(imageInput.files[0]));

imageAttachmentClear.addEventListener("click", () => setAttachedImage(null));

// Lets a screenshot be pasted straight into the question box (Cmd/Ctrl+V) instead of
// requiring "save to disk, then click the paperclip, then pick the file" — the
// clipboard item becomes a File exactly like one chosen through the file picker, so
// everything downstream (the preview chip, the multipart submit below) is unaffected.
questionInput.addEventListener("paste", (event) => {
  const items = event.clipboardData?.items ?? [];
  const imageItem = Array.from(items).find((item) => item.type.startsWith("image/"));
  if (!imageItem) {
    return;
  }
  event.preventDefault();
  const file = imageItem.getAsFile();
  if (file) {
    setAttachedImage(file);
    setStatus(askStatus, t("knowledge.imagePasted", { name: file.name || file.type }), "success");
  }
});

uploadForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const file = fileInput.files[0];
  if (!file) {
    return;
  }

  uploadButton.disabled = true;
  setStatus(uploadStatus, t("documents.uploading", { name: file.name }));

  const formData = new FormData();
  formData.append("file", file);

  try {
    const response = await fetch(`${INGESTION_BASE}/api/v1/documents`, {
      method: "POST",
      headers: authHeader(),
      body: formData,
    });

    if (response.status === 401) {
      clearAuth(t("auth.sessionExpired"));
      return;
    }

    const body = await response.json();

    if (!response.ok) {
      throw new Error(body.message ?? t("documents.uploadFailed"));
    }

    setStatus(uploadStatus, t("documents.indexed", { source: body.source, chunks: body.chunkCount }), "success");
    addHistoryEntry(body);
    uploadForm.reset();
    setFile(null);
  } catch (error) {
    setStatus(uploadStatus, error.message ?? t("documents.uploadFailed"), "error");
    uploadButton.disabled = false;
  }
});

function addHistoryEntry({ source, chunkCount, pageCount }) {
  const item = document.createElement("li");
  item.innerHTML = `
    <span class="source">${escapeHtml(source)}</span>
    <span class="meta">${t("documents.historyMeta", { pages: pageCount, chunks: chunkCount })}</span>
  `;
  uploadHistory.prepend(item);
}

askForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const question = questionInput.value.trim();
  if (!question) {
    return;
  }
  await performAsk({ question, model: modelSelect.value, attachedImage: imageInput.files[0], useFallback: false });
});

// Multi-LLM Phase 2d (ADR 0038): the confirmation gate's "yes" button - re-asks
// the exact same question with useFallback: true, the one thing that actually
// triggers a real call to the public-LLM fallback (rag-service never calls it
// on the first, unconfirmed request).
fallbackConfirmYes.addEventListener("click", async () => {
  if (!pendingFallbackQuestion) {
    return;
  }
  const { question, model } = pendingFallbackQuestion;
  fallbackConfirmYes.disabled = true;
  fallbackConfirmNo.disabled = true;
  try {
    await performAsk({ question, model, attachedImage: null, useFallback: true });
  } finally {
    fallbackConfirmYes.disabled = false;
    fallbackConfirmNo.disabled = false;
  }
});

fallbackConfirmNo.addEventListener("click", () => {
  fallbackConfirmCard.hidden = true;
  pendingFallbackQuestion = null;
});

copyAnswerButton.addEventListener("click", async () => {
  try {
    await navigator.clipboard.writeText(answerText.textContent);
    setStatus(askStatus, t("knowledge.copied"), "success");
  } catch {
    // Clipboard API can refuse silently (insecure context, permission) - non-critical,
    // the answer is still fully visible and selectable on the page either way.
  }
});

async function performAsk({ question, model, attachedImage, useFallback }) {
  askButton.disabled = true;
  answerCard.hidden = true;
  diagramCard.hidden = true;
  fallbackConfirmCard.hidden = true;
  setStatus(askStatus, attachedImage
    ? t("knowledge.describingImage")
    : useFallback
      ? t("knowledge.askingPublic")
      : t("knowledge.retrieving"));

  try {
    let response;
    if (attachedImage) {
      // Multipart, not JSON: this is rag-service's only multipart endpoint (the
      // image-attachment form of /api/v1/ask) — the browser sets the multipart
      // boundary itself, so Content-Type must NOT be set explicitly here, same as
      // the document-upload form above.
      const formData = new FormData();
      formData.append("question", question);
      if (model) {
        formData.append("model", model);
      }
      formData.append("image", attachedImage);
      response = await fetch(`${RAG_BASE}/api/v1/ask`, {
        method: "POST",
        headers: authHeader(),
        body: formData,
      });
    } else {
      response = await fetch(`${RAG_BASE}/api/v1/ask`, {
        method: "POST",
        headers: { "Content-Type": "application/json", ...authHeader() },
        body: JSON.stringify({ question, model: model || undefined, useFallback: useFallback || undefined }),
      });
    }

    if (response.status === 401) {
      clearAuth(t("auth.sessionExpired"));
      return;
    }

    const body = await response.json();

    if (!response.ok) {
      throw new Error(body.message ?? `Request failed with status ${response.status}`);
    }

    if (body.type === "diagram") {
      if (!body.mermaid || body.mermaid.includes("Dados insuficientes")) {
        setStatus(askStatus, t("knowledge.noDiagramFound"), "error");
        return;
      }
      setStatus(askStatus, "");
      await renderDiagram(body);
    } else if (body.fallbackAvailable) {
      // Multi-LLM Phase 2c/2d: offered, not answered - no LLM was called yet.
      // Keep the question around so "yes" can resend it with useFallback: true.
      setStatus(askStatus, "");
      pendingFallbackQuestion = { question, model };
      fallbackConfirmCard.hidden = false;
    } else {
      setStatus(askStatus, "");
      pendingFallbackQuestion = null;
      renderAnswer(body);
    }

    setAttachedImage(null);
  } catch (error) {
    setStatus(askStatus, error.message ?? t("knowledge.somethingWrong"), "error");
  } finally {
    askButton.disabled = false;
  }
}

function renderAnswer({ answer, citations, source }) {
  // Multi-LLM Phase 2d (ADR 0038): the one visible signal that this answer was
  // never grounded in the tenant's own documents - reads the backend's explicit
  // `source` field rather than inferring it from citations being empty (which
  // can also happen for other reasons on the normal, local path).
  answerProvenanceBadge.hidden = source !== "public-llm";
  answerText.textContent = answer;
  renderCitations(citationsList, citations);
  answerCard.hidden = false;
}

function renderCitations(listElement, citations) {
  listElement.innerHTML = "";

  if (!citations || citations.length === 0) {
    const item = document.createElement("li");
    item.textContent = t("knowledge.noCitations");
    listElement.appendChild(item);
    return;
  }

  citations.forEach((citation) => {
    const item = document.createElement("li");
    const score = typeof citation.score === "number" ? citation.score.toFixed(3) : "n/a";
    item.innerHTML = `
      <div class="citation-head">
        <span>${escapeHtml(citation.source)} &middot; ${t("knowledge.citationChunk", { index: citation.chunkIndex })}</span>
        <span>${t("knowledge.citationScore", { score })}</span>
      </div>
      <div>${escapeHtml(citation.snippet)}</div>
    `;
    listElement.appendChild(item);
  });
}

async function renderDiagram({ mermaid: definition, citations }) {
  diagramCounter += 1;

  try {
    const { svg } = await mermaid.render(`diagram-${diagramCounter}`, definition);
    diagramOutput.innerHTML = svg;
  } catch (error) {
    diagramOutput.innerHTML = "";
    setStatus(askStatus, t("knowledge.diagramRenderError"), "error");
    return;
  }

  renderCitations(diagramCitations, citations);
  diagramCard.hidden = false;
}

// ADR 0013 conversation memory, wired into web-ui for the first time here - a
// minimal multi-turn panel calling chat-service's own existing endpoints
// directly, no new backend design needed.

startConversationButton.addEventListener("click", async () => {
  startConversationButton.disabled = true;
  setStatus(conversationStatus, t("conversations.starting"));
  try {
    const response = await fetch(`${CHAT_BASE}/api/v1/conversations`, {
      method: "POST",
      headers: authHeader(),
    });

    if (response.status === 401) {
      clearAuth(t("auth.sessionExpired"));
      return;
    }
    if (!response.ok) {
      throw new Error(t("conversations.startFailed", { status: response.status }));
    }

    const body = await response.json();
    currentConversationId = body.conversationId;
    conversationIdLabel.textContent = t("conversations.conversationLabel", { id: currentConversationId });
    conversationIdLabel.hidden = false;
    conversationMessages.innerHTML = "";
    conversationThread.hidden = false;
    setStatus(conversationStatus, "");
    conversationInput.focus();
  } catch (error) {
    setStatus(conversationStatus, error.message ?? t("conversations.startFailed", { status: "?" }), "error");
  } finally {
    startConversationButton.disabled = false;
  }
});

conversationForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const message = conversationInput.value.trim();
  if (!message || !currentConversationId) {
    return;
  }

  appendConversationMessage("user", message);
  conversationInput.value = "";
  conversationSendButton.disabled = true;
  setStatus(conversationStatus, t("conversations.thinking"));

  try {
    const response = await fetch(
      `${CHAT_BASE}/api/v1/conversations/${currentConversationId}/messages`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json", ...authHeader() },
        body: JSON.stringify({ message }),
      }
    );

    if (response.status === 401) {
      clearAuth(t("auth.sessionExpired"));
      return;
    }

    const body = await response.json();
    if (!response.ok) {
      throw new Error(body.message ?? `Request failed with status ${response.status}`);
    }

    appendConversationMessage("assistant", body.answer, body.citations);
    setStatus(conversationStatus, "");
  } catch (error) {
    setStatus(conversationStatus, error.message ?? t("knowledge.somethingWrong"), "error");
  } finally {
    conversationSendButton.disabled = false;
  }
});

function appendConversationMessage(role, content, citations) {
  const item = document.createElement("li");
  item.className = `conversation-message role-${role}`;
  item.innerHTML = `<div>${escapeHtml(content)}</div>`;
  if (citations && citations.length > 0) {
    const sources = citations.map((citation) => escapeHtml(citation.source)).join(", ");
    item.innerHTML += `<div class="conversation-sources">${t("conversations.sourcesLabel", { sources })}</div>`;
  }
  conversationMessages.appendChild(item);
  conversationMessages.scrollTop = conversationMessages.scrollHeight;
}

function escapeHtml(value) {
  const div = document.createElement("div");
  div.textContent = value ?? "";
  return div.innerHTML;
}

// Runs last, on purpose: renderAuthState()/showView("knowledge") synchronously
// touch modelSelect, askForm, and other consts declared throughout this file, so
// this must come after every declaration above it - moved here after it was
// originally placed mid-file and threw "Cannot access before initialization" on
// every page load, silently skipping every listener registration below that point
// (ask, upload, conversation, model select were all dead until this fix).
renderAuthState();
applyTranslations();
showView("knowledge");
