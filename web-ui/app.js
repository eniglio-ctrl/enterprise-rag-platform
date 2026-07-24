const INGESTION_BASE = window.RAG_PLATFORM_CONFIG?.ingestionBaseUrl ?? "http://localhost:8081";
const RAG_BASE = window.RAG_PLATFORM_CONFIG?.ragBaseUrl ?? "http://localhost:8082";

const dropzone = document.getElementById("dropzone");
const dropzoneLabel = document.getElementById("dropzone-label");
const fileInput = document.getElementById("file-input");
const uploadForm = document.getElementById("upload-form");
const uploadButton = document.getElementById("upload-button");
const uploadStatus = document.getElementById("upload-status");
const uploadHistory = document.getElementById("upload-history");

const chatForm = document.getElementById("chat-form");
const chatButton = document.getElementById("chat-button");
const chatStatus = document.getElementById("chat-status");
const questionInput = document.getElementById("question-input");
const answerCard = document.getElementById("answer-card");
const answerText = document.getElementById("answer-text");
const citationsList = document.getElementById("citations-list");

document.getElementById("config-summary").textContent =
  `ingestion-service: ${INGESTION_BASE} · rag-service: ${RAG_BASE}`;

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
      body: formData,
    });

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

chatForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const question = questionInput.value.trim();
  if (!question) {
    return;
  }

  chatButton.disabled = true;
  answerCard.hidden = true;
  setStatus(chatStatus, "Retrieving context and generating an answer...");

  try {
    const response = await fetch(`${RAG_BASE}/api/v1/chat`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ question }),
    });

    const body = await response.json();

    if (!response.ok) {
      throw new Error(body.message ?? `Request failed with status ${response.status}`);
    }

    setStatus(chatStatus, "");
    renderAnswer(body);
  } catch (error) {
    setStatus(chatStatus, error.message ?? "Something went wrong.", "error");
  } finally {
    chatButton.disabled = false;
  }
});

function renderAnswer({ answer, citations }) {
  answerText.textContent = answer;
  citationsList.innerHTML = "";

  if (!citations || citations.length === 0) {
    const item = document.createElement("li");
    item.textContent = "No sources were retrieved for this question.";
    citationsList.appendChild(item);
  } else {
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
      citationsList.appendChild(item);
    });
  }

  answerCard.hidden = false;
}

function escapeHtml(value) {
  const div = document.createElement("div");
  div.textContent = value ?? "";
  return div.innerHTML;
}
