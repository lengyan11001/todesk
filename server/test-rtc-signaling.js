const http = require("http");
const { spawn } = require("child_process");
const WebSocket = require("ws");

const port = Number(process.env.TEST_PORT || 38182);
const base = `http://127.0.0.1:${port}`;
const wsUrl = `ws://127.0.0.1:${port}/ws`;

function request(method, path, body, headers = {}) {
  return new Promise((resolve, reject) => {
    const data = body ? Buffer.from(JSON.stringify(body)) : null;
    const req = http.request(`${base}${path}`, {
      method,
      headers: {
        "content-type": "application/json",
        ...(data ? { "content-length": data.length } : {}),
        ...headers
      }
    }, (res) => {
      const chunks = [];
      res.on("data", (chunk) => chunks.push(chunk));
      res.on("end", () => {
        const text = Buffer.concat(chunks).toString("utf8");
        const parsed = text ? JSON.parse(text) : {};
        if (res.statusCode >= 400) reject(new Error(`${res.statusCode} ${text}`));
        else resolve(parsed);
      });
    });
    req.on("error", reject);
    if (data) req.write(data);
    req.end();
  });
}

function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function openWs() {
  const ws = new WebSocket(wsUrl);
  ws.messages = [];
  ws.on("message", (raw) => {
    ws.messages.push(JSON.parse(String(raw)));
  });
  return new Promise((resolve, reject) => {
    ws.on("open", () => resolve(ws));
    ws.on("error", reject);
  });
}

function send(ws, msg) {
  ws.send(JSON.stringify(msg));
}

async function waitFor(ws, predicate, label, timeoutMs = 2000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const found = ws.messages.find(predicate);
    if (found) return found;
    await wait(25);
  }
  throw new Error(`timeout waiting for ${label}`);
}

async function waitHttpReady() {
  const deadline = Date.now() + 5000;
  while (Date.now() < deadline) {
    try {
      await request("GET", "/api/health");
      return;
    } catch {
      await wait(100);
    }
  }
  throw new Error("server did not become ready");
}

async function main() {
  const server = spawn(process.execPath, ["src/server.js"], {
    cwd: __dirname,
    env: {
      ...process.env,
      PORT: String(port),
      HOST: "127.0.0.1",
      DATA_DIR: `/tmp/bhzn-rtc-test-${Date.now()}`,
      RTC_STUN_URLS: "stun:example.test:3478",
      RTC_TURN_URLS: "turn:turn.example.test:3478?transport=udp",
      RTC_TURN_SECRET: "test-secret",
      DEFAULT_ADMIN_USERNAME: "admin",
      DEFAULT_ADMIN_PASSWORD: "todesk2026"
    },
    stdio: ["ignore", "pipe", "pipe"]
  });
  const logs = [];
  server.stdout.on("data", (chunk) => logs.push(String(chunk)));
  server.stderr.on("data", (chunk) => logs.push(String(chunk)));

  try {
    await waitHttpReady();
    const username = `rtctest_${Date.now()}`;
    await request("POST", "/api/auth/register", { username, password: "123456" });
    const admin = await request("POST", "/api/admin/login", { username: "admin", password: "todesk2026" });
    const users = await request("GET", "/api/admin/users", null, { authorization: `Bearer ${admin.token}` });
    const user = users.users.find((item) => item.username === username);
    await request("POST", `/api/admin/users/${user.id}/status`, { status: "approved" }, { authorization: `Bearer ${admin.token}` });
    const login = await request("POST", "/api/auth/login", { username, password: "123456" });

    const device = await openWs();
    const controller = await openWs();
    send(device, {
      type: "hello-device",
      id: "RTC-1001",
      name: "RTC Test Device",
      platform: "macos",
      verificationCode: "123456",
      permissions: { screenCapture: true, inputControl: true },
      controlEnabled: true,
      screen: { width: 1280, height: 720, inputWidth: 1280, inputHeight: 720 },
      rtcCapabilities: { webrtc: true, video: true, dataChannel: true, localNetwork: true, codecs: ["H264", "VP8"], version: "test" }
    });
    send(controller, { type: "hello-controller", token: login.token });
    await waitFor(device, (msg) => msg.type === "hello", "device hello");
    await waitFor(controller, (msg) => msg.type === "hello", "controller hello");
    await request("POST", "/api/bindings", { deviceId: "RTC-1001", verificationCode: "123456" }, { authorization: `Bearer ${login.token}` });

    send(controller, { type: "rtc-start", deviceId: "RTC-1001", mode: "control" });
    const ready = await waitFor(controller, (msg) => msg.type === "rtc-ready", "rtc-ready");
    const rtcRequest = await waitFor(device, (msg) => msg.type === "rtc-request", "rtc-request");
    if (ready.sessionId !== rtcRequest.sessionId) throw new Error("session id mismatch");
    if (!Array.isArray(ready.iceServers) || ready.iceServers.length < 2) throw new Error("missing ice servers");
    const turn = ready.iceServers.find((item) => String([].concat(item.urls || []).join(",")).includes("turn:"));
    if (!turn?.username || !turn?.credential) throw new Error("missing turn credential");

    send(controller, { type: "rtc-offer", sessionId: ready.sessionId, deviceId: "RTC-1001", sdp: "v=0\r\n" });
    await waitFor(device, (msg) => msg.type === "rtc-offer" && msg.sessionId === ready.sessionId, "rtc-offer");
    send(device, { type: "rtc-answer", sessionId: ready.sessionId, deviceId: "RTC-1001", sdp: "v=0\r\n" });
    await waitFor(controller, (msg) => msg.type === "rtc-answer" && msg.sessionId === ready.sessionId, "rtc-answer");

    const candidate = { candidate: "candidate:1 1 UDP 2122252543 192.168.1.2 5678 typ host", sdpMid: "0", sdpMLineIndex: 0 };
    send(controller, { type: "rtc-ice-candidate", sessionId: ready.sessionId, deviceId: "RTC-1001", candidate });
    await waitFor(device, (msg) => msg.type === "rtc-ice-candidate" && msg.candidate?.candidate === candidate.candidate, "rtc candidate");

    const controlRequest = device.messages.find((msg) => msg.type === "control-request");
    if (controlRequest) throw new Error("rtc-start should not trigger legacy control-request");

    send(controller, { type: "rtc-stop", sessionId: ready.sessionId, deviceId: "RTC-1001", reason: "test_done" });
    await waitFor(device, (msg) => msg.type === "rtc-stopped" && msg.reason === "test_done", "rtc stopped");

    device.close();
    controller.close();
    console.log("rtc signaling ok", ready.sessionId);
  } finally {
    server.kill("SIGTERM");
    await wait(100);
    if (process.env.DEBUG_RTC_TEST) process.stderr.write(logs.join(""));
  }
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
