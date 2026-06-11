const serverState = document.getElementById("serverState");
const loginView = document.getElementById("loginView");
const appShell = document.getElementById("appShell");
const authForm = document.getElementById("authForm");
const loginModeBtn = document.getElementById("loginModeBtn");
const registerModeBtn = document.getElementById("registerModeBtn");
const usernameInput = document.getElementById("usernameInput");
const passwordInput = document.getElementById("passwordInput");
const authSubmitBtn = document.getElementById("authSubmitBtn");
const authHint = document.getElementById("authHint");
const accountName = document.getElementById("accountName");
const accountStatus = document.getElementById("accountStatus");
const logoutBtn = document.getElementById("logoutBtn");
const addForm = document.getElementById("addForm");
const deviceIdInput = document.getElementById("deviceIdInput");
const deviceCodeInput = document.getElementById("deviceCodeInput");
const bindHint = document.getElementById("bindHint");
const deviceShelf = document.getElementById("deviceShelf");
const activeTitle = document.getElementById("activeTitle");
const activeMeta = document.getElementById("activeMeta");
const viewerWrap = document.getElementById("viewerWrap");
const screenPlaceholder = document.getElementById("screenPlaceholder");
const viewer = document.getElementById("viewer");
const canvas = document.getElementById("screenCanvas");
const ctx = canvas.getContext("2d");
const remoteCursor = document.createElement("div");
remoteCursor.className = "remote-cursor hidden";
viewer.appendChild(remoteCursor);
const closeScreenBtn = document.getElementById("closeScreenBtn");
const backBtn = document.getElementById("backBtn");
const homeBtn = document.getElementById("homeBtn");
const homeSwipeBtn = document.getElementById("homeSwipeBtn");
const stopBtn = document.getElementById("stopBtn");
const binaryFrameMagic = "BHZF1";
const textDecoder = new TextDecoder();

let ws;
let reconnectTimer = 0;
let wsGeneration = 0;
let authMode = "login";
let sessionToken = localStorage.getItem("bhzn_session_token") || "";
let currentUser = null;
let devices = [];
let activeDeviceId = "";
let sessionId = "";
let screenOpen = false;
let dragStart = null;
let dragLast = null;
let dragLastSentAt = 0;
let dragMoved = false;
let dragStartedRemote = false;
let dragButton = "left";
let frameSize = { width: 0, height: 0 };
let inputReady = false;
let pendingText = "";
let pendingTextTimer = 0;
let frameDrawSerial = 0;
let lastFrameTimestamp = 0;

function devicePlatform(device) {
  return String(device?.platform || "android").toLowerCase();
}

function isDesktopDevice(device) {
  return ["windows", "macos", "linux"].includes(devicePlatform(device));
}

function defaultDeviceLabel(device) {
  const platform = devicePlatform(device);
  if (platform === "windows") return "Windows Device";
  if (platform === "macos") return "Mac Device";
  if (platform === "linux") return "Linux Device";
  if (platform === "ios") return "iPhone Device";
  return "Android Device";
}

function deviceName(device) {
  return device?.label || device?.name || defaultDeviceLabel(device);
}

function hasScreenPermission(device) {
  return Boolean(device?.permissions?.mediaProjection || device?.permissions?.screenCapture);
}

function hasInputPermission(device) {
  return Boolean(device?.permissions?.accessibility || device?.permissions?.inputControl);
}

function platformText(device) {
  const platform = devicePlatform(device);
  if (platform === "windows") return "Windows";
  if (platform === "macos") return "macOS";
  if (platform === "linux") return "Linux";
  if (platform === "ios") return "iPhone";
  return "Android";
}

function wsUrl() {
  const proto = location.protocol === "https:" ? "wss:" : "ws:";
  return `${proto}//${location.host}/ws`;
}

