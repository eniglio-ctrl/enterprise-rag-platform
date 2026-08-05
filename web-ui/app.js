const INGESTION_BASE = window.RAG_PLATFORM_CONFIG?.ingestionBaseUrl ?? "http://localhost:8081";
const RAG_BASE = window.RAG_PLATFORM_CONFIG?.ragBaseUrl ?? "http://localhost:8082";
const AUTH_BASE = window.RAG_PLATFORM_CONFIG?.authBaseUrl ?? "http://localhost:8084";
// chat-service isn't part of the public demo deployment (ADR 0020) - only reached
// when DEMO_MODE is false, same condition the conversation panel itself is hidden
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
const appLayout = document.getElementById("app-layout");
const authBar = document.getElementById("auth-bar");
const authSummary = document.getElementById("auth-summary");
const logoutButton = document.getElementById("logout-button");

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

function renderAuthState() {
  if (DEMO_MODE) {
    authPanel.hidden = true;
    appLayout.hidden = false;
    authBar.hidden = true;
    document.getElementById("upload-panel").hidden = true;
    document.getElementById("invite-panel").hidden = true;
    document.getElementById("admin-panel").hidden = true;
    document.getElementById("conversation-panel").hidden = true;
    document.getElementById("demo-banner").hidden = false;
    document.getElementById("ask-heading").textContent = "Ask";
    loadModels();
    return;
  }

  const auth = getAuth();
  const authenticated = Boolean(auth);
  authPanel.hidden = authenticated;
  appLayout.hidden = !authenticated;
  authBar.hidden = !authenticated;
  if (authenticated) {
    authSummary.textContent = `${auth.email ?? auth.userId} · tenant ${auth.tenantId}`;
    loadModels();
  }
  // ADR 0047: the admin panel only exists for a tenant's ADMIN - `role` rides along
  // on AuthResponse into localStorage for free (setAuth spreads the whole login/
  // register response body), so no separate lookup is needed here.
  const adminPanel = document.getElementById("admin-panel");
  adminPanel.hidden = !authenticated || auth.role !== "ADMIN";
  if (authenticated && auth.role === "ADMIN") {
    loadAdminPanel();
  }
}

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
  } catch {
    // Model picker is a convenience, not a critical path — if it fails to load,
    // /api/v1/ask still works with the server's own default model.
  }
}

// ADR 0047: admin-only panel - fetches the tenant's members and every document in the
// tenant (not just the caller's own), so an admin can promote/demote teammates and
// override any document's sharing settings. Both requests 403 for a non-admin, but
// this is only ever called after renderAuthState() already checked auth.role.
async function loadAdminPanel() {
  try {
    const [usersResponse, documentsResponse] = await Promise.all([
      fetch(`${AUTH_BASE}/api/v1/auth/users`, { headers: authHeader() }),
      fetch(`${INGESTION_BASE}/api/v1/documents`, { headers: authHeader() }),
    ]);
    if (!usersResponse.ok || !documentsResponse.ok) {
      throw new Error("Could not load the admin panel.");
    }
    const users = await usersResponse.json();
    const documents = await documentsResponse.json();
    renderAdminUsers(users);
    // The owner's email and the "share with" checkboxes are resolved client-side by
    // joining on `users` here - ingestion-service (owner of documents) never calls
    // auth-service (owner of users), keeping the services decoupled the way they
    // already are everywhere else in this codebase.
    renderAdminDocuments(documents, users);
  } catch (error) {
    setStatus(adminUsersStatus, error.message ?? "Could not load the admin panel.", "error");
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
        nextRole === "ADMIN" ? "Make admin" : "Make member"
      }</button>`}
    `;
    adminUsersList.appendChild(item);
  });

  adminUsersList.querySelectorAll("button[data-user-id]").forEach((button) => {
    button.addEventListener("click", async () => {
      setStatus(adminUsersStatus, "Updating role...");
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
          throw new Error(body.message ?? "Could not update the role.");
        }
        setStatus(adminUsersStatus, "", "");
        loadAdminPanel();
      } catch (error) {
        setStatus(adminUsersStatus, error.message ?? "Could not update the role.", "error");
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
        <span class="meta">owner: ${escapeHtml(ownerEmail)}</span>
      </div>
      <div class="admin-document-controls">
        <select class="admin-visibility-select">
          <option value="TENANT" ${doc.visibility === "TENANT" ? "selected" : ""}>Visible to the whole tenant</option>
          <option value="RESTRICTED" ${doc.visibility === "RESTRICTED" ? "selected" : ""}>Restricted</option>
        </select>
        <button type="button" class="admin-save-sharing">Save</button>
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
      setStatus(docStatus, "Saving...");
      try {
        const response = await fetch(`${INGESTION_BASE}/api/v1/documents/${doc.documentId}/sharing`, {
          method: "PATCH",
          headers: { "Content-Type": "application/json", ...authHeader() },
          body: JSON.stringify({ visibility, sharedWith }),
        });
        const body = await response.json();
        if (!response.ok) {
          throw new Error(body.message ?? "Could not update sharing.");
        }
        setStatus(docStatus, "Saved.", "success");
      } catch (error) {
        setStatus(docStatus, error.message ?? "Could not update sharing.", "error");
      }
    });

    adminDocumentsList.appendChild(item);
  });
}

loginForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const email = document.getElementById("login-email").value.trim();
  const password = document.getElementById("login-password").value;

  setStatus(loginStatus, "Logging in...");
  try {
    const response = await fetch(`${AUTH_BASE}/api/v1/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email, password }),
    });
    const body = await response.json();
    if (!response.ok) {
      throw new Error(body.message ?? "Login failed.");
    }
    setAuth({ ...body, email });
    loginForm.reset();
    setStatus(loginStatus, "", "");
  } catch (error) {
    setStatus(loginStatus, error.message ?? "Login failed.", "error");
  }
});

registerForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const email = document.getElementById("register-email").value.trim();
  const password = document.getElementById("register-password").value;
  const invitationToken = document.getElementById("register-invitation").value.trim() || null;

  setStatus(registerStatus, "Creating account...");
  try {
    const response = await fetch(`${AUTH_BASE}/api/v1/auth/register`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email, password, invitationToken }),
    });
    const body = await response.json();
    if (!response.ok) {
      throw new Error(body.message ?? "Registration failed.");
    }
    setAuth({ ...body, email });
    registerForm.reset();
    setStatus(registerStatus, "", "");
  } catch (error) {
    setStatus(registerStatus, error.message ?? "Registration failed.", "error");
  }
});

inviteForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const email = document.getElementById("invite-email").value.trim();

  setStatus(inviteStatus, "Creating invitation...");
  try {
    const response = await fetch(`${AUTH_BASE}/api/v1/auth/invitations`, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...authHeader() },
      body: JSON.stringify({ email }),
    });
    const body = await response.json();
    if (!response.ok) {
      throw new Error(body.message ?? "Could not create the invitation.");
    }
    inviteForm.reset();
    setStatus(inviteStatus, `Invitation token (valid until ${new Date(body.expiresAt).toLocaleString()}): ${body.token}`, "");
  } catch (error) {
    setStatus(inviteStatus, error.message ?? "Could not create the invitation.", "error");
  }
});

logoutButton.addEventListener("click", () => clearAuth());

renderAuthState();

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

mermaid.initialize({ startOnLoad: false });

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
  dropzoneLabel.textContent = file ? file.name : "Drag a file here or click to choose";
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
// requiring "save to disk, then click 📎, then pick the file" — the clipboard item
// becomes a File exactly like one chosen through the file picker, so everything
// downstream (the preview chip, the multipart submit below) is unaffected either way.
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
    setStatus(askStatus, `Image pasted from clipboard: ${file.name || file.type}`, "success");
  }
});

uploadForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const file = fileInput.files[0];
  if (!file) {
    return;
  }

  uploadButton.disabled = true;
  setStatus(uploadStatus, `Uploading and indexing "${file.name}"...`);

  const formData = new FormData();
  formData.append("file", file);

  try {
    const response = await fetch(`${INGESTION_BASE}/api/v1/documents`, {
      method: "POST",
      headers: authHeader(),
      body: formData,
    });

    if (response.status === 401) {
      clearAuth("Your session expired. Please log in again.");
      return;
    }

    const body = await response.json();

    if (!response.ok) {
      throw new Error(body.message ?? `Upload failed with status ${response.status}`);
    }

    setStatus(uploadStatus, `Indexed "${body.source}" into ${body.chunkCount} chunk(s).`, "success");
    addHistoryEntry(body);
    uploadForm.reset();
    setFile(null);
  } catch (error) {
    setStatus(uploadStatus, error.message ?? "Upload failed.", "error");
    uploadButton.disabled = false;
  }
});

function addHistoryEntry({ source, chunkCount, pageCount }) {
  const item = document.createElement("li");
  item.innerHTML = `
    <span class="source">${escapeHtml(source)}</span>
    <span class="meta">${pageCount} page(s) &middot; ${chunkCount} chunk(s)</span>
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

async function performAsk({ question, model, attachedImage, useFallback }) {
  askButton.disabled = true;
  answerCard.hidden = true;
  diagramCard.hidden = true;
  fallbackConfirmCard.hidden = true;
  setStatus(askStatus, attachedImage
    ? "Describing the attached image and generating a response..."
    : useFallback
      ? "Asking a public AI model (not grounded in your documents)..."
      : "Retrieving context and generating a response...");

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
      clearAuth("Your session expired. Please log in again.");
      return;
    }

    const body = await response.json();

    if (!response.ok) {
      throw new Error(body.message ?? `Request failed with status ${response.status}`);
    }

    if (body.type === "diagram") {
      if (!body.mermaid || body.mermaid.includes("Dados insuficientes")) {
        setStatus(askStatus, "No architecture, process or flow was found in the ingested content to diagram.", "error");
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
    setStatus(askStatus, error.message ?? "Something went wrong.", "error");
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
    item.textContent = "No sources were retrieved for this question.";
    listElement.appendChild(item);
    return;
  }

  citations.forEach((citation) => {
    const item = document.createElement("li");
    const score = typeof citation.score === "number" ? citation.score.toFixed(3) : "n/a";
    item.innerHTML = `
      <div class="citation-head">
        <span>${escapeHtml(citation.source)} &middot; chunk ${citation.chunkIndex}</span>
        <span>score ${score}</span>
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
    setStatus(askStatus, "The generated diagram could not be rendered.", "error");
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
  setStatus(conversationStatus, "Starting a new conversation...");
  try {
    const response = await fetch(`${CHAT_BASE}/api/v1/conversations`, {
      method: "POST",
      headers: authHeader(),
    });

    if (response.status === 401) {
      clearAuth("Your session expired. Please log in again.");
      return;
    }
    if (!response.ok) {
      throw new Error(`Could not start a conversation (status ${response.status}).`);
    }

    const body = await response.json();
    currentConversationId = body.conversationId;
    conversationIdLabel.textContent = `Conversation ${currentConversationId}`;
    conversationIdLabel.hidden = false;
    conversationMessages.innerHTML = "";
    conversationThread.hidden = false;
    setStatus(conversationStatus, "");
    conversationInput.focus();
  } catch (error) {
    setStatus(conversationStatus, error.message ?? "Could not start a conversation.", "error");
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
  setStatus(conversationStatus, "Thinking...");

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
      clearAuth("Your session expired. Please log in again.");
      return;
    }

    const body = await response.json();
    if (!response.ok) {
      throw new Error(body.message ?? `Request failed with status ${response.status}`);
    }

    appendConversationMessage("assistant", body.answer, body.citations);
    setStatus(conversationStatus, "");
  } catch (error) {
    setStatus(conversationStatus, error.message ?? "Something went wrong.", "error");
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
    item.innerHTML += `<div class="conversation-sources">Sources: ${sources}</div>`;
  }
  conversationMessages.appendChild(item);
  conversationMessages.scrollTop = conversationMessages.scrollHeight;
}

function escapeHtml(value) {
  const div = document.createElement("div");
  div.textContent = value ?? "";
  return div.innerHTML;
}
