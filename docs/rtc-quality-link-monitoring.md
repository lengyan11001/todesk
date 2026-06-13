# RTC 画质控制、链路监控和 Relay 兜底对接

## 目标

桌面端优先使用 WebRTC。H5 控制端可以选择画质档位；局域网/P2P 可以放开清晰度和帧率，TURN UDP 允许高画质但仍会消耗 TURN 服务器流量，WebSocket 中转只作为兜底并强制低规格、限时断开。

## 链路类型

- `rtc-lan`: WebRTC host candidate，局域网直连，基本不消耗服务器画面流量。
- `rtc-p2p`: WebRTC srflx/prflx candidate，公网 P2P，基本不消耗服务器画面流量。
- `rtc-turn`: WebRTC relay candidate，走 TURN UDP，会消耗 TURN 服务器流量，但不经过 Node WebSocket relay。
- `ws-relay`: WebSocket 业务中转，走 Node 服务转发画面，必须低规格并限时。
- `online`: 设备在线待命，没有控制会话。
- `offline`: 设备离线。

注意：UDP/TURN 不是免费链路。只有 `rtc-lan` 和 `rtc-p2p` 才基本不产生服务器画面流量成本。

## 质量档位

H5 发送 `rtc-start` 时携带 `quality`：

```json
{
  "type": "rtc-start",
  "deviceId": "EGF7-9T70",
  "mode": "control",
  "quality": {
    "profile": "balanced",
    "maxSide": 1920,
    "fps": 18,
    "jpegQuality": 58,
    "bitrateKbps": 3000
  }
}
```

标准档位：

| profile | maxSide | fps | jpegQuality | bitrateKbps | 用途 |
| --- | ---: | ---: | ---: | ---: | --- |
| `data` | 1280 | 10 | 42 | 1200 | 省流量 |
| `balanced` | 1920 | 18 | 58 | 3000 | 默认 |
| `hd` | 2560 | 24 | 66 | 6000 | 高清 |
| `lan` | 3840 | 30 | 74 | 12000 | 局域网/高质量 |
| `relay` | 1280 | 10 | 45 | 1200 | WebSocket 中转兜底 |

服务端会对档位做白名单限制。Windows/Android/Mac agent 都要按服务端转发的 `rtc-request.quality` 执行，不要自己相信任意前端参数。

## WebRTC 信令

控制端发起：

```json
{
  "type": "rtc-start",
  "deviceId": "DEVICE-ID",
  "mode": "control",
  "quality": { "profile": "hd", "maxSide": 2560, "fps": 24, "jpegQuality": 66, "bitrateKbps": 6000 }
}
```

服务端给设备：

```json
{
  "type": "rtc-request",
  "sessionId": "SESSION",
  "deviceId": "DEVICE-ID",
  "iceServers": [],
  "quality": { "profile": "hd", "maxSide": 2560, "fps": 24, "jpegQuality": 66, "bitrateKbps": 6000 },
  "mode": "control"
}
```

设备端需要：

- 按 `quality.maxSide` 限制采集画面最长边。
- 按 `quality.fps` 限制帧率。
- 编码器能设置码率时按 `quality.bitrateKbps` 设置。
- JPEG/DataChannel 方案按 `quality.jpegQuality` 编码。
- H264/VP8 视频轨方案优先用硬件编码，并设置目标码率。

## RTC 状态上报

控制端和设备端都可以发 `rtc-state`。至少设备端要上报 candidate 类型和流量统计：

```json
{
  "type": "rtc-state",
  "sessionId": "SESSION",
  "deviceId": "DEVICE-ID",
  "state": "connected",
  "selectedCandidateType": "host",
  "rttMs": 12,
  "bitrateKbps": 4200,
  "bytesSent": 12345678,
  "bytesReceived": 12345,
  "packetsLost": 0
}
```

`selectedCandidateType` 取 WebRTC stats 里的本地 candidate type：

- `host` -> 局域网直连
- `srflx` 或 `prflx` -> 公网 P2P
- `relay` -> TURN UDP
- 无法获取 -> `unknown`