async function api(path, options = {}) {
  const headers = {
    "content-type": "application/json",
    ...(options.headers || {})
  };
  if (sessionToken) headers.authorization = `Bearer ${sessionToken}`;
  const response = await fetch(path, {
    ...options,
    headers,
    body: options.body ? JSON.stringify(options.body) : undefined
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(data.error || `http_${response.status}`);
    error.data = data;
    throw error;
  }
  return data;
}

function statusText(status) {
  if (status === "approved") return "已审核";
  if (status === "rejected") return "已拒绝";
  return "待审核";
}

function setAuthMode(mode) {
  authMode = mode;
  loginModeBtn.classList.toggle("active", mode === "login");
  registerModeBtn.classList.toggle("active", mode === "register");
  authSubmitBtn.textContent = mode === "login" ? "登录" : "注册";
  authHint.textContent = mode === "login"
    ? "登录后进入设备控制台。"
    : "注册后需要管理员审核，通过后才能进入控制台。";
}

function showLogin(message) {
  loginView.classList.remove("hidden");
  appShell.classList.add("hidden");
  if (message) authHint.textContent = message;
}

function showApp() {
  loginView.classList.add("hidden");
  appShell.classList.remove("hidden");
}

function setUser(user) {
  currentUser = user;
  const approved = user?.status === "approved";
  accountName.textContent = user?.username || "-";
  accountStatus.textContent = user ? statusText(user.status) : "-";
  serverState.textContent = user
    ? (approved ? "账号已审核" : statusText(user.status))
    : "请先登录";

  if (approved) {
    showApp();
    return;
  }

  closeSocket();
  devices = [];
  sessionId = "";
  activeDeviceId = "";
  closeScreen(false);
  setInputReady(false);
  renderDevices();
  showLogin(user ? (user.status === "pending" ? "账号已注册，正在等待管理员审核。" : "账号未通过审核，请联系管理员。") : "");
}

function closeSocket() {
  wsGeneration += 1;
  clearTimeout(reconnectTimer);
  if (ws) {
    ws.close();
    ws = null;
  }
}

function connect() {
  if (!sessionToken || currentUser?.status !== "approved") return;
  clearTimeout(reconnectTimer);
  closeSocket();
  const socketGeneration = ++wsGeneration;
  ws = new WebSocket(wsUrl());
  ws.binaryType = "arraybuffer";
  ws.addEventListener("open", () => {
    if (socketGeneration !== wsGeneration) return;
    sessionId = "";
    setInputReady(false);
    ws.send(JSON.stringify({ type: "hello-controller", token: sessionToken }));
  });
  ws.addEventListener("close", () => {
    if (socketGeneration !== wsGeneration) return;
    if (!sessionToken || currentUser?.status !== "approved") return;
    sessionId = "";
    setInputReady(false);
    if (screenOpen) activeMeta.textContent = "连接断开，正在重连";
    reconnectTimer = setTimeout(connect, 1200);
  });
  ws.addEventListener("message", (event) => {
    if (socketGeneration !== wsGeneration) return;
    if (typeof event.data !== "string") {
      handleBinaryFrame(event.data);
      return;
    }
    const msg = JSON.parse(event.data);
    if (msg.type === "devices") {
      devices = msg.devices || [];
      syncActiveDeviceState();
      renderDevices();
      resumeActiveControl();
    }
    if (msg.type === "control-ready") {
      sessionId = msg.sessionId;
      activeDeviceId = msg.device.id;
      openScreen();
      setActiveDevice(msg.device);
      setInputReady(canControlDevice(msg.device));
      stopBtn.disabled = false;
      canvas.focus({ preventScroll: true });
      activeMeta.textContent = canControlDevice(msg.device)
        ? `${deviceName(msg.device)} · ${msg.device.model || msg.device.osVersion || "未知型号"}`
        : `已进入画面，仅可观看：${platformText(msg.device)} 端未开启控制权限或允许控制开关`;
    }
    if (msg.type === "frame" && msg.deviceId === activeDeviceId && screenOpen) {
      drawFrame(msg);
    }
    if (msg.type === "control-stopped") {
      if (msg.sessionId && sessionId && msg.sessionId !== sessionId) return;
      sessionId = "";
      setControls(false);
      if (screenOpen) activeMeta.textContent = `控制已停止：${msg.reason || "ended"}`;
    }
    if (msg.type === "error") {
      if (screenOpen) activeMeta.textContent = errorText(msg);
      if (msg.error === "bad_session") {
        sessionId = "";
        setInputReady(false);
        resumeActiveControl();
      }
    }
  });
}

function openScreen() {
  screenOpen = true;
  viewerWrap.classList.remove("closed");
  screenPlaceholder.classList.add("hidden");
}

function closeScreen(stopRemote = true) {
  if (stopRemote && sessionId && ws?.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ type: "stop-control", sessionId }));
  }
  screenOpen = false;
  sessionId = "";
  activeDeviceId = "";
  frameSize = { width: 0, height: 0 };
  frameDrawSerial += 1;
  lastFrameTimestamp = 0;
  setInputReady(false);
  stopBtn.disabled = true;
  viewer.classList.remove("has-frame");
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  viewerWrap.classList.add("closed");
  screenPlaceholder.classList.remove("hidden");
  activeTitle.textContent = "未打开屏幕";
  activeMeta.textContent = "点击下方设备入口，打开对应设备屏幕。";
  renderDevices();
}

