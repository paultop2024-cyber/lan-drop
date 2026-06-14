const state = {
  status: null,
  accessCode: localStorage.getItem("lanDropCode") || "",
  files: [],
  selectedFiles: [],
};

const els = {
  qrImage: document.getElementById("qrImage"),
  authBadge: document.getElementById("authBadge"),
  baseUrlBtn: document.getElementById("baseUrlBtn"),
  openUploadDirBtn: document.getElementById("openUploadDirBtn"),
  accessCode: document.getElementById("accessCode"),
  uploadDir: document.getElementById("uploadDir"),
  recentCount: document.getElementById("recentCount"),
  filesList: document.getElementById("filesList"),
  pickedFiles: document.getElementById("pickedFiles"),
  fileInput: document.getElementById("fileInput"),
  uploadBtn: document.getElementById("uploadBtn"),
  progressCard: document.getElementById("progressCard"),
  progressLabel: document.getElementById("progressLabel"),
  progressValue: document.getElementById("progressValue"),
  progressFill: document.getElementById("progressFill"),
  uploadResult: document.getElementById("uploadResult"),
  authDialog: document.getElementById("authDialog"),
  authForm: document.getElementById("authForm"),
  codeInput: document.getElementById("codeInput"),
  authError: document.getElementById("authError"),
  refreshBtn: document.getElementById("refreshBtn"),
  dropzone: document.getElementById("dropzone"),
};

async function api(path, options = {}) {
  const headers = new Headers(options.headers || {});
  if (state.accessCode) {
    headers.set("x-access-code", state.accessCode);
  }
  const response = await fetch(path, { ...options, headers });
  if (response.status === 401) {
    throw new Error("AUTH");
  }
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || "REQUEST_FAILED");
  }
  return response.json();
}

function setAccessCode(code) {
  state.accessCode = code.trim();
  localStorage.setItem("lanDropCode", state.accessCode);
}

function formatTime(iso) {
  const d = new Date(iso);
  return d.toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function renderStatus() {
  const status = state.status;
  if (!status) return;
  els.qrImage.src = status.qrDataUrl;
  els.baseUrlBtn.textContent = status.baseUrl;
  els.accessCode.textContent = status.authRequired ? status.accessCodeHint : "未开启";
  els.uploadDir.textContent = status.uploadsDir;
  els.authBadge.textContent = status.authRequired ? "需访问码" : "免访问码";
}

function renderPickedFiles() {
  if (!state.selectedFiles.length) {
    els.pickedFiles.className = "picked-files empty";
    els.pickedFiles.textContent = "还没有选文件";
    els.uploadBtn.disabled = true;
    return;
  }
  els.pickedFiles.className = "picked-files";
  els.pickedFiles.innerHTML = state.selectedFiles
    .map(
      (file) => `
        <div class="picked-file">
          <div class="file-title">${escapeHtml(file.name)}</div>
          <div class="file-meta">
            <span>${(file.size / 1024 / 1024).toFixed(1)} MB</span>
            <span>${escapeHtml(file.type || "未知类型")}</span>
          </div>
        </div>
      `
    )
    .join("");
  els.uploadBtn.disabled = false;
}

function renderFiles() {
  els.recentCount.textContent = String(state.files.length);
  if (!state.files.length) {
    els.filesList.className = "files-list empty";
    els.filesList.textContent = "还没有收到文件";
    return;
  }
  els.filesList.className = "files-list";
  els.filesList.innerHTML = state.files
    .map(
      (file) => `
        <div class="file-row">
          <div class="file-title">${escapeHtml(file.name)}</div>
          <div class="file-meta">
            <span>${escapeHtml(file.sizeLabel)}</span>
            <span>${escapeHtml(file.day)}</span>
            <span>${escapeHtml(formatTime(file.modifiedAt))}</span>
          </div>
          <a href="${escapeHtml(fileOpenUrl(file))}" target="_blank" rel="noreferrer">打开文件</a>
        </div>
      `
    )
    .join("");
}

function escapeHtml(text) {
  return String(text)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function fileOpenUrl(file) {
  const url = new URL(file.url, window.location.origin);
  if (state.status?.authRequired && state.accessCode) {
    url.searchParams.set("code", state.accessCode);
  }
  return `${url.pathname}${url.search}`;
}

async function loadStatus() {
  state.status = await api("/api/status");
  renderStatus();
}

async function loadFiles() {
  try {
    const data = await api("/api/files");
    state.files = data.files;
    renderFiles();
  } catch (error) {
    if (error.message === "AUTH") {
      await requestCode();
      return loadFiles();
    }
    throw error;
  }
}

async function openUploadFolder() {
  try {
    await api("/api/open-upload-folder", { method: "POST" });
    showResult("已在 Mac 上打开保存文件夹。", true);
  } catch (error) {
    if (error.message === "AUTH") {
      await requestCode();
      return openUploadFolder();
    }
    showResult("打开保存文件夹失败，请确认是在 Mac 端页面操作。", false);
  }
}

function showProgress(percent, label) {
  els.progressCard.hidden = false;
  els.progressLabel.textContent = label;
  els.progressValue.textContent = `${percent}%`;
  els.progressFill.style.width = `${percent}%`;
}

function hideProgress() {
  els.progressCard.hidden = true;
}

function showResult(html, ok = true) {
  els.uploadResult.hidden = false;
  els.uploadResult.style.color = ok ? "var(--good)" : "#dc2626";
  els.uploadResult.innerHTML = html;
}

async function requestCode() {
  els.authError.hidden = true;
  els.codeInput.value = state.accessCode;
  if (!els.authDialog.open) {
    els.authDialog.showModal();
  }
  els.codeInput.focus();
  return new Promise((resolve) => {
    const handler = async (event) => {
      event.preventDefault();
      const code = els.codeInput.value.trim();
      try {
        await fetch("/api/auth", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ code }),
        }).then((r) => {
          if (!r.ok) throw new Error("INVALID");
          return r.json();
        });
        setAccessCode(code);
        els.authDialog.close();
        els.authForm.removeEventListener("submit", handler);
        resolve();
      } catch {
        els.authError.hidden = false;
      }
    };
    els.authForm.addEventListener("submit", handler);
  });
}

