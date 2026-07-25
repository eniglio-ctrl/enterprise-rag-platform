const INGESTION_BASE = window.RAG_PLATFORM_CONFIG?.ingestionBaseUrl ?? "http://localhost:8081";
const RAG_BASE = window.RAG_PLATFORM_CONFIG?.ragBaseUrl ?? "http://localhost:8082";

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
const answerCard = document.getElementById("answer-card");
const answerText = document.getElementById("answer-text");
const citationsList = document.getElementById("citations-list");

const diagramCard = document.getElementById("diagram-card");
const diagramOutput = document.getElementById("diagram-output");
const diagramCitations = document.getElementById("diagram-citations");
let diagramCounter = 0;

mermaid.initialize({ startOnLoad: false });

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

askForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const question = questionInput.value.trim();
  if (!question) {
    return;
  }

  askButton.disabled = true;
  answerCard.hidden = true;
  diagramCard.hidden = true;
  setStatus(askStatus, "Retrieving context and generating a response...");

  try {
    const response = await fetch(`${RAG_BASE}/api/v1/ask`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ question }),
    });

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