function resumeActiveControl() {
  if (!screenOpen || !activeDeviceId || sessionId || !ws || ws.readyState !== WebSocket.OPEN) return;
  const device = devices.find((item) => item.id === activeDeviceId);
  if (!device?.online || !hasScreenPermission(device)) return;
  activeMeta.textContent = "连接恢复，正在重新进入设备";
  ws.send(JSON.stringify({ type: "control", deviceId: activeDeviceId }));
}

function renderDevices() {
  if (!currentUser || currentUser.status !== "approved") return;
  if (!devices.length) {
    deviceShelf.innerHTML = `<div class="placeholder-card">还没有添加设备，请输入设备 ID 和验证码匹配。</div>`;
    return;
  }
  deviceShelf.innerHTML = "";
  for (const device of devices) {
    const desktop = isDesktopDevice(device);
    const btn = document.createElement("button");
    btn.className = `phone-card ${desktop ? "desktop-card" : ""} ${device.id === activeDeviceId ? "active" : ""} ${device.online ? "" : "offline"}`;
    btn.innerHTML = `
      <span class="phone-delete" title="删除设备" data-binding-id="${device.bindingId || ""}">×</span>
      <span class="phone-top"></span>
      <strong>${device.id}</strong>
      <em>${deviceName(device)}</em>
      <small class="device-status ${device.online ? "online" : "offline"}">${device.online ? "在线" : "离线"}</small>
      <div class="badges">
        <i class="badge agent-badge">${device.agentVersion ? `Agent ${device.agentVersion}` : "Agent -"}</i>
        <i class="badge ${hasScreenPermission(device) ? "" : "warn"}">屏幕${hasScreenPermission(device) ? "已开" : "未开"}</i>
        <i class="badge ${hasInputPermission(device) ? "" : "warn"}">控制${hasInputPermission(device) ? "已开" : "未开"}</i>
      </div>
    `;
    btn.addEventListener("click", () => startControl(device.id));
    const deleteBtn = btn.querySelector(".phone-delete");
    deleteBtn.addEventListener("click", (event) => {
      event.stopPropagation();
      deleteBinding(device);
    });
    deviceShelf.appendChild(btn);
  }
}

async function deleteBinding(device) {
  if (!device.bindingId) return;
  if (!confirm(`删除设备 ${device.id}？`)) return;
  try {
    const data = await api(`/api/bindings/${encodeURIComponent(device.bindingId)}`, { method: "DELETE" });
    devices = data.devices || [];
    if (activeDeviceId === device.id) {
      closeScreen(false);
    }
    renderDevices();
  } catch (error) {
    bindHint.textContent = errorText(error.data || { error: error.message });
  }
}

function setActiveDevice(device) {
  activeTitle.textContent = device.id;
  activeMeta.textContent = `${deviceName(device)} · ${device.model || device.osVersion || "未知型号"}`;
  viewer.classList.toggle("desktop-viewer", isDesktopDevice(device));
  viewer.classList.toggle("phone-viewer", !isDesktopDevice(device));
  renderDevices();
}

