const WebSocket = require("ws");

const url = "ws://127.0.0.1:38080/ws";
const device = new WebSocket(url);
const controller = new WebSocket(url);
let sessionId = "";
let sawInput = false;

function send(ws, msg) {
  ws.send(JSON.stringify(msg));
}

function done(ok, message) {
  console.log(message);
  device.close();
  controller.close();
  process.exit(ok ? 0 : 1);
}

device.on("open", () => {
  send(device, {
    type: "hello-device",
    id: "TEST-1001",
    name: "Test Phone",
    model: "Node Sim",
    permissions: { mediaProjection: true, accessibility: true },
    controlEnabled: true,
    screen: { width: 360, height: 720 }
  });
});

controller.on("open", () => {
  send(controller, { type: "hello-controller", token: "test-token" });
  setTimeout(() => send(controller, { type: "control", deviceId: "TEST-1001" }), 250);
});

controller.on("message", (raw) => {
  const msg = JSON.parse(String(raw));
  if (msg.type === "control-ready") {
    sessionId = msg.sessionId;
    send(controller, { type: "input", sessionId, action: "tap", x: 80, y: 120 });
  }
});

device.on("message", (raw) => {
  const msg = JSON.parse(String(raw));
  if (msg.type === "input" && msg.action === "tap" && msg.x === 80) {
    sawInput = true;
    done(true, "websocket relay ok");
  }
});

setTimeout(() => done(false, `websocket relay failed session=${sessionId} input=${sawInput}`), 5000);
