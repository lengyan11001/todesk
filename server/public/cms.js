const cmsState = document.getElementById("cmsState");
const adminForm = document.getElementById("adminForm");
const adminUsernameInput = document.getElementById("adminUsernameInput");
const adminPasswordInput = document.getElementById("adminPasswordInput");
const adminSubmitBtn = document.getElementById("adminSubmitBtn");
const adminLogoutBtn = document.getElementById("adminLogoutBtn");
const cmsContent = document.getElementById("cmsContent");
const userTable = document.getElementById("userTable");
const bindingTable = document.getElementById("bindingTable");
const linkTable = document.getElementById("linkTable");

let adminToken = localStorage.getItem("bhzn_admin_session") || "";
let cmsRefreshTimer = 0;

function statusText(status) {
  if (status === "approved") return "已通过";
  if (status === "rejected") return "已拒绝";
  return "待审核";
}

async function adminApi(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: {
      "content-type": "application/json",
      ...(adminToken ? { authorization: `Bearer ${adminToken}` } : {}),
      ...(options.headers || {})
    },
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

function setLoggedIn(admin) {
  cmsContent.classList.remove("hidden");
  adminUsernameInput.classList.add("hidden");
  adminPasswordInput.classList.add("hidden");
  adminSubmitBtn.classList.add("hidden");
  adminLogoutBtn.classList.remove("hidden");
  cmsState.textContent = `${admin.username} 已登录`;
}

function setLoggedOut(message = "请登录管理员账号") {
  adminToken = "";
  clearTimeout(cmsRefreshTimer);
  localStorage.removeItem("bhzn_admin_session");
  cmsContent.classList.add("hidden");
  adminUsernameInput.classList.remove("hidden");
  adminPasswordInput.classList.remove("hidden");
  adminSubmitBtn.classList.remove("hidden");
  adminLogoutBtn.classList.add("hidden");
  userTable.innerHTML = "";
  bindingTable.innerHTML = "";
  if (linkTable) linkTable.innerHTML = "";
  cmsState.textContent = message;
}

function renderUsers(users) {
  if (!users.length) {
    userTable.innerHTML = `<div class="placeholder-card">暂无注册用户</div>`;
    return;
  }
  userTable.innerHTML = "";
  for (const user of users) {
    const row = document.createElement("div");
    row.className = "table-row";
    row.innerHTML = `
      <div>
        <strong>${user.username}</strong>
        <span>${statusText(user.status)} · ${new Date(user.createdAt).toLocaleString()}</span>
      </div>
      <div class="row-actions">
        <button data-action="approved" ${user.status === "approved" ? "disabled" : ""}>通过</button>
        <button data-action="rejected" class="danger-small" ${user.status === "rejected" ? "disabled" : ""}>拒绝</button>
      </div>
    `;
    row.querySelectorAll("button[data-action]").forEach((button) => {
      button.addEventListener("click", () => updateUserStatus(user.id, button.dataset.action));
    });
    userTable.appendChild(row);
  }
}

function renderBindings(bindings) {
  if (!bindings.length) {
    bindingTable.innerHTML = `<div class="placeholder-card">暂无设备绑定记录</div>`;
    return;
  }
  bindingTable.innerHTML = "";
  for (const binding of bindings) {
    const row = document.createElement("div");
    row.className = "table-row";
    row.innerHTML = `
      <div>
        <strong>${binding.deviceId}</strong>
        <span>${binding.username} · ${binding.online ? "在线" : "离线"} · ${binding.label || "Android Device"}</span>
      </div>
      <div class="mini-badges">
        <i class="badge ${binding.permissions?.mediaProjection ? "" : "warn"}">录屏${binding.permissions?.mediaProjection ? "已开" : "未开"}</i>
        <i class="badge ${binding.permissions?.accessibility ? "" : "warn"}">输入${binding.permissions?.accessibility ? "已开" : "未开"}</i>
      </div>
    `;
    bindingTable.appendChild(row);
  }
}

function formatBytes(bytes) {
  const value = Number(bytes || 0);
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  if (value < 1024 * 1024 * 1024) return `${(value / 1024 / 1024).toFixed(1)} MB`;
  return `${(value / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

function pathText(path) {
  if (path === "rtc-lan") return "局域网直连";
  if (path === "rtc-p2p") return "公网 P2P";
  if (path === "rtc-turn") return "TURN UDP";
  if (path === "ws-relay") return "WebSocket 中转";
  if (path === "rtc") return "WebRTC 连接中";
  if (path === "online") return "在线待命";
  return "离线";
}

function pathClass(path) {
  if (path === "rtc-lan" || path === "rtc-p2p") return "good";
  if (path === "rtc-turn") return "warn";
  if (path === "ws-relay") return "danger";
  return "";
}

function renderLinks(links) {
  if (!linkTable) return;
  if (!links.length) {
    linkTable.innerHTML = `<div class="placeholder-card">暂无设备链路数据</div>`;
    return;
  }
  linkTable.innerHTML = "";
  for (const item of links) {
    const link = item.link || {};
    const rtc = (link.activeRtcSessions || [])[0];
    const relay = (link.activeRelaySessions || [])[0];
    const quality = rtc?.quality || relay?.quality || {};
    const row = document.createElement("div");
    row.className = "table-row link-row";
    row.innerHTML = `
      <div>
        <strong>${item.deviceId}</strong>
        <span>${item.online ? "在线" : "离线"} · ${item.label || item.name || item.platform || "-"} · ${item.agentVersion || "Agent -"}</span>
        <span>owner: ${(item.owners || []).map((owner) => owner.username).join(", ") || "-"}</span>
      </div>
      <div class="link-fields">
        <i class="badge ${pathClass(link.activePath)}">${pathText(link.activePath)}</i>
        <b>${quality.profile || "-"} · ${quality.maxSide || "-"}px · ${quality.fps || "-"}fps</b>
        <span>relay: ${formatBytes(link.bytesFromDevice)} / ${formatBytes(link.bytesToControllers)}</span>
        <span>rtc: ${formatBytes(link.rtcBytesSent)} / ${formatBytes(link.rtcBytesReceived)} · ${Math.round(link.rtcBitrateKbps || 0)} kbps · ${Math.round(link.rtcRttMs || 0)} ms</span>
        <span>会话: RTC ${(link.activeRtcSessions || []).length} · 中转 ${(link.activeRelaySessions || []).length}${relay?.ttlSeconds ? ` · 中转剩余 ${Math.ceil(relay.ttlSeconds / 60)} 分钟` : ""}</span>
      </div>
    `;
    linkTable.appendChild(row);
  }
}

async function loadCms() {
  if (!adminToken) {
    setLoggedOut();
    return;
  }
  cmsState.textContent = "正在加载";
  try {
    const [me, users, bindings, links] = await Promise.all([
      adminApi("/api/admin/me"),
      adminApi("/api/admin/users"),
      adminApi("/api/admin/bindings"),
      adminApi("/api/admin/device-links")
    ]);
    setLoggedIn(me.admin);
    renderUsers(users.users || []);
    renderBindings(bindings.bindings || []);
    renderLinks(links.links || []);
    clearTimeout(cmsRefreshTimer);
    cmsRefreshTimer = setTimeout(loadCms, 5000);
  } catch {
    setLoggedOut("管理员登录已失效，请重新登录");
  }
}

async function updateUserStatus(id, status) {
  cmsState.textContent = "正在保存";
  try {
    await adminApi(`/api/admin/users/${id}/status`, {
      method: "POST",
      body: { status }
    });
    await loadCms();
  } catch {
    cmsState.textContent = "保存失败";
  }
}

adminForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  cmsState.textContent = "正在登录";
  try {
    const data = await adminApi("/api/admin/login", {
      method: "POST",
      body: {
        username: adminUsernameInput.value.trim(),
        password: adminPasswordInput.value
      }
    });
    adminToken = data.token;
    localStorage.setItem("bhzn_admin_session", adminToken);
    adminPasswordInput.value = "";
    await loadCms();
  } catch {
    setLoggedOut("管理员账号或密码错误");
  }
});

adminLogoutBtn.addEventListener("click", async () => {
  try {
    if (adminToken) await adminApi("/api/admin/logout", { method: "POST" });
  } catch {
    // Local logout still wins.
  }
  setLoggedOut();
});

loadCms();