function syncActiveDeviceState() {
  if (!activeDeviceId) return;
  const device = devices.find((item) => item.id === activeDeviceId);
  if (!device) return;
  if (!device.online) {
    sessionId = "";
    setInputReady(false);
    if (screenOpen) activeMeta.textContent = "设备已添加，但当前离线";
    return;
  }
  setInputReady(Boolean(sessionId) && canControlDevice(device));
  stopBtn.disabled = !sessionId;
  if (sessionId && canControlDevice(device)) {
    activeMeta.textContent = `${deviceName(device)} · ${device.model || device.osVersion || "未知型号"} · 可操作`;
  }
}

function setControls(enabled) {
  setInputReady(enabled);
  stopBtn.disabled = !enabled;
}

function canControlDevice(device) {
  return Boolean(device?.controlEnabled && hasScreenPermission(device) && hasInputPermission(device));
}

function setInputReady(enabled) {
  inputReady = Boolean(enabled);
  backBtn.disabled = !inputReady;
  homeBtn.disabled = !inputReady;
  homeSwipeBtn.disabled = !inputReady;
}

function errorText(msg) {
  const device = msg.device || {};
  if (msg.error === "screen_not_ready") return `设备已在线，但 ${platformText(device)} 端还没有开启屏幕权限`;
  if (msg.error === "input_not_ready") return `当前仅可观看：${platformText(device)} 端未开启控制权限或允许控制开关`;
  if (msg.error === "device_offline") return "设备不在线";
  if (msg.error === "device_not_bound") return "设备还没有绑定到当前账号";
  if (msg.error === "bad_verification_code") return "设备验证码不正确";
  if (msg.error === "user_not_approved") return "账号还没有审核通过";
  if (msg.error === "invalid_login") return "用户名或密码错误";
  if (msg.error === "username_exists") return "用户名已存在";
  if (msg.error === "bad_credentials") return "用户名或密码格式不正确";
  if (msg.error === "binding_not_found") return "设备绑定记录不存在";
  return `操作失败：${msg.error}`;
}

function startControl(id) {
  activeDeviceId = id.trim().toUpperCase();
  const device = devices.find((item) => item.id === activeDeviceId);
  openScreen();
  viewer.classList.remove("has-frame");
  frameDrawSerial += 1;
  lastFrameTimestamp = 0;
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  if (device) setActiveDevice(device);
  if (!device?.online) {
    activeMeta.textContent = "设备已添加，但当前离线";
    return;
  }
  activeMeta.textContent = "正在请求控制";
  ws.send(JSON.stringify({ type: "control", deviceId: activeDeviceId }));
}

function drawFrame(msg) {
  if (!msg.image) return;
  if (msg.frameKind && msg.frameKind !== "jpeg") return;
  const timestamp = Number(msg.timestamp || 0);
  if (timestamp && timestamp < lastFrameTimestamp) return;
  const drawSerial = ++frameDrawSerial;
  const img = new Image();
  img.onload = () => {
    if (drawSerial !== frameDrawSerial) return;
    if (timestamp && timestamp < lastFrameTimestamp) return;
    canvas.width = img.naturalWidth;
    canvas.height = img.naturalHeight;
    frameSize = { width: img.naturalWidth, height: img.naturalHeight };
    viewer.style.aspectRatio = `${img.naturalWidth} / ${img.naturalHeight}`;
    ctx.drawImage(img, 0, 0);
    lastFrameTimestamp = timestamp || Date.now();
    viewer.classList.add("has-frame");
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: "frame-ack", deviceId: msg.deviceId, frameId: msg.frameId || timestamp }));
    }
  };
  img.src = `data:image/jpeg;base64,${msg.image}`;
}

