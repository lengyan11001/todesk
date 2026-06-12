use serde::{Deserialize, Serialize};

use crate::{capture::CaptureState, config::AgentConfig};

#[derive(Debug, Serialize)]
#[serde(tag = "type", rename_all = "kebab-case")]
pub enum ClientEvent {
    HelloDevice(DeviceStatus),
    Heartbeat(DeviceStatus),
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
    pub local_network: bool,
    pub codecs: Vec<String>,
    pub max_fps: u32,
    pub version: String,
}

impl RtcCapabilities {
    pub fn pending_native(version: &str) -> Self {
        Self {
            webrtc: false,
            video: false,
            data_channel: false,
            local_network: true,
            codecs: Vec::new(),
            max_fps: 0,
            version: format!("{version};native-webrtc-pending"),
        }
    }
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
    pub error: String,
}

impl RtcState {
    pub fn failed(device_id: &str, session_id: &str, error: &str) -> Self {
        Self {
            session_id: session_id.to_string(),
            device_id: device_id.to_string(),
            state: "failed".to_string(),
            selected_candidate_type: "unknown".to_string(),
            rtt_ms: 0,
            bitrate_kbps: 0,
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
            rtc_capabilities: RtcCapabilities::pending_native(version),
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
    ControlRequest { #[serde(rename = "sessionId")] _session_id: Option<String> },
    #[serde(rename = "stop-control")]
    StopControl { #[serde(rename = "sessionId")] _session_id: Option<String> },
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
        _mode: Option<String>,
    },
    #[serde(rename = "rtc-offer")]
    RtcOffer {
        #[serde(rename = "sessionId")]
        session_id: String,
        #[serde(rename = "deviceId")]
        _device_id: Option<String>,
        _sdp: Option<String>,
    },
    #[serde(rename = "rtc-ice-candidate")]
    RtcIceCandidate {
        #[serde(rename = "sessionId")]
        session_id: String,
        #[serde(rename = "deviceId")]
        _device_id: Option<String>,
        _candidate: Option<serde_json::Value>,
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn serializes_rtc_state_event() {
        let json = serde_json::to_value(ClientEvent::RtcState(RtcState::failed(
            "ABCD-1234",
            "session-1",
            "native_webrtc_pending",
        )))
        .unwrap();

        assert_eq!(json["type"], "rtc-state");
        assert_eq!(json["sessionId"], "session-1");
        assert_eq!(json["deviceId"], "ABCD-1234");
        assert_eq!(json["state"], "failed");
        assert_eq!(json["selectedCandidateType"], "unknown");
        assert_eq!(json["error"], "native_webrtc_pending");
    }

    #[test]
    fn deserializes_rtc_request_event() {
        let event: ServerEvent = serde_json::from_str(
            r#"{"type":"rtc-request","sessionId":"session-1","deviceId":"ABCD-1234","controllerId":"user-1"}"#,
        )
        .unwrap();

        match event {
            ServerEvent::RtcRequest { session_id, .. } => assert_eq!(session_id, "session-1"),
            other => panic!("unexpected event: {other:?}"),
        }
    }

    #[test]
    fn serializes_pending_rtc_capabilities() {
        let capabilities = serde_json::to_value(RtcCapabilities::pending_native("0.2.9-rs")).unwrap();

        assert_eq!(capabilities["webrtc"], false);
        assert_eq!(capabilities["video"], false);
        assert_eq!(capabilities["dataChannel"], false);
        assert_eq!(capabilities["localNetwork"], true);
        assert_eq!(capabilities["version"], "0.2.9-rs;native-webrtc-pending");
    }
}
