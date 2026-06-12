# BHZN ToDesk WebRTC 客户端实现规范

更新时间：2026-06-12

本文给 Windows、Android、macOS、H5 客户端实现 WebRTC/P2P 远控使用。目标是先做局域网直连和公网 P2P，失败后走 TURN，最后回退当前 WebSocket/JPEG 中转。

## 目标架构

优先级从高到低：

```text
1. 同局域网 WebRTC host candidate 直连
2. 公网 WebRTC srflx candidate P2P 打洞
3. TURN relay 中转
4. 当前 WebSocket/JPEG 中转回退
```

服务器职责：

- 登录鉴权
- 设备绑定校验
- 设备在线状态
- WebRTC 信令转发
- TURN 临时凭证签发
- 中转回退

客户端职责：

- 采集屏幕
- 编码视频
- 建立 RTCPeerConnection
- 处理 ICE candidate
- 用 DataChannel 传输入、剪贴板、控制消息
- 失败时回退旧链路

## 基础约束

- 所有 WebRTC 信令都走现有 `/ws` WebSocket。
- 控制端必须先登录并绑定设备。
- 被控端必须已上线，并且具备屏幕权限。
- 输入控制必须在设备具备输入权限后才开放。
- TURN 凭证只能由服务器临时签发，不能写死在客户端。

## 信令消息

所有消息都是 JSON。

### H5/控制端发起 RTC

```json
{
  "type": "rtc-start",
  "deviceId": "4KIO-4ZVZ",
  "mode": "control",
  "sessionId": "optional-existing-control-session"
}
```

服务端校验：

- 当前 WebSocket 是已审核用户。
- 用户已绑定 `deviceId`。
- 设备在线。
- 设备具备屏幕权限。

成功后服务端返回：

```json
{
  "type": "rtc-ready",
  "sessionId": "control-session-id",
  "deviceId": "4KIO-4ZVZ",
  "iceServers": [
    { "urls": ["stun:stun.example.com:3478"] },
    {
      "urls": ["turn:turn.example.com:3478?transport=udp"],
      "username": "temporary-user",
      "credential": "temporary-password"
    }
  ],
  "ttlSeconds": 3600
}
```

同时服务端发给 Agent：

```json
{
  "type": "rtc-request",
  "sessionId": "control-session-id",
  "deviceId": "4KIO-4ZVZ",
  "controllerId": "user-id",
  "iceServers": [
    { "urls": ["stun:stun.example.com:3478"] },
    {
      "urls": ["turn:turn.example.com:3478?transport=udp"],
      "username": "temporary-user",
      "credential": "temporary-password"
    }
  ],
  "ttlSeconds": 3600
}
```

### Offer/Answer

控制端创建 offer 后发送：

```json
{
  "type": "rtc-offer",
  "sessionId": "control-session-id",
  "deviceId": "4KIO-4ZVZ",
  "sdp": "v=0..."
}
```

Agent 创建 answer 后发送：

```json
{
  "type": "rtc-answer",
  "sessionId": "control-session-id",
  "deviceId": "4KIO-4ZVZ",
  "sdp": "v=0..."
}
```

服务端只在该 session 的控制端和设备之间转发，不广播。

### ICE Candidate

两端都用同一格式：

```json
{
  "type": "rtc-ice-candidate",
  "sessionId": "control-session-id",
  "deviceId": "4KIO-4ZVZ",
  "candidate": {
    "candidate": "candidate:...",
    "sdpMid": "0",
    "sdpMLineIndex": 0
  }
}
```

### RTC 连接状态

任一端状态变化都可以上报：

```json
{
  "type": "rtc-state",
  "sessionId": "control-session-id",
  "deviceId": "4KIO-4ZVZ",
  "state": "checking|connected|completed|disconnected|failed|closed",
  "selectedCandidateType": "host|srflx|relay|unknown",
  "rttMs": 25,
  "bitrateKbps": 1800
}
```

服务端用于审计、计费、诊断。

### RTC 停止

```json
{
  "type": "rtc-stop",
  "sessionId": "control-session-id",
  "deviceId": "4KIO-4ZVZ",
  "reason": "controller_stopped|failed|fallback"
}
```

## RTCPeerConnection 角色

推荐：

- H5/控制端：Offerer
- Agent/被控端：Answerer

原因：

- 浏览器端实现简单。
- H5 可以在用户点击控制时立即创建 offer。
- Agent 接到 `rtc-request` 后再采集和编码，避免无人观看时消耗资源。

## 媒体轨道

### Agent 发送

Agent 必须发送：

- `video` track：屏幕视频

Agent 可选发送：

- `audio` track：系统声音，第一阶段不做。

### H5 接收

H5 监听：

```js
pc.ontrack = (event) => {
  videoElement.srcObject = event.streams[0];
};
```

H5 不需要发送视频或音频。

## 编码建议

### Windows

优先：

- DXGI Desktop Duplication / Windows Graphics Capture
- Media Foundation / WebRTC native encoder
- H.264 硬编码

最低目标：

- 720P 5-15fps
- 1080P 5-15fps

### Android

优先：

- MediaProjection
- MediaCodec H.264
- WebRTC Android SDK

注意：

- Android 需要前台服务。
- 屏幕采集必须用户授权。
- 输入控制仍依赖无障碍服务或厂商能力。