function parseBinaryFrame(data) {
  const buffer = data instanceof ArrayBuffer ? data : data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength);
  if (buffer.byteLength < binaryFrameMagic.length + 4) return null;
  const prefix = textDecoder.decode(new Uint8Array(buffer, 0, binaryFrameMagic.length));
  if (prefix !== binaryFrameMagic) return null;
  const view = new DataView(buffer);
  const headerLength = view.getUint32(binaryFrameMagic.length, true);
  const headerStart = binaryFrameMagic.length + 4;
  const dataStart = headerStart + headerLength;
  if (headerLength < 2 || dataStart > buffer.byteLength) return null;
  const header = JSON.parse(textDecoder.decode(new Uint8Array(buffer, headerStart, headerLength)));
  return {
    header,
    image: new Uint8Array(buffer, dataStart)
  };
}

async function handleBinaryFrame(data) {
  let frame;
  try {
    frame = parseBinaryFrame(data);
  } catch {
    return;
  }
  if (!frame?.header) return;
  const header = frame.header;
  if (header.deviceId !== activeDeviceId || !screenOpen) return;
  if (header.frameKind && header.frameKind !== "jpeg") return;
  const timestamp = Number(header.timestamp || 0);
  if (timestamp && timestamp < lastFrameTimestamp) return;
  const drawSerial = ++frameDrawSerial;
  let bitmap;
  try {
    bitmap = await createImageBitmap(new Blob([frame.image], { type: "image/jpeg" }));
  } catch {
    return;
  }
  if (drawSerial !== frameDrawSerial || (timestamp && timestamp < lastFrameTimestamp)) {
    bitmap.close();
    return;
  }
  canvas.width = bitmap.width;
  canvas.height = bitmap.height;
  frameSize = { width: bitmap.width, height: bitmap.height };
  viewer.style.aspectRatio = `${bitmap.width} / ${bitmap.height}`;
  ctx.drawImage(bitmap, 0, 0);
  bitmap.close();
  lastFrameTimestamp = timestamp || Date.now();
  viewer.classList.add("has-frame");
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ type: "frame-ack", deviceId: header.deviceId, frameId: header.frameId || timestamp }));
  }
}

function canvasPoint(event) {
  const rect = canvas.getBoundingClientRect();
  const x = Math.max(0, Math.min(1, (event.clientX - rect.left) / rect.width));
  const y = Math.max(0, Math.min(1, (event.clientY - rect.top) / rect.height));
  return {
    x: Math.round(x * frameSize.width),
    y: Math.round(y * frameSize.height)
  };
}

function updateRemoteCursor(event) {
  const rect = canvas.getBoundingClientRect();
  const viewerRect = viewer.getBoundingClientRect();
  const x = Math.max(0, Math.min(rect.width, event.clientX - rect.left)) + rect.left - viewerRect.left;
  const y = Math.max(0, Math.min(rect.height, event.clientY - rect.top)) + rect.top - viewerRect.top;
  remoteCursor.style.transform = `translate(${Math.round(x)}px, ${Math.round(y)}px)`;
  remoteCursor.classList.remove("hidden");
}

function hideRemoteCursor() {
  remoteCursor.classList.add("hidden");
}

function sendInput(payload) {
  if (!screenOpen || !sessionId || !inputReady || !ws || ws.readyState !== WebSocket.OPEN) {
    if (screenOpen && !sessionId) resumeActiveControl();
    if (sessionId && !inputReady) {
      const activeDevice = devices.find((item) => item.id === activeDeviceId);
      activeMeta.textContent = `当前仅可观看：${platformText(activeDevice)} 端未开启控制权限或允许控制开关`;
    }
    return;
  }
  ws.send(JSON.stringify({ type: "input", sessionId, ...payload }));
}

function isTypingTarget(target) {
  const tag = String(target?.tagName || "").toLowerCase();
  return tag === "input" || tag === "textarea" || tag === "select" || target?.isContentEditable;
}

function flushPendingText() {
  clearTimeout(pendingTextTimer);
  if (!pendingText) return;
  const text = pendingText;
  pendingText = "";
  sendInput({ action: "text", text });
}

function queueTextInput(text) {
  pendingText += text;
  clearTimeout(pendingTextTimer);
  pendingTextTimer = setTimeout(flushPendingText, 60);
}

