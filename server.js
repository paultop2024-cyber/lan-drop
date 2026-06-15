const express = require("express");
const multer = require("multer");
const QRCode = require("qrcode");
const fs = require("fs");
const fsp = require("fs/promises");
const dgram = require("dgram");
const os = require("os");
const path = require("path");
const crypto = require("crypto");
const { execFile } = require("child_process");

const ROOT = __dirname;
const PUBLIC_DIR = path.join(ROOT, "public");
const DISCOVERY_PORT = 50000;
const activePhoneTransfers = new Map();

function ensureDir(dir) {
  fs.mkdirSync(dir, { recursive: true });
}

function getLocalIp() {
  const nets = os.networkInterfaces();
  const candidates = [];
  for (const entries of Object.values(nets)) {
    for (const entry of entries || []) {
      if (entry.family === "IPv4" && !entry.internal) {
        candidates.push(entry.address);
      }
    }
  }
  return candidates[0] || "127.0.0.1";
}

function bytesLabel(bytes) {
  if (!Number.isFinite(bytes)) return "";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let value = bytes;
  let idx = 0;
  while (value >= 1024 && idx < units.length - 1) {
    value /= 1024;
    idx += 1;
  }
  const shown = value >= 100 || idx === 0 ? value.toFixed(0) : value.toFixed(1);
  return `${shown} ${units[idx]}`;
}

function safeName(name) {
  const parsed = path.parse(name || "file");
  const base = parsed.name.replace(/[^a-zA-Z0-9._-]+/g, "-").replace(/-+/g, "-").replace(/^-|-$/g, "") || "file";
  const ext = (parsed.ext || "").replace(/[^a-zA-Z0-9.]/g, "");
  return `${base}${ext}`;
}

function timestampCompact(date = new Date()) {
  return date.toISOString().replace(/[-:.TZ]/g, "").slice(0, 14);
}

