const fs = require("fs");
const http = require("http");
const WebSocket = require("ws");

const base = "http://127.0.0.1:38181";
const wsUrl = "ws://127.0.0.1:38181/ws";

function request(method, path, body, headers = {}) {
  return new Promise((resolve, reject) => {
    const data = Buffer.isBuffer(body) ? body : body ? Buffer.from(JSON.stringify(body)) : null;
    const req = http.request(`${base}${path}`, {
      method,
      headers: {
        ...(Buffer.isBuffer(body) ? {} : { "content-type": "application/json" }),
        ...(data ? { "content-length": data.length } : {}),
        ...headers
      }
    }, (res) => {
      const chunks = [];
      res.on("data", (chunk) => chunks.push(chunk));
      res.on("end", () => {
        const text = Buffer.concat(chunks).toString("utf8");
        const parsed = text ? JSON.parse(text) : {};
        if (res.statusCode >= 400) {
          reject(new Error(`${res.statusCode} ${text}`));
        } else {
          resolve(parsed);
        }
      });
    });
    req.on("error", reject);
    if (data) req.write(data);
    req.end();
  });
}

function send(ws, msg) {
  ws.send(JSON.stringify(msg));
}

async function main() {
  const username = `filetest_${Date.now()}`;
  await request("POST", "/api/auth/register", { username, password: "123456" });
  const admin = await request("POST", "/api/admin/login", { username: "admin", password: "todesk2026" });
  const users = await request("GET", "/api/admin/users", null, { authorization: `Bearer ${admin.token}` });
  const user = users.users.find((item) => item.username === username);
  await request("POST", `/api/admin/users/${user.id}/status`, { status: "approved" }, { authorization: `Bearer ${admin.token}` });
  const login = await request("POST", "/api/auth/login", { username, password: "123456" });

  const device = new WebSocket(wsUrl);
  const controller = new WebSocket(wsUrl);
  let fileTask;
  let transferStatus;

  await new Promise((resolve, reject) => {
    let opened = 0;
    const ready = () => {
      opened += 1;
      if (opened === 2) resolve();
    };
    device.on("open", ready);
    controller.on("open", ready);
    device.on("error", reject);
    controller.on("error", reject);
  });

  device.on("message", (raw) => {
    const msg = JSON.parse(String(raw));
    if (msg.type === "file-transfer") {
      fileTask = msg;
      send(device, { type: "file-transfer-status", transferId: msg.transferId, status: "downloading", bytes: 0 });
      send(device, { type: "file-transfer-status", transferId: msg.transferId, status: "saved", path: "C:\\Downloads\\hello.txt", bytes: msg.size });
    }
  });
  controller.on("message", (raw) => {
    const msg = JSON.parse(String(raw));
    if (msg.type === "file-transfer") {
      transferStatus = msg.transfer;
    }
  });

  send(device, {
    type: "hello-device",
    id: "FILE-1001",
    name: "File Test Device",
    platform: "windows",
    verificationCode: "123456",
    permissions: { screenCapture: true, inputControl: true },
    controlEnabled: true,
    screen: { width: 100, height: 100, inputWidth: 100, inputHeight: 100 }
  });
  send(controller, { type: "hello-controller", token: login.token });
  await new Promise((resolve) => setTimeout(resolve, 200));
  await request("POST", "/api/bindings", { deviceId: "FILE-1001", verificationCode: "123456" }, { authorization: `Bearer ${login.token}` });
  const payload = Buffer.from("hello file transfer");
  const created = await request("POST", "/api/file-transfers", payload, {
    authorization: `Bearer ${login.token}`,
    "content-type": "application/octet-stream",
    "x-device-id": "FILE-1001",
    "x-file-name": encodeURIComponent("hello.txt")
  });
  await new Promise((resolve) => setTimeout(resolve, 400));

  device.close();
  controller.close();
  if (!fileTask) throw new Error("device did not receive file-transfer");
  if (!created.transfer || created.transfer.status !== "dispatched") throw new Error("bad create response");
  if (!transferStatus || transferStatus.status !== "saved") throw new Error("controller did not receive saved status");
  console.log("file transfer relay ok", fileTask.transferId);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