function keyModifiers(event) {
  const modifiers = [];
  if (event.ctrlKey) modifiers.push("ctrl");
  if (event.altKey) modifiers.push("alt");
  if (event.shiftKey) modifiers.push("shift");
  if (event.metaKey) modifiers.push("meta");
  return modifiers;
}

function shouldForwardKeyboard(event) {
  if (!screenOpen || !sessionId || !inputReady) return false;
  if (isTypingTarget(event.target)) return false;
  return true;
}

authForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  authSubmitBtn.disabled = true;
  try {
    const path = authMode === "login" ? "/api/auth/login" : "/api/auth/register";
    const data = await api(path, {
      method: "POST",
      body: { username: usernameInput.value.trim(), password: passwordInput.value }
    });
    if (authMode === "register") {
      authHint.textContent = "注册成功，等待管理员审核后再登录使用。";
      setAuthMode("login");
      return;
    }
    sessionToken = data.token;
    localStorage.setItem("bhzn_session_token", sessionToken);
    setUser(data.user);
    if (data.user.status === "approved") {
      connect();
      const list = await api("/api/devices");
      devices = list.devices || [];
      renderDevices();
    }
  } catch (error) {
    authHint.textContent = errorText(error.data || { error: error.message });
  } finally {
    authSubmitBtn.disabled = false;
  }
});

loginModeBtn.addEventListener("click", () => setAuthMode("login"));
registerModeBtn.addEventListener("click", () => setAuthMode("register"));

logoutBtn.addEventListener("click", async () => {
  try {
    if (sessionToken) await api("/api/auth/logout", { method: "POST" });
  } catch {
    // Local logout still wins.
  }
  sessionToken = "";
  currentUser = null;
  devices = [];
  localStorage.removeItem("bhzn_session_token");
  closeSocket();
  closeScreen(false);
  setAuthMode("login");
  setUser(null);
});

addForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  bindHint.textContent = "正在匹配设备";
  try {
    const data = await api("/api/bindings", {
      method: "POST",
      body: {
        deviceId: deviceIdInput.value.trim().toUpperCase(),
        verificationCode: deviceCodeInput.value.trim()
      }
    });
    devices = data.devices || devices;
    bindHint.textContent = "设备已添加";
    renderDevices();
    startControl(data.device.id);
  } catch (error) {
    bindHint.textContent = errorText(error.data || { error: error.message });
  }
});

canvas.addEventListener("pointerdown", (event) => {
  if (!sessionId || frameSize.width === 0) return;
  event.preventDefault();
  canvas.focus({ preventScroll: true });
  canvas.setPointerCapture(event.pointerId);
  updateRemoteCursor(event);
  const activeDevice = devices.find((item) => item.id === activeDeviceId);
  dragButton = event.button === 2 && isDesktopDevice(activeDevice) ? "right" : "left";
  dragStart = { ...canvasPoint(event), t: performance.now() };
  dragLast = dragStart;
  dragLastSentAt = dragStart.t;
  dragMoved = false;
  dragStartedRemote = false;
});

canvas.addEventListener("pointermove", (event) => {
  if (!dragStart || !dragLast) return;
  event.preventDefault();
  const now = performance.now();
  const activeDevice = devices.find((item) => item.id === activeDeviceId);
  const desktopDrag = isDesktopDevice(activeDevice);
  const minInterval = desktopDrag ? 10 : 55;
  if (now - dragLastSentAt < minInterval) return;
  const point = canvasPoint(event);
  updateRemoteCursor(event);
  const dxTotal = Math.abs(point.x - dragStart.x);
  const dyTotal = Math.abs(point.y - dragStart.y);
  const dx = Math.abs(point.x - dragLast.x);
  const dy = Math.abs(point.y - dragLast.y);
  const startThreshold = desktopDrag ? 1 : 10;
  const moveThreshold = desktopDrag ? 1 : 4;
  if (dxTotal < startThreshold && dyTotal < startThreshold) return;
  if (dx < moveThreshold && dy < moveThreshold) return;
  if (!dragStartedRemote) {
    sendInput({ action: "dragStart", x: dragStart.x, y: dragStart.y, duration: 16, button: dragButton });
    dragStartedRemote = true;
  }
  sendInput({
    action: "dragMove",
    x: point.x,
    y: point.y,
    duration: Math.max(16, Math.min(80, Math.round(now - dragLastSentAt))),
    button: dragButton
  });
  dragMoved = true;
  dragLast = point;
  dragLastSentAt = now;
});