async function uploadFiles() {
  if (!state.selectedFiles.length) return;
  if (state.status?.authRequired && !state.accessCode) {
    await requestCode();
  }
  const formData = new FormData();
  for (const file of state.selectedFiles) {
    formData.append("files", file);
  }

  const xhr = new XMLHttpRequest();
  xhr.open("POST", "/api/upload");
  if (state.accessCode) {
    xhr.setRequestHeader("x-access-code", state.accessCode);
  }

  showProgress(0, "准备上传");
  showResult("", true);
  els.uploadResult.hidden = true;
  els.uploadBtn.disabled = true;

  xhr.upload.onprogress = (event) => {
    if (!event.lengthComputable) return;
    const percent = Math.max(1, Math.round((event.loaded / event.total) * 100));
    showProgress(percent, "上传中");
  };

  xhr.onload = async () => {
    if (xhr.status === 401) {
      hideProgress();
      els.uploadBtn.disabled = false;
      await requestCode();
      return uploadFiles();
    }
    if (xhr.status < 200 || xhr.status >= 300) {
      hideProgress();
      els.uploadBtn.disabled = false;
      showResult("上传失败了，请再试一次。", false);
      return;
    }
    showProgress(100, "上传完成");
    const result = JSON.parse(xhr.responseText);
    showResult(
      `收到 ${result.count} 个文件，已经保存到：<br><strong>${escapeHtml(result.savedTo)}</strong>`,
      true
    );
    state.selectedFiles = [];
    els.fileInput.value = "";
    renderPickedFiles();
    await loadFiles();
    setTimeout(hideProgress, 800);
  };

  xhr.onerror = () => {
    hideProgress();
    els.uploadBtn.disabled = false;
    showResult("网络中断了，请确认手机和 Mac 还在同一个 Wi‑Fi。", false);
  };

  xhr.send(formData);
}

function bindDropzone() {
  ["dragenter", "dragover"].forEach((eventName) => {
    els.dropzone.addEventListener(eventName, (event) => {
      event.preventDefault();
      els.dropzone.classList.add("dragover");
    });
  });
  ["dragleave", "drop"].forEach((eventName) => {
    els.dropzone.addEventListener(eventName, (event) => {
      event.preventDefault();
      els.dropzone.classList.remove("dragover");
    });
  });
  els.dropzone.addEventListener("drop", (event) => {
    const files = Array.from(event.dataTransfer.files || []);
    state.selectedFiles = files;
    renderPickedFiles();
  });
}

async function init() {
  bindDropzone();
  els.fileInput.addEventListener("change", () => {
    state.selectedFiles = Array.from(els.fileInput.files || []);
    renderPickedFiles();
  });
  els.uploadBtn.addEventListener("click", uploadFiles);
  els.refreshBtn.addEventListener("click", async () => {
    await loadStatus();
    await loadFiles();
  });
  els.baseUrlBtn.addEventListener("click", async () => {
    if (navigator.clipboard && state.status?.baseUrl) {
      await navigator.clipboard.writeText(state.status.baseUrl);
      els.baseUrlBtn.textContent = "已复制";
      setTimeout(() => {
        els.baseUrlBtn.textContent = state.status.baseUrl;
      }, 1200);
    }
  });
  els.openUploadDirBtn.addEventListener("click", openUploadFolder);

  await loadStatus();
  if (state.status.authRequired && !state.accessCode) {
    await requestCode();
  }
  await loadFiles();
  renderPickedFiles();
}

init().catch((error) => {
  console.error(error);
  showResult("页面初始化失败了，请刷新试试。", false);
});
