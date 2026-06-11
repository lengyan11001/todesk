const cmsState = document.getElementById("cmsState");
const adminForm = document.getElementById("adminForm");
const adminUsernameInput = document.getElementById("adminUsernameInput");
const adminPasswordInput = document.getElementById("adminPasswordInput");
const adminSubmitBtn = document.getElementById("adminSubmitBtn");
const adminLogoutBtn = document.getElementById("adminLogoutBtn");
const cmsContent = document.getElementById("cmsContent");
const userTable = document.getElementById("userTable");
const bindingTable = document.getElementById("bindingTable");

let adminToken = localStorage.getItem("bhzn_admin_session") || "";

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
  localStorage.removeItem("bhzn_admin_session");
  cmsContent.classList.add("hidden");
  adminUsernameInput.classList.remove("hidden");
  adminPasswordInput.classList.remove("hidden");
  adminSubmitBtn.classList.remove("hidden");
  adminLogoutBtn.classList.add("hidden");
  userTable.innerHTML = "";
  bindingTable.innerHTML = "";
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

async function loadCms() {
  if (!adminToken) {
    setLoggedOut();
    return;
  }
  cmsState.textContent = "正在加载";
  try {
    const [me, users, bindings] = await Promise.all([
      adminApi("/api/admin/me"),
      adminApi("/api/admin/users"),
      adminApi("/api/admin/bindings")
    ]);
    setLoggedIn(me.admin);
    renderUsers(users.users || []);
    renderBindings(bindings.bindings || []);
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