function todayFolder() {
  const now = new Date();
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, "0");
  const d = String(now.getDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

function authOk(req, accessCode, accessDisabled) {
  if (accessDisabled) return true;
  const code = req.header("x-access-code") || req.query.code || req.body?.code;
  return code === accessCode;
}

async function listRecentFiles(uploadRoot, limit = 50) {
  ensureDir(uploadRoot);
  const dayDirs = await fsp.readdir(uploadRoot, { withFileTypes: true });
  const files = [];

  for (const dirent of dayDirs) {
    if (!dirent.isDirectory()) continue;
    if (dirent.name.startsWith(".")) continue;
    const dayDir = path.join(uploadRoot, dirent.name);
    const entries = await fsp.readdir(dayDir, { withFileTypes: true });
    for (const entry of entries) {
      if (!entry.isFile()) continue;
      const abs = path.join(dayDir, entry.name);
      const stat = await fsp.stat(abs);
      files.push({
        name: entry.name,
        day: dirent.name,
        size: stat.size,
        sizeLabel: bytesLabel(stat.size),
        modifiedAt: stat.mtime.toISOString(),
        url: `/download/${encodeURIComponent(dirent.name)}/${encodeURIComponent(entry.name)}`,
      });
    }
  }

  files.sort((a, b) => new Date(b.modifiedAt) - new Date(a.modifiedAt));
  return files.slice(0, limit);
}

function historyPath(uploadRoot) {
  return path.join(uploadRoot, "transfer-history.json");
}

function resolveInsideUploadRoot(uploadRoot, ...segments) {
  const resolved = path.resolve(uploadRoot, ...segments);
  const root = path.resolve(uploadRoot);
  if (resolved !== root && !resolved.startsWith(`${root}${path.sep}`)) {
    return null;
  }
  return resolved;
}

function uploadSessionRoot(uploadRoot) {
  return path.join(uploadRoot, ".lan-drop-sessions");
}

function phoneShareRoot(uploadRoot) {
  return path.join(uploadRoot, ".lan-drop-to-phone");
}

function trackPhoneShareTransfer(req, res, next) {
  const transferId = crypto.randomUUID();
  const totalBytes = Number(req.headers["content-length"] || 0);
  activePhoneTransfers.set(transferId, {
    id: transferId,
    sender: os.hostname() || "Mac",
    totalBytes: Number.isFinite(totalBytes) ? totalBytes : 0,
    totalLabel: Number.isFinite(totalBytes) && totalBytes > 0 ? bytesLabel(totalBytes) : "",
    startedAt: new Date().toISOString(),
  });
  res.on("finish", () => activePhoneTransfers.delete(transferId));
  res.on("close", () => activePhoneTransfers.delete(transferId));
  next();
}

function normalizeSessionId(value) {
  return String(value || "").replace(/[^a-zA-Z0-9_-]/g, "").slice(0, 64);
}

function makeSessionId({ filename, totalSize, lastModified }) {
  return crypto
    .createHash("sha256")
    .update(`${filename || "file"}:${totalSize || 0}:${lastModified || ""}`)
    .digest("hex")
    .slice(0, 32);
}

function sessionPaths(uploadRoot, sessionId) {
  const root = uploadSessionRoot(uploadRoot);
  return {
    root,
    partPath: path.join(root, `${sessionId}.part`),
    metaPath: path.join(root, `${sessionId}.json`),
  };
}

async function fileSizeIfExists(filePath) {
  try {
    return (await fsp.stat(filePath)).size;
  } catch {
    return 0;
  }
}

async function readJsonIfExists(filePath) {
  try {
    return JSON.parse(await fsp.readFile(filePath, "utf8"));
  } catch {
    return null;
  }
}

async function readHistory(uploadRoot, limit = 100) {
  try {
    const payload = await fsp.readFile(historyPath(uploadRoot), "utf8");
    const history = JSON.parse(payload);
    return Array.isArray(history) ? history.slice(0, limit) : [];
  } catch {
    return [];
  }
}

async function appendHistory(uploadRoot, entries) {
  const oldHistory = await readHistory(uploadRoot, 500);
  const nextHistory = [...entries, ...oldHistory].slice(0, 500);
  await fsp.writeFile(historyPath(uploadRoot), JSON.stringify(nextHistory, null, 2));
}

async function listPhoneShareFiles(uploadRoot, limit = 100) {
  const shareRoot = phoneShareRoot(uploadRoot);
  ensureDir(shareRoot);
  const entries = await fsp.readdir(shareRoot, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    if (!entry.isFile()) continue;
    const abs = path.join(shareRoot, entry.name);
    const stat = await fsp.stat(abs);
    files.push({
      name: entry.name,
      size: stat.size,
      sizeLabel: bytesLabel(stat.size),
      modifiedAt: stat.mtime.toISOString(),
      url: `/phone-download/${encodeURIComponent(entry.name)}`,
    });
  }
  files.sort((a, b) => new Date(b.modifiedAt) - new Date(a.modifiedAt));
  return files.slice(0, limit);
}

function buildApp(config) {
  const app = express();
  const uploadRoot = config.uploadRoot;
  const accessCode = config.accessCode;
  const accessDisabled = config.accessDisabled;
  const maxFileLabel = config.maxFileLabel;

  ensureDir(uploadRoot);

  const storage = multer.diskStorage({
    destination(req, file, cb) {
      const dest = path.join(uploadRoot, todayFolder());
      ensureDir(dest);
      cb(null, dest);
    },
    filename(req, file, cb) {
      const ext = path.extname(file.originalname || "");
      const stamp = timestampCompact();
      const rand = crypto.randomBytes(3).toString("hex");
      cb(null, `${stamp}-${rand}-${safeName(file.originalname || `upload${ext}`)}`);
    },
  });

  const upload = multer({ storage });
  const phoneShareStorage = multer.diskStorage({
    destination(req, file, cb) {
      const dest = phoneShareRoot(uploadRoot);
      ensureDir(dest);
      cb(null, dest);
    },
    filename(req, file, cb) {
      const stamp = timestampCompact();
      const rand = crypto.randomBytes(3).toString("hex");
      cb(null, `${stamp}-${rand}-${safeName(file.originalname || "download")}`);
    },
  });
  const phoneShareUpload = multer({ storage: phoneShareStorage });
  const chunkUpload = multer({
    storage: multer.memoryStorage(),
    limits: { fileSize: 12 * 1024 * 1024 },
  });

  app.use(express.json({ limit: "1mb" }));
  app.use(express.urlencoded({ extended: false }));
  app.use(express.static(PUBLIC_DIR));

  app.get("/api/status", async (req, res) => {
    const ip = getLocalIp();
    const baseUrl = `http://${ip}:${config.port}`;
    const qrDataUrl = await QRCode.toDataURL(baseUrl, {
      margin: 1,
      width: 320,
      color: { dark: "#0f172a", light: "#ffffffff" },
    });

    res.json({
      ok: true,
      baseUrl,
      ip,
      port: config.port,
      uploadsDir: uploadRoot,
      authRequired: !accessDisabled,
      accessCodeHint: accessDisabled ? "off" : accessCode,
      maxFileLabel,
      qrDataUrl,
      recentCount: (await listRecentFiles(uploadRoot)).length,
    });
  });

  app.get("/api/files", async (req, res) => {
    if (!authOk(req, accessCode, accessDisabled)) {
      return res.status(401).json({ ok: false, error: "ACCESS_CODE_REQUIRED" });
    }
    res.json({ ok: true, files: await listRecentFiles(uploadRoot) });
  });

  app.get("/files", async (req, res) => {
    if (!authOk(req, accessCode, accessDisabled)) {
      return res.status(401).json({ ok: false, error: "ACCESS_CODE_REQUIRED" });
    }
    const files = await listRecentFiles(uploadRoot);
    res.json({
      files: files.map((file) => ({
        name: file.name,
        size: file.size,
        modified: Math.floor(new Date(file.modifiedAt).getTime() / 1000),
        url: file.url,
      })),
    });
  });

  app.get("/api/history", async (req, res) => {
    if (!authOk(req, accessCode, accessDisabled)) {
      return res.status(401).json({ ok: false, error: "ACCESS_CODE_REQUIRED" });
    }
    res.json({ ok: true, history: await readHistory(uploadRoot) });
  });

  app.get("/history", async (req, res) => {
    if (!authOk(req, accessCode, accessDisabled)) {
      return res.status(401).json({ ok: false, error: "ACCESS_CODE_REQUIRED" });
    }
    res.json({ history: await readHistory(uploadRoot) });
  });

  app.post("/api/auth", (req, res) => {
    if (accessDisabled || req.body.code === accessCode) {
      return res.json({ ok: true });
    }
    res.status(401).json({ ok: false, error: "INVALID_CODE" });
  });

  app.post("/api/open-upload-folder", (req, res) => {
    if (!authOk(req, accessCode, accessDisabled)) {
      return res.status(401).json({ ok: false, error: "ACCESS_CODE_REQUIRED" });
    }
    ensureDir(uploadRoot);
    execFile("open", [uploadRoot], (error) => {
      if (error) {
        return res.status(500).json({ ok: false, error: "OPEN_FOLDER_FAILED" });
      }
      res.json({ ok: true, path: uploadRoot });
    });
  });

  app.get("/api/phone-files", async (req, res) => {
    if (!authOk(req, accessCode, accessDisabled)) {
      return res.status(401).json({ ok: false, error: "ACCESS_CODE_REQUIRED" });
    }
    res.json({
      ok: true,
      files: await listPhoneShareFiles(uploadRoot),
      activeTransfers: Array.from(activePhoneTransfers.values()),
    });
  });

  app.post("/api/phone-files", trackPhoneShareTransfer, phoneShareUpload.array("files"), async (req, res) => {
    if (!authOk(req, accessCode, accessDisabled)) {
      for (const file of req.files || []) {
        await fsp.rm(file.path, { force: true });
      }
      return res.status(401).json({ ok: false, error: "ACCESS_CODE_REQUIRED" });
    }
    const files = (req.files || []).map((file) => ({
      originalName: file.originalname,
      storedName: path.basename(file.filename),
      size: file.size,
      sizeLabel: bytesLabel(file.size),
      url: `/phone-download/${encodeURIComponent(file.filename)}`,
    }));
    await appendHistory(
      uploadRoot,
      files.map((file) => ({
        filename: file.originalName,
        storedName: file.storedName,
        size: file.size,
        direction: "send",
        timestamp: Math.floor(Date.now() / 1000),
        path: path.join(phoneShareRoot(uploadRoot), file.storedName),
      }))
    );
    res.json({
      ok: true,
      count: files.length,
      files,
    });
  });

  app.delete("/api/phone-files/:filename", async (req, res) => {
    if (!authOk(req, accessCode, accessDisabled)) {
      return res.status(401).json({ ok: false, error: "ACCESS_CODE_REQUIRED" });
    }
    const filePath = resolveInsideUploadRoot(uploadRoot, ".lan-drop-to-phone", req.params.filename);
    if (!filePath || !fs.existsSync(filePath)) {
      return res.status(404).json({ ok: false, error: "FILE_NOT_FOUND" });
    }
    await fsp.rm(filePath, { force: true });
    res.json({ ok: true });
  });

  async function handleUpload(req, res, legacyResponse = false) {
    if (!authOk(req, accessCode, accessDisabled)) {
      return res.status(401).json({ ok: false, error: "ACCESS_CODE_REQUIRED" });
    }
    const files = (req.files || []).map((file) => {
      const relDir = path.basename(path.dirname(file.path));
      return {
        originalName: file.originalname,
        storedName: path.basename(file.filename),
        size: file.size,
        sizeLabel: bytesLabel(file.size),
        url: `/download/${encodeURIComponent(relDir)}/${encodeURIComponent(file.filename)}`,
      };
    });
    await appendHistory(
      uploadRoot,
      files.map((file) => ({
        filename: file.originalName,
        storedName: file.storedName,
        size: file.size,
        direction: "receive",
        timestamp: Math.floor(Date.now() / 1000),
        path: path.join(uploadRoot, todayFolder(), file.storedName),
      }))
    );
    if (legacyResponse) {
      const first = files[0] || {};
      return res.json({
        status: "success",
        filename: first.originalName || "",
        size: first.size || 0,
        path: first.storedName ? path.join(uploadRoot, todayFolder(), first.storedName) : path.join(uploadRoot, todayFolder()),
        count: files.length,
        files,
      });
    }
    res.json({
      ok: true,
      savedTo: path.join(uploadRoot, todayFolder()),
      count: files.length,
      files,
    });
  }

  app.post("/api/upload", upload.array("files"), (req, res) => handleUpload(req, res));
  app.post("/upload", upload.any(), (req, res) => {
    if (!req.files?.length && req.file) {
      req.files = [req.file];
    }
    return handleUpload(req, res, true);
  });

  app.post("/api/upload-session", async (req, res) => {
    if (!authOk(req, accessCode, accessDisabled)) {
      return res.status(401).json({ ok: false, error: "ACCESS_CODE_REQUIRED" });
    }

    const filename = String(req.body.filename || "upload.bin");
    const totalSize = Number(req.body.totalSize || 0);
    const lastModified = String(req.body.lastModified || "");
    if (!Number.isFinite(totalSize) || totalSize < 0) {
      return res.status(400).json({ ok: false, error: "INVALID_TOTAL_SIZE" });
    }

    const sessionId = normalizeSessionId(req.body.sessionId) || makeSessionId({ filename, totalSize, lastModified });
    const paths = sessionPaths(uploadRoot, sessionId);
    ensureDir(paths.root);

    let meta = await readJsonIfExists(paths.metaPath);
    if (!meta) {
      const day = todayFolder();
      meta = {
        sessionId,
        originalName: filename,
        totalSize,
        lastModified,
        day,
        storedName: `${timestampCompact()}-${sessionId.slice(0, 8)}-${safeName(filename)}`,
        createdAt: new Date().toISOString(),
      };
      await fsp.writeFile(paths.metaPath, JSON.stringify(meta, null, 2));
    }

    const finalPath = resolveInsideUploadRoot(uploadRoot, meta.day, meta.storedName);
    const complete = Boolean(finalPath && fs.existsSync(finalPath));
    res.json({
      ok: true,
      sessionId,
      offset: complete ? Number(meta.totalSize || totalSize) : await fileSizeIfExists(paths.partPath),
      complete,
      storedName: meta.storedName,
    });
  });

  app.post("/api/upload-chunk", chunkUpload.single("chunk"), async (req, res) => {
    if (!authOk(req, accessCode, accessDisabled)) {
      return res.status(401).json({ ok: false, error: "ACCESS_CODE_REQUIRED" });
    }

    const sessionId = normalizeSessionId(req.body.sessionId);
    const offset = Number(req.body.offset || 0);
    if (!sessionId || !Number.isFinite(offset) || offset < 0 || !req.file?.buffer) {
      return res.status(400).json({ ok: false, error: "INVALID_CHUNK_REQUEST" });
    }

    const paths = sessionPaths(uploadRoot, sessionId);
    const meta = await readJsonIfExists(paths.metaPath);
    if (!meta) {
      return res.status(404).json({ ok: false, error: "UPLOAD_SESSION_NOT_FOUND" });
    }

    const currentOffset = await fileSizeIfExists(paths.partPath);
    if (offset < currentOffset) {
      return res.json({ ok: true, nextOffset: currentOffset, skipped: true });
    }
    if (offset > currentOffset) {
      return res.status(409).json({ ok: false, error: "OFFSET_MISMATCH", nextOffset: currentOffset });
    }

    ensureDir(paths.root);
    await fsp.appendFile(paths.partPath, req.file.buffer);
    const nextOffset = currentOffset + req.file.buffer.length;
    res.json({ ok: true, nextOffset });
  });

  app.post("/api/upload-complete", async (req, res) => {
    if (!authOk(req, accessCode, accessDisabled)) {
      return res.status(401).json({ ok: false, error: "ACCESS_CODE_REQUIRED" });
    }

    const sessionId = normalizeSessionId(req.body.sessionId);
    if (!sessionId) {
      return res.status(400).json({ ok: false, error: "INVALID_UPLOAD_SESSION" });
    }

    const paths = sessionPaths(uploadRoot, sessionId);
    const meta = await readJsonIfExists(paths.metaPath);
    if (!meta) {
      return res.status(404).json({ ok: false, error: "UPLOAD_SESSION_NOT_FOUND" });
    }

    const receivedSize = await fileSizeIfExists(paths.partPath);
    const totalSize = Number(meta.totalSize || 0);
    const finalDir = path.join(uploadRoot, meta.day);
    const finalPath = resolveInsideUploadRoot(uploadRoot, meta.day, meta.storedName);
    if (!finalPath) {
      return res.status(400).json({ ok: false, error: "INVALID_FINAL_PATH" });
    }
    if (fs.existsSync(finalPath)) {
      return res.json({
        ok: true,
        savedTo: finalDir,
        count: 1,
        files: [{ originalName: meta.originalName, storedName: meta.storedName, size: totalSize, url: `/download/${encodeURIComponent(meta.day)}/${encodeURIComponent(meta.storedName)}` }],
      });
    }
    if (receivedSize < totalSize) {
      return res.status(409).json({ ok: false, error: "UPLOAD_INCOMPLETE", nextOffset: receivedSize });
    }

    ensureDir(finalDir);
    await fsp.rename(paths.partPath, finalPath);
    await fsp.rm(paths.metaPath, { force: true });
    await appendHistory(uploadRoot, [
      {
        filename: meta.originalName,
        storedName: meta.storedName,
        size: totalSize,
        direction: "receive",
        timestamp: Math.floor(Date.now() / 1000),
        path: finalPath,
      },
    ]);

    res.json({
      ok: true,
      savedTo: finalDir,
      count: 1,
      files: [{ originalName: meta.originalName, storedName: meta.storedName, size: totalSize, url: `/download/${encodeURIComponent(meta.day)}/${encodeURIComponent(meta.storedName)}` }],
    });
  });

  app.get("/download/:day/:filename", (req, res) => {
    if (!authOk(req, accessCode, accessDisabled)) {
      return res.status(401).json({ ok: false, error: "ACCESS_CODE_REQUIRED" });
    }
    const filePath = resolveInsideUploadRoot(uploadRoot, req.params.day, req.params.filename);
    if (!filePath || !fs.existsSync(filePath)) {
      return res.status(404).json({ ok: false, error: "FILE_NOT_FOUND" });
    }
    res.download(filePath, path.basename(filePath), { dotfiles: "allow" });
  });

  app.get("/download/:filename", async (req, res) => {
    if (!authOk(req, accessCode, accessDisabled)) {
      return res.status(401).json({ ok: false, error: "ACCESS_CODE_REQUIRED" });
    }
    const files = await listRecentFiles(uploadRoot, 500);
    const found = files.find((file) => file.name === req.params.filename);
    if (!found) {
      return res.status(404).json({ ok: false, error: "FILE_NOT_FOUND" });
    }
    const filePath = resolveInsideUploadRoot(uploadRoot, found.day, found.name);
    if (!filePath || !fs.existsSync(filePath)) {
      return res.status(404).json({ ok: false, error: "FILE_NOT_FOUND" });
    }
    res.download(filePath, path.basename(filePath), { dotfiles: "allow" });
  });

  app.get("/phone-download/:filename", (req, res) => {
    if (!authOk(req, accessCode, accessDisabled)) {
      return res.status(401).json({ ok: false, error: "ACCESS_CODE_REQUIRED" });
    }
    const filePath = resolveInsideUploadRoot(uploadRoot, ".lan-drop-to-phone", req.params.filename);
    if (!filePath || !fs.existsSync(filePath)) {
      return res.status(404).json({ ok: false, error: "FILE_NOT_FOUND" });
    }
    res.download(filePath, path.basename(filePath), { dotfiles: "allow" });
  });

  app.get("/api/download-all", async (req, res) => {
    if (!authOk(req, accessCode, accessDisabled)) {
      return res.status(401).json({ ok: false, error: "ACCESS_CODE_REQUIRED" });
    }
    const files = await listRecentFiles(uploadRoot, 200);
    res.json({ ok: true, files });
  });

  return app;
}

function readConfig(overrides = {}) {
  const port = Number(overrides.port ?? process.env.PORT ?? 4318);
  const host = overrides.host || process.env.HOST || "0.0.0.0";
  const uploadRoot = overrides.uploadRoot || process.env.LAN_DROP_DIR || path.join(os.homedir(), "Downloads", "LANDrop");
  const accessCode = String(overrides.accessCode ?? process.env.LAN_DROP_CODE ?? Math.floor(100000 + Math.random() * 900000));
  return {
    port,
    host,
    uploadRoot,
    accessCode,
    accessDisabled: accessCode.toLowerCase() === "off",
    maxFileLabel: overrides.maxFileLabel || process.env.LAN_DROP_MAX_LABEL || "No hard cap",
  };
}

function startDiscovery(config) {
  const socket = dgram.createSocket({ type: "udp4", reuseAddr: true });
  const deviceName = os.hostname() || "Mac";

  socket.on("message", (message, remote) => {
    let payload;
    try {
      payload = JSON.parse(message.toString("utf8"));
    } catch {
      return;
    }
    if (payload?.type !== "discover") return;

    const ip = getLocalIp();
    const response = Buffer.from(
      JSON.stringify({
        type: "response",
        device_name: deviceName,
        ip,
        port: config.port,
        baseUrl: `http://${ip}:${config.port}`,
        authRequired: !config.accessDisabled,
      })
    );
    socket.send(response, 0, response.length, remote.port, remote.address);
  });

  socket.on("error", (error) => {
    console.warn(`Discovery disabled: ${error.message}`);
    socket.close();
  });

  socket.bind(DISCOVERY_PORT, () => {
    socket.setBroadcast(true);
    console.log(`Discovery listening on udp://${DISCOVERY_PORT}`);
  });

  return socket;
}

function startServer(overrides = {}) {
  const config = readConfig(overrides);
  const app = buildApp(config);
  let discoverySocket = null;
  const server = app.listen(config.port, config.host, () => {
    const address = server.address();
    if (address && typeof address === "object" && address.port) {
      config.port = address.port;
    }
    const ip = getLocalIp();
    const baseUrl = `http://${ip}:${config.port}`;
    console.log("");
    console.log("LAN Drop is ready.");
    console.log(`Mac local page: http://127.0.0.1:${config.port}`);
    console.log(`Phone LAN URL: ${baseUrl}`);
    console.log(`Uploads folder: ${config.uploadRoot}`);
    console.log(`Access code: ${config.accessDisabled ? "off" : config.accessCode}`);
    discoverySocket = startDiscovery(config);
    console.log("");
  });
  server.on("close", () => {
    discoverySocket?.close();
  });
  return { app, server, config };
}

module.exports = {
  startServer,
  readConfig,
};

if (require.main === module) {
  startServer();
}