### macOS

优先：

- ScreenCaptureKit
- VideoToolbox H.264
- WebRTC native SDK

当前 macOS 代码已有 ScreenCaptureKit/JPEG 桥，但还不是 WebRTC video track。需要新增 native WebRTC 发送层。

## DataChannel

创建两个 DataChannel：

### control

可靠、有序：

```js
pc.createDataChannel("control", { ordered: true });
```

用于：

- 鼠标点击
- 拖动
- 键盘
- 剪贴板
- 控制状态

输入消息格式沿用当前 H5 输入协议：

```json
{
  "type": "input",
  "sessionId": "control-session-id",
  "inputId": "h5-1781252800000-1",
  "action": "tap|rightClick|dragStart|dragMove|dragEnd|scroll|key|text",
  "x": 100,
  "y": 200,
  "x2": 100,
  "y2": 300,
  "duration": 80,
  "button": "left|right",
  "deltaX": 0,
  "deltaY": -3,
  "key": "Enter",
  "code": "Enter",
  "text": "hello",
  "modifiers": ["ctrl"]
}
```

Agent 执行后回：

```json
{
  "type": "input-result",
  "sessionId": "control-session-id",
  "inputId": "h5-1781252800000-1",
  "action": "tap",
  "ok": true,
  "error": ""
}
```

### telemetry

不可靠、可丢：

```js
pc.createDataChannel("telemetry", { ordered: false, maxRetransmits: 0 });
```

用于：

- 鼠标位置预览
- 实时码率
- 帧率
- 丢包
- RTT

## 回退策略

H5 控制端：

1. 发 `rtc-start`。
2. 收到 `rtc-ready` 后创建 PeerConnection。
3. 进入 `checking`。
4. 如果 `8` 秒内没有 `connected/completed`，发送 `rtc-stop reason=fallback`。
5. 自动走当前 `control` 消息，使用 WebSocket/JPEG 中转。

Agent：

1. 收到 `rtc-request`。
2. 准备 PeerConnection。
3. 等 offer。
4. 创建 answer。
5. ICE 失败则释放 WebRTC 资源。
6. 如果服务端随后发普通 `control-request`，继续走旧中转。

## ICE 类型判断

连接后需要记录最终 candidate 类型：

- `host`：同局域网直连，成本最低，延迟最低。
- `srflx`：公网 P2P 打洞，成本低。
- `relay`：TURN 中转，成本高，需要计费或限速。

H5 可以通过 `pc.getStats()` 获取 selected candidate pair。

## 服务端安全要求

服务端必须检查：

- `rtc-start` 只能由已登录控制端发起。
- 用户必须绑定设备。
- `rtc-offer` 只能从该 session 的控制端发给该设备。
- `rtc-answer` 只能从该 session 的设备发给该控制端。
- `rtc-ice-candidate` 只能在该 session 双方之间转发。
- `rtc-stop` 只能由该 session 的控制端或设备发起。

服务端不应该：

- 接收任意用户向任意设备发 offer。
- 把 candidate 广播给其他客户端。
- 把 TURN 固定密码发给前端。

## TURN 临时凭证

推荐 coturn `use-auth-secret`：

```text
username = `${expireUnixTimestamp}:${userId}:${deviceId}:${sessionId}`
credential = base64(hmac-sha1(turnStaticSecret, username))
```

TTL：

- 默认 `3600` 秒。
- 会话结束后服务端标记停止。
- 后续可按会话统计 TURN 用量。

## 客户端实现任务

### H5

- 增加 RTC 管理器。
- 控制时先尝试 RTC。
- 成功后用 video 标签显示画面。
- DataChannel 发送输入。
- 失败后回退 canvas/JPEG。
- 展示连接类型：局域网/P2P/TURN/中转。

### macOS

- 当前可先实现信令接收和状态上报。
- 后续引入 WebRTC native SDK。
- ScreenCaptureKit 帧接入 WebRTC video source。
- DataChannel 接入现有输入执行逻辑。

### Windows

- 接入 WebRTC native SDK。
- 采屏用 Windows Graphics Capture 或 DXGI。
- 编码优先硬件 H.264。
- DataChannel 接入现有输入模块。
- 支持 RTC 失败回退旧 WebSocket 中转。

### Android

- 接入 WebRTC Android SDK。
- 采屏用 MediaProjection。
- 编码用 MediaCodec H.264。
- DataChannel 接入无障碍输入模块。
- 前台服务保持采集。
- RTC 失败回退旧 WebSocket 中转。

## 第一阶段验收

不要求真实远控视频，先验证连接能力：

- H5 点击设备后能发 `rtc-start`。
- Agent 能收到 `rtc-request`。
- Offer/Answer 能完成交换。
- ICE candidate 能互换。
- H5 能判断最终连接类型。
- 同局域网能出现 `host`。
- 公网 P2P 能出现 `srflx`。
- TURN 能出现 `relay`。
- 失败能自动回退 WebSocket 中转。

## 第二阶段验收

真实远控：

- H5 显示 WebRTC 视频。
- 鼠标键盘通过 DataChannel 可用。
- 断网/失败能回退。
- 统计每会话码率、时长、candidate 类型。
- 免费版超过限制自动降级或断开。
