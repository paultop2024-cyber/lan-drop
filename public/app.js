const state = {
  status: null,
  accessCode: localStorage.getItem("lanDropCode") || "",
  files: [],
  activeReceives: [],
  phoneFiles: [],
  phoneSelectedFiles: [],
};

const els = {
  authBadge: document.getElementById("authBadge"),
  baseUrlBtn: document.getElementById("baseUrlBtn"),
  openUploadDirBtn: document.getElementById("openUploadDirBtn"),
  accessCode: document.getElementById("accessCode"),
  uploadDir: document.getElementById("uploadDir"),
  recentCount: document.getElementById("recentCount"),
  receiveStatus: document.getElementById("receiveStatus"),
  filesList: document.getElementById("filesList"),
  phoneFileCount: document.getElementById("phoneFileCount"),
  phoneFilesList: document.getElementById("phoneFilesList"),
  phonePickedFiles: document.getElementById("phonePickedFiles"),
  phoneFileInput: document.getElementById("phoneFileInput"),
  phoneDropzone: document.getElementById("phoneDropzone"),
  shareToPhoneBtn: document.getElementById("shareToPhoneBtn"),
  phoneShareResult: document.getElementById("phoneShareResult"),
  authDialog: document.getElementById("authDialog"),
  authForm: document.getElementById("authForm"),
  codeInput: document.getElementById("codeInput"),
  authError: document.getElementById("authError"),
  refreshBtn: document.getElementById("refreshBtn"),
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
  els.baseUrlBtn.textContent = status.baseUrl;
  els.accessCode.textContent = status.authRequired ? status.accessCodeHint : "未开启";
  els.uploadDir.textContent = status.uploadsDir;
  els.authBadge.textContent = status.authRequired ? "在线，需访问码" : "在线，免访问码";
}

function renderPhonePickedFiles() {
  if (!state.phoneSelectedFiles.length) {
    els.phonePickedFiles.className = "picked-files empty";
    els.phonePickedFiles.textContent = "还没有选择要发给手机的文件";
    els.shareToPhoneBtn.disabled = true;
    return;
  }
  els.phonePickedFiles.className = "picked-files";
  els.phonePickedFiles.innerHTML = state.phoneSelectedFiles
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
  els.shareToPhoneBtn.disabled = false;
}

