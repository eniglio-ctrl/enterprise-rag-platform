const INGESTION_BASE = window.RAG_PLATFORM_CONFIG?.ingestionBaseUrl ?? "http://localhost:8081";
const RAG_BASE = window.RAG_PLATFORM_CONFIG?.ragBaseUrl ?? "http://localhost:8082";
const AUTH_BASE = window.RAG_PLATFORM_CONFIG?.authBaseUrl ?? "http://localhost:8084";
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

function setAuth({ token, expiresInSeconds, tenantId, userId, email }) {
  localStorage.setItem(
    TOKEN_STORAGE_KEY,
    JSON.stringify({ token, expiresAt: Date.now() + expiresInSeconds * 1000, tenantId, userId, email })
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
const citationsList = document.getElementById("citations-list");

const imageInput = document.getElementById("image-input");
const imageAttachmentPreview = document.getElementById("image-attachment-preview");
const imageAttachmentName = document.getElementById("image-attachment-name");
const imageAttachmentClear = document.getElementById("image-attachment-clear");

const diagramCard = document.getElementById("diagram-card");
const diagramOutput = document.getElementById("diagram-output");
const diagramCitations = document.getElementById("diagram-citations");
let diagramCounter = 0;

mermaid.initialize({ startOnLoad: false });

document.getElementById("config-summary").textContent = DEMO_MODE
  ? `rag-service: ${RAG_BASE} · public demo (ADR 0020)`
  : `ingestion-service: ${INGESTION_BASE} · rag-service: ${RAG_BASE} · auth-service: ${AUTH_BASE}`;

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

  const attachedImage = imageInput.files[0];

  askButton.disabled = true;
  answerCard.hidden = true;
  diagramCard.hidden = true;
  setStatus(askStatus, attachedImage
    ? "Describing the attached image and generating a response..."
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
      if (modelSelect.value) {
        formData.append("model", modelSelect.value);
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
        body: JSON.stringify({ question, model: modelSelect.value || undefined }),
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
    } else {
      setStatus(askStatus, "");
      renderAnswer(body);
    }

    setAttachedImage(null);
  } catch (error) {
    setStatus(askStatus, error.message ?? "Something went wrong.", "error");
  } finally {
    askButton.disabled = false;
  }
});

function renderAnswer({ answer, citations }) {
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

function escapeHtml(value) {
  const div = document.createElement("div");
  div.textContent = value ?? "";
  return div.innerHTML;
}
