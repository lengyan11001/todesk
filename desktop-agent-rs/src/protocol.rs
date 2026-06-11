use serde::{Deserialize, Serialize};

use crate::{capture::CaptureState, config::AgentConfig};

#[derive(Debug, Serialize)]
#[serde(tag = "type", rename_all = "kebab-case")]
pub enum ClientEvent {
    HelloDevice(DeviceStatus),
    Heartbeat(DeviceStatus),
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
    #[serde(other)]
    Other,
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