## WebSocket Relay 兜底

RTC 不支持或连接失败后，H5 会发：

```json
{
  "type": "control",
  "deviceId": "DEVICE-ID",
  "relayFallback": true,
  "reason": "rtc_timeout"
}
```

服务端只在 `relayFallback=true` 时允许 RTC 设备进入 WebSocket 中转。服务端返回：

```json
{
  "type": "control-ready",
  "sessionId": "SESSION",
  "path": "ws-relay",
  "quality": { "profile": "relay", "maxSide": 1280, "fps": 10, "jpegQuality": 45, "bitrateKbps": 1200 },
  "ttlSeconds": 600
}
```

设备端收到 `control-request` 时也会带同一套 `quality`：

```json
{
  "type": "control-request",
  "sessionId": "SESSION",
  "controllerCount": 1,
  "quality": { "profile": "relay", "maxSide": 1280, "fps": 10, "jpegQuality": 45, "bitrateKbps": 1200 },
  "relayTtlSeconds": 600
}
```

设备端必须按 `relay` 低规格采集。服务端到期会自动 `stop-control`，reason 为 `relay_time_limit`。

## CMS 链路监控

后台接口：

```http
GET /api/admin/device-links
Authorization: Bearer <admin-token>
```

返回每台设备当前：

- `link.activePath`
- `link.activeRtcSessions`
- `link.activeRelaySessions`
- WebSocket relay 流量：`bytesFromDevice`、`bytesToControllers`
- RTC/TURN/P2P 统计：`rtcBytesSent`、`rtcBytesReceived`、`rtcBitrateKbps`、`rtcRttMs`
- 设备 owner、权限、agent 版本、屏幕信息

CMS 每 5 秒刷新一次。

## Windows 对接要求

Windows agent 需要实现：

1. `hello-device.rtcCapabilities`:
   ```json
   {
     "webrtc": true,
     "video": true,
     "dataChannel": true,
     "frameChannel": false,
     "localNetwork": true,
     "codecs": ["H264", "VP8"],
     "maxFps": 30,
     "version": "windows-rtc-1"
   }
   ```
2. 收到 `rtc-request` 后创建 PeerConnection，添加视频 track，创建/接收 `control` DataChannel。
3. 视频采集优先用 Windows Graphics Capture，编码优先硬件 H264。
4. 按 `quality` 设置采集尺寸、FPS、目标码率。
5. 从 WebRTC stats 周期上报 `rtc-state`。
6. `control` DataChannel 收到 H5 input JSON 后执行鼠标/键盘，并回传 `input-result`。
7. RTC 活跃时必须停止 WebSocket relay 截屏。
8. 收到 `control-request.quality.profile=relay` 时按 1280/10fps/低码率走旧中转。

## Android 对接要求

Android agent 需要实现：

1. `hello-device.rtcCapabilities` 同样上报 WebRTC 能力。
2. WebRTC 视频轨使用 MediaProjection + SurfaceTexture/VideoCapturer。
3. 编码优先硬件 H264，按 `quality.bitrateKbps`、`quality.fps` 配置。
4. DataChannel `control` 执行触控、文本、按键。
5. RTC 活跃时停止旧的 WebSocket 图片帧上传。
6. 如果系统 WebRTC 不可用或 MediaProjection 未授权，返回 `rtc-state failed`，让 H5 走 relay 兜底。

## 验收标准

1. 局域网同网段设备，CMS 显示 `局域网直连`，WebSocket relay 字节不增长。
2. 非同网段但可打洞，CMS 显示 `公网 P2P`，WebSocket relay 字节不增长。
3. 无法打洞时，CMS 显示 `TURN UDP`，Node relay 字节不增长，但 RTC 字节增长。
4. RTC 失败后，CMS 显示 `WebSocket 中转`，画质固定 relay 档，并在 10 分钟到期自动断开。
5. 用户切换 H5 画质档位后，新建 RTC 会话按新档位生效。
