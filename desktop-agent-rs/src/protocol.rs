use serde::{Deserialize, Serialize};

use crate::{capture::CaptureState, config::AgentConfig};

#[derive(Debug, Serialize)]
#[serde(tag = "type", rename_all = "kebab-case")]
pub enum ClientEvent {
    HelloDevice(DeviceStatus),
    Heartbeat(DeviceStatus),
    RtcAnswer(RtcSdp),
    RtcIceCandidate(RtcIceCandidateSignal),
    RtcState(RtcState),
    RtcStop(RtcStop),
    #[serde(rename = "file-transfer-status")]
    FileTransferStatus(FileTransferStatus),
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BinaryFrameHeader {
    pub frame_id: u64,
    pub frame_kind: String,
    pub width: u32,
    pub height: u32,
    pub input_width: u32,
    pub input_height: u32,
    pub timestamp: u64,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceStatus {
    pub id: String,
    pub name: String,
    pub model: String,
    pub platform: String,
    pub os_version: String,
    pub agent_version: String,
    pub permissions: Permissions,
    pub control_enabled: bool,
    pub screen: ScreenInfo,
    pub verification_code: String,
    pub rtc_capabilities: RtcCapabilities,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Permissions {
    pub screen_capture: bool,
    pub input_control: bool,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ScreenInfo {
    pub width: u32,
    pub height: u32,
    pub input_width: u32,
    pub input_height: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RtcCapabilities {
    pub webrtc: bool,
    pub video: bool,
    pub data_channel: bool,
    pub frame_channel: bool,
    pub local_network: bool,
    pub codecs: Vec<String>,
    pub max_fps: u32,
    pub version: String,
}

impl RtcCapabilities {
    pub fn frame_channel(version: &str) -> Self {
        Self {
            webrtc: true,
            video: false,
            data_channel: true,
            frame_channel: true,
            local_network: true,
            codecs: Vec::new(),
            max_fps: 30,
            version: format!("{version};rtc-frame-channel"),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct QualityProfile {
    pub profile: Option<String>,
    pub max_side: Option<u32>,
    pub fps: Option<u32>,
    pub jpeg_quality: Option<u8>,
    pub bitrate_kbps: Option<u32>,
}

impl QualityProfile {
    pub fn relay() -> Self {
        Self {
            profile: Some("relay".to_string()),
            max_side: Some(1280),
            fps: Some(10),
            jpeg_quality: Some(42),
            bitrate_kbps: Some(1800),
        }
    }

    pub fn balanced() -> Self {
        Self {
            profile: Some("balanced".to_string()),
            max_side: Some(1600),
            fps: Some(18),
            jpeg_quality: Some(56),
            bitrate_kbps: Some(3200),
        }
    }

    pub fn with_fallback(value: Option<Self>, fallback: Self) -> Self {
        value.unwrap_or_else(|| fallback.clone()).sanitize_with(&fallback)
    }

    pub fn sanitize_with(self, fallback: &Self) -> Self {
        let fallback_profile = fallback.profile.as_deref().unwrap_or("balanced");
        let profile = self
            .profile
            .as_deref()
            .map(|item| item.trim().to_ascii_lowercase())
            .filter(|item| matches!(item.as_str(), "hd" | "balanced" | "data" | "lan" | "relay"))
            .unwrap_or_else(|| fallback_profile.to_string());
        Self {
            profile: Some(profile),
            max_side: Some(self.max_side.unwrap_or_else(|| fallback.max_side_value()).clamp(480, 2560)),
            fps: Some(self.fps.unwrap_or_else(|| fallback.fps_value()).clamp(3, 30)),
            jpeg_quality: Some(self.jpeg_quality.unwrap_or_else(|| fallback.jpeg_quality_value()).clamp(30, 85)),
            bitrate_kbps: Some(self.bitrate_kbps.unwrap_or_else(|| fallback.bitrate_kbps_value()).clamp(300, 20_000)),
        }
    }

    pub fn profile_name(&self) -> &str {
        self.profile.as_deref().unwrap_or("balanced")
    }

    pub fn max_side_value(&self) -> u32 {
        self.max_side.unwrap_or(1280).clamp(480, 2560)
    }

    pub fn fps_value(&self) -> u32 {
        self.fps.unwrap_or(15).clamp(3, 30)
    }

    pub fn jpeg_quality_value(&self) -> u8 {
        self.jpeg_quality.unwrap_or(48).clamp(30, 85)
    }

    pub fn bitrate_kbps_value(&self) -> u32 {
        self.bitrate_kbps.unwrap_or(1800).clamp(300, 20_000)
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RtcSdp {
    pub session_id: String,
    pub device_id: String,
    pub sdp: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RtcIceCandidate {
    pub candidate: String,
    pub sdp_mid: Option<String>,
    #[serde(rename = "sdpMLineIndex")]
    pub sdp_mline_index: Option<u16>,
    pub username_fragment: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RtcIceCandidateSignal {
    pub session_id: String,
    pub device_id: String,
    pub candidate: RtcIceCandidate,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RtcState {
    pub session_id: String,
    pub device_id: String,
    pub state: String,
    pub selected_candidate_type: String,
    pub rtt_ms: u32,
    pub bitrate_kbps: u32,
    pub bytes_sent: u64,
    pub bytes_received: u64,
    pub packets_lost: u64,
    pub error: String,
}

impl RtcState {
    #[allow(dead_code)]
    pub fn failed(device_id: &str, session_id: &str, error: &str) -> Self {
        Self {
            session_id: session_id.to_string(),
            device_id: device_id.to_string(),
            state: "failed".to_string(),
            selected_candidate_type: "unknown".to_string(),
            rtt_ms: 0,
            bitrate_kbps: 0,
            bytes_sent: 0,
            bytes_received: 0,
            packets_lost: 0,
            error: error.chars().take(400).collect(),
        }
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RtcStop {
    pub session_id: String,
    pub device_id: String,
    pub reason: String,
}

impl RtcStop {
    pub fn new(device_id: &str, session_id: &str, reason: &str) -> Self {
        Self {
            session_id: session_id.to_string(),
            device_id: device_id.to_string(),
            reason: reason.to_string(),
        }
    }
}

impl DeviceStatus {
    pub fn from_config(config: &AgentConfig, version: &str, capture: &CaptureState) -> Self {
        let screen = capture.screen_info();
        Self {
            id: config.device_id.clone(),
            name: config.name.clone(),
            model: std::env::consts::ARCH.to_string(),
            platform: platform_name().to_string(),
            os_version: std::env::consts::OS.to_string(),
            agent_version: version.to_string(),
            permissions: Permissions { screen_capture: true, input_control: true },
            control_enabled: true,
            screen,
            verification_code: config.verification_code.clone(),
            rtc_capabilities: RtcCapabilities::frame_channel(version),
        }
    }
}

fn platform_name() -> &'static str {
    if cfg!(target_os = "windows") {
        "windows"
    } else if cfg!(target_os = "macos") {
        "macos"
    } else {
        "linux"
    }
}

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all = "kebab-case")]
pub enum ServerEvent {
    #[serde(rename = "input")]
    Input(InputEvent),
    #[serde(rename = "server-replaced")]
    ServerReplaced,
    #[serde(rename = "control-request")]
    ControlRequest {
        #[serde(rename = "sessionId")]
        session_id: Option<String>,
        quality: Option<QualityProfile>,
    },
    #[serde(rename = "stop-control")]
    StopControl {
        #[serde(rename = "sessionId")]
        session_id: Option<String>,
    },
    #[serde(rename = "hello")]
    Hello { _role: Option<String>, _id: Option<String> },
    #[serde(rename = "file-transfer")]
    FileTransfer(FileTransferRequest),
    #[serde(rename = "rtc-request")]
    RtcRequest {
        #[serde(rename = "sessionId")]
        session_id: String,
        #[serde(rename = "deviceId")]
        _device_id: Option<String>,
        #[serde(rename = "controllerId")]
        _controller_id: Option<String>,
        #[serde(rename = "iceServers", default)]
        ice_servers: Vec<RtcIceServer>,
        quality: Option<QualityProfile>,
        _mode: Option<String>,
    },
    #[serde(rename = "rtc-offer")]
    RtcOffer {
        #[serde(rename = "sessionId")]
        session_id: String,
        #[serde(rename = "deviceId")]
        _device_id: Option<String>,
        sdp: Option<String>,
    },
    #[serde(rename = "rtc-ice-candidate")]
    RtcIceCandidate {
        #[serde(rename = "sessionId")]
        session_id: String,
        #[serde(rename = "deviceId")]
        _device_id: Option<String>,
        candidate: Option<RtcIceCandidate>,
    },
    #[serde(rename = "rtc-stopped")]
    RtcStopped {
        #[serde(rename = "sessionId")]
        session_id: String,
        #[serde(rename = "deviceId")]
        _device_id: Option<String>,
        _reason: Option<String>,
    },
    #[serde(other)]
    Other,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FileTransferRequest {
    pub transfer_id: String,
    pub file_name: String,
    pub size: u64,
    pub sha256: String,
    pub url: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FileTransferStatus {
    pub transfer_id: String,
    pub status: String,
    pub path: String,
    pub bytes: u64,
    pub error: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InputEvent {
    pub action: String,
    pub x: Option<f64>,
    pub y: Option<f64>,
    pub x2: Option<f64>,
    pub y2: Option<f64>,
    pub duration: Option<f64>,
    pub button: Option<String>,
    pub delta_y: Option<f64>,
    pub key: Option<String>,
    pub text: Option<String>,
    pub modifiers: Option<Vec<String>>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RtcIceServer {
    pub urls: RtcIceServerUrls,
    pub username: Option<String>,
    pub credential: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(untagged)]
pub enum RtcIceServerUrls {
    One(String),
    Many(Vec<String>),
}

impl RtcIceServerUrls {
    pub fn into_vec(self) -> Vec<String> {
        match self {
            Self::One(value) => vec![value],
            Self::Many(values) => values,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn serializes_rtc_state_event() {
        let json = serde_json::to_value(ClientEvent::RtcState(RtcState::failed(
            "ABCD-1234",
            "session-1",
            "start_failed",
        )))
        .unwrap();

        assert_eq!(json["type"], "rtc-state");
        assert_eq!(json["sessionId"], "session-1");
        assert_eq!(json["deviceId"], "ABCD-1234");
        assert_eq!(json["state"], "failed");
        assert_eq!(json["selectedCandidateType"], "unknown");
        assert_eq!(json["bytesSent"], 0);
        assert_eq!(json["bytesReceived"], 0);
        assert_eq!(json["packetsLost"], 0);
        assert_eq!(json["error"], "start_failed");
    }

    #[test]
    fn deserializes_rtc_request_event() {
        let event: ServerEvent = serde_json::from_str(
            r#"{"type":"rtc-request","sessionId":"session-1","deviceId":"ABCD-1234","controllerId":"user-1","quality":{"profile":"lan","maxSide":2560,"fps":30,"jpegQuality":70}}"#,
        )
        .unwrap();

        match event {
            ServerEvent::RtcRequest { session_id, quality, .. } => {
                assert_eq!(session_id, "session-1");
                let quality = QualityProfile::with_fallback(quality, QualityProfile::balanced());
                assert_eq!(quality.profile_name(), "lan");
                assert_eq!(quality.max_side_value(), 2560);
                assert_eq!(quality.fps_value(), 30);
                assert_eq!(quality.jpeg_quality_value(), 70);
            }
            other => panic!("unexpected event: {other:?}"),
        }
    }

    #[test]
    fn serializes_frame_channel_rtc_capabilities() {
        let capabilities = serde_json::to_value(RtcCapabilities::frame_channel("0.2.10-rs")).unwrap();

        assert_eq!(capabilities["webrtc"], true);
        assert_eq!(capabilities["video"], false);
        assert_eq!(capabilities["dataChannel"], true);
        assert_eq!(capabilities["frameChannel"], true);
        assert_eq!(capabilities["localNetwork"], true);
        assert_eq!(capabilities["maxFps"], 30);
        assert_eq!(capabilities["version"], "0.2.10-rs;rtc-frame-channel");
    }
}