window.addEventListener("beforeinput", (event) => {
  if (!shouldForwardKeyboard(event)) return;
  if (event.inputType !== "insertText" || !event.data) return;
  event.preventDefault();
  queueTextInput(event.data);
});

window.addEventListener("keydown", (event) => {
  if (!shouldForwardKeyboard(event)) return;
  const printable = event.key && event.key.length === 1;
  const hasCommandModifier = event.ctrlKey || event.altKey || event.metaKey;
  if (printable && !hasCommandModifier) {
    event.preventDefault();
    queueTextInput(event.key);
    return;
  }
  flushPendingText();
  event.preventDefault();
  sendInput({
    action: "key",
    key: event.key,
    code: event.code,
    modifiers: keyModifiers(event)
  });
});

canvas.addEventListener("pointerup", (event) => {
  if (!dragStart) return;
  event.preventDefault();
  const end = canvasPoint(event);
  updateRemoteCursor(event);
  const dx = Math.abs(end.x - dragStart.x);
  const dy = Math.abs(end.y - dragStart.y);
  const now = performance.now();
  const button = dragButton;
  if (!dragMoved && dx < 12 && dy < 12) {
    sendInput({ action: button === "right" ? "rightClick" : "tap", x: end.x, y: end.y, duration: 80, button });
  } else if (dragLast && dragStartedRemote) {
    sendInput({
      action: "dragEnd",
      x: end.x,
      y: end.y,
      duration: Math.max(16, Math.min(100, Math.round(now - dragLastSentAt))),
      button
    });
  }
  dragStart = null;
  dragLast = null;
  dragMoved = false;
  dragStartedRemote = false;
  dragButton = "left";
  setTimeout(hideRemoteCursor, 180);
});

canvas.addEventListener("pointercancel", () => {
  dragStart = null;
  dragLast = null;
  dragMoved = false;
  dragStartedRemote = false;
  dragButton = "left";
  hideRemoteCursor();
});

canvas.addEventListener("contextmenu", (event) => {
  event.preventDefault();
});

canvas.addEventListener("wheel", (event) => {
  const activeDevice = devices.find((item) => item.id === activeDeviceId);
  if (!isDesktopDevice(activeDevice)) return;
  event.preventDefault();
  const point = canvasPoint(event);
  sendInput({
    action: "scroll",
    x: point.x,
    y: point.y,
    deltaX: Math.round(event.deltaX),
    deltaY: Math.round(event.deltaY)
  });
}, { passive: false });

closeScreenBtn.addEventListener("click", () => closeScreen(true));
backBtn.addEventListener("click", () => sendInput({ action: "back" }));
homeBtn.addEventListener("click", () => sendInput({ action: "home" }));
homeSwipeBtn.addEventListener("click", () => {
  if (!frameSize.width || !frameSize.height) return;
  sendInput({
    action: "homeSwipe",
    x: Math.round(frameSize.width / 2),
    y: Math.max(1, frameSize.height - 8),
    x2: Math.round(frameSize.width / 2),
    y2: Math.round(frameSize.height * 0.55),
    duration: 320
  });
});
stopBtn.addEventListener("click", () => {
  if (!sessionId) return;
  ws.send(JSON.stringify({ type: "stop-control", sessionId }));
  sessionId = "";
  setControls(false);
});

async function boot() {
  setAuthMode("login");
  showLogin();
  closeScreen(false);
  if (!sessionToken) {
    setUser(null);
    return;
  }
  try {
    const data = await api("/api/me");
    setUser(data.user);
    if (data.user.status === "approved") {
      connect();
      const list = await api("/api/devices");
      devices = list.devices || [];
      renderDevices();
    }
  } catch {
    sessionToken = "";
    localStorage.removeItem("bhzn_session_token");
    setUser(null);
  }
}

boot();