function renderFiles() {
  els.recentCount.textContent = String(state.files.length);
  renderReceiveStatus();
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

function renderReceiveStatus() {
  const active = state.activeReceives[0];
  if (active) {
    const detail = active.percent === null
      ? "正在接收文件，请稍等…"
      : `${active.percent}% · ${active.receivedLabel || "0 B"} / ${active.totalLabel || ""}`;
    els.receiveStatus.className = "receive-summary receiving";
    els.receiveStatus.innerHTML = `
      <span class="live-dot small" aria-hidden="true"></span>
      <div>
        <strong>正在接收：${escapeHtml(active.filename || "手机发送的文件")}</strong>
        <p>${escapeHtml(detail)}</p>
      </div>
    `;
    return;
  }

  const latest = state.files[0];
  if (latest) {
    els.receiveStatus.className = "receive-summary received";
    els.receiveStatus.innerHTML = `
      <span class="status-mark" aria-hidden="true">✓</span>
      <div>
        <strong>已接收 ${state.files.length} 个文件</strong>
        <p>最近收到：${escapeHtml(latest.name)} · ${escapeHtml(formatTime(latest.modifiedAt))}</p>
      </div>
    `;
    return;
  }

  els.receiveStatus.className = "receive-summary idle";
  els.receiveStatus.innerHTML = `
    <span class="live-dot small" aria-hidden="true"></span>
    <div>
      <strong>等待手机发送</strong>
      <p>手机端点“直接发送到 Mac”，收到后会自动出现在列表里。</p>
    </div>
  `;
}

function renderPhoneFiles() {
  els.phoneFileCount.textContent = String(state.phoneFiles.length);
  if (!state.phoneFiles.length) {
    els.phoneFilesList.className = "files-list empty";
    els.phoneFilesList.textContent = "还没有可下载文件";
    return;
  }
  els.phoneFilesList.className = "files-list";
  els.phoneFilesList.innerHTML = state.phoneFiles
    .map(
      (file) => `
        <div class="file-row">
          <div class="file-title">${escapeHtml(file.name)}</div>
          <div class="file-meta">
            <span>${escapeHtml(file.sizeLabel)}</span>
            <span>${escapeHtml(formatTime(file.modifiedAt))}</span>
          </div>
          <div class="file-actions">
            <a href="${escapeHtml(phoneFileDownloadUrl(file))}" download>下载到手机</a>
            <button class="link-button" type="button" data-delete-phone-file="${escapeHtml(file.name)}">移除</button>
          </div>
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

function phoneFileDownloadUrl(file) {
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
    state.activeReceives = data.activeReceives || [];
    renderFiles();
  } catch (error) {
    if (error.message === "AUTH") {
      await requestCode();
      return loadFiles();
    }
    throw error;
  }
}

async function loadPhoneFiles() {
  try {
    const data = await api("/api/phone-files");
    state.phoneFiles = data.files;
    renderPhoneFiles();
  } catch (error) {
    if (error.message === "AUTH") {
      await requestCode();
      return loadPhoneFiles();
    }
    throw error;
  }
}

async function openUploadFolder() {
  try {
    await api("/api/open-upload-folder", { method: "POST" });
    showPhoneShareResult("已在 Mac 上打开保存文件夹。", true);
  } catch (error) {
    if (error.message === "AUTH") {
      await requestCode();
      return openUploadFolder();
    }
    showPhoneShareResult("打开保存文件夹失败，请确认是在 Mac 端页面操作。", false);
  }
}

function showPhoneShareResult(html, ok = true) {
  els.phoneShareResult.hidden = false;
  els.phoneShareResult.style.color = ok ? "var(--good)" : "#dc2626";
  els.phoneShareResult.innerHTML = html;
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

async function shareFilesToPhone() {
  if (!state.phoneSelectedFiles.length) return;
  if (state.status?.authRequired && !state.accessCode) {
    await requestCode();
  }
  const formData = new FormData();
  for (const file of state.phoneSelectedFiles) {
    formData.append("files", file);
  }

  els.shareToPhoneBtn.disabled = true;
  showPhoneShareResult("正在准备给手机下载…", true);
  try {
    const data = await api("/api/phone-files", {
      method: "POST",
      body: formData,
    });
    showPhoneShareResult(`已放入 ${data.count} 个文件。手机端点“查看 Mac 发来的文件”，即可直接接收。`, true);
    state.phoneSelectedFiles = [];
    els.phoneFileInput.value = "";
    renderPhonePickedFiles();
    await loadPhoneFiles();
  } catch (error) {
    if (error.message === "AUTH") {
      await requestCode();
      els.shareToPhoneBtn.disabled = false;
      return shareFilesToPhone();
    }
    showPhoneShareResult("准备下载列表失败，请再试一次。", false);
    els.shareToPhoneBtn.disabled = false;
  }
}

async function deletePhoneFile(filename) {
  try {
    await api(`/api/phone-files/${encodeURIComponent(filename)}`, { method: "DELETE" });
    await loadPhoneFiles();
  } catch (error) {
    if (error.message === "AUTH") {
      await requestCode();
      return deletePhoneFile(filename);
    }
    showPhoneShareResult("移除失败，请刷新后再试。", false);
  }
}

function bindPhoneDropzone() {
  ["dragenter", "dragover"].forEach((eventName) => {
    els.phoneDropzone.addEventListener(eventName, (event) => {
      event.preventDefault();
      els.phoneDropzone.classList.add("dragover");
    });
  });
  ["dragleave", "drop"].forEach((eventName) => {
    els.phoneDropzone.addEventListener(eventName, (event) => {
      event.preventDefault();
      els.phoneDropzone.classList.remove("dragover");
    });
  });
  els.phoneDropzone.addEventListener("drop", (event) => {
    const files = Array.from(event.dataTransfer.files || []);
    state.phoneSelectedFiles = files;
    renderPhonePickedFiles();
  });
}

function startAutoRefresh() {
  window.setInterval(async () => {
    try {
      await loadFiles();
      await loadPhoneFiles();
    } catch (error) {
      console.warn("Auto refresh failed", error);
    }
  }, 3000);
}

async function init() {
  bindPhoneDropzone();
  els.phoneFileInput.addEventListener("change", () => {
    state.phoneSelectedFiles = Array.from(els.phoneFileInput.files || []);
    renderPhonePickedFiles();
  });
  els.shareToPhoneBtn.addEventListener("click", shareFilesToPhone);
  els.phoneFilesList.addEventListener("click", (event) => {
    const button = event.target.closest("[data-delete-phone-file]");
    if (!button) return;
    deletePhoneFile(button.dataset.deletePhoneFile);
  });
  els.refreshBtn.addEventListener("click", async () => {
    await loadStatus();
    await loadFiles();
    await loadPhoneFiles();
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
  await loadPhoneFiles();
  renderPhonePickedFiles();
  startAutoRefresh();
}

init().catch((error) => {
  console.error(error);
  showPhoneShareResult("页面初始化失败了，请刷新试试。", false);
});
